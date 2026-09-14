package com.travelscope.agent.tools;

import com.alibaba.fastjson2.JSONObject;
import com.travelscope.dto.TripRequirementState;
import com.travelscope.service.TripRequirementStore;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 需求状态机工具（v3 需求文档 3.3 决策三 / FR-S02：状态机下沉为代码工具）
 * <p>
 * 「缺什么」由代码计算（{@link TripRequirementState#missingFields()}，零模型调用），
 * 「怎么问、怎么听」由 intake-agent 的语言能力承载。注册进全局 Toolkit，
 * 供 intake-agent 调用；master 也可在委派前调用 get_missing_fields 预判是否需要反问。
 * </p>
 * <p>
 * userId 取自 Toolkit 注入的 RuntimeContext（方法参数不带 @ToolParam 即为上下文注入），
 * sessionId 由本轮路由指令明确给出；TripRequirementStore 按 userId+sessionId 双键隔离。
 * </p>
 */
public class RequirementTools {

    private static final Logger log = LoggerFactory.getLogger(RequirementTools.class);

    private final TripRequirementStore store;

    public RequirementTools(TripRequirementStore store) {
        this.store = store;
    }

    /**
     * 查询还缺哪些必填项（纯代码计算，判断环节零模型调用）
     *
     * @param ctx Toolkit 自动注入的运行时上下文（取 userId，勿加 @ToolParam 注解）
     */
    @Tool(description = "查询当前会话行程需求的收集状态：已收集哪些字段、还缺哪些必填项"
            + "（destination/days/startDate/fromCity）、已反问几轮。"
            + "必填项收齐时返回「已全部收齐」——此时应写 intake_done.md 并收尾")
    public String get_missing_fields(
            @ToolParam(name = "sessionId", description = "本轮会话 ID，使用路由指令中给出的值，如 conv-13")
            String sessionId,
            RuntimeContext ctx) {
        String userId = ctx.getUserId();
        TripRequirementState s = store.get(userId, sessionId);
        log.info("get_missing_fields: 用户={}, 会话={}", userId, sessionId);
        return s.summary();
    }

    /**
     * 写回需求字段（intake-agent 理解用户回答后调用）
     */
    @Tool(description = "把本轮理解到的需求字段写回状态机。fieldsJson 形如 "
            + "{\"destination\":\"北京\",\"days\":3,\"startDate\":\"2026-10-01\",\"fromCity\":\"上海\","
            + "\"budget\":\"3000 元以内\",\"preference\":\"历史文化\",\"people\":2,\"special\":\"不吃辣\"}，"
            + "只写本轮确实获得的字段，用户没说的不要编。写回后请再调 get_missing_fields 确认缺项")
    public String update_requirement_state(
            @ToolParam(name = "sessionId", description = "本轮会话 ID，使用路由指令中给出的值")
            String sessionId,
            @ToolParam(name = "fieldsJson", description = "需求字段 JSON 对象字符串，只含本轮获得的字段")
            String fieldsJson,
            RuntimeContext ctx) {
        String userId = ctx.getUserId();
        TripRequirementState s = store.get(userId, sessionId);
        JSONObject fields;
        try {
            fields = JSONObject.parseObject(fieldsJson);
        } catch (Exception e) {
            log.warn("fieldsJson 解析失败: {}", e.getMessage());
            return "ERROR: fieldsJson 无法解析为 JSON 对象，请传形如 {\"destination\":\"北京\",\"days\":3} 的对象字符串";
        }
        if (fields == null || fields.isEmpty()) {
            return "ERROR: fieldsJson 为空，没有可写回的字段";
        }

        // 只接受状态机定义的字段，防止 Agent 写入未知键
        int applied = 0;
        if (fields.containsKey("destination")) {
            s.destination = fields.getString("destination");
            applied++;
        }
        if (fields.containsKey("days")) {
            Integer days = fields.getInteger("days");
            if (days == null || days <= 0) {
                return "ERROR: days 必须为正整数";
            }
            s.days = days;
            applied++;
        }
        if (fields.containsKey("startDate")) {
            s.startDate = fields.getString("startDate");
            applied++;
        }
        if (fields.containsKey("fromCity")) {
            s.fromCity = fields.getString("fromCity");
            applied++;
        }
        if (fields.containsKey("budget")) {
            s.budget = fields.getString("budget");
            applied++;
        }
        if (fields.containsKey("preference")) {
            s.preference = fields.getString("preference");
            applied++;
        }
        if (fields.containsKey("people")) {
            Integer people = fields.getInteger("people");
            if (people == null || people <= 0) {
                return "ERROR: people 必须为正整数";
            }
            s.people = people;
            applied++;
        }
        if (fields.containsKey("special")) {
            s.special = fields.getString("special");
            applied++;
        }
        if (applied == 0) {
            return "ERROR: fieldsJson 中没有状态机认可的字段（认可: destination/days/startDate/fromCity/"
                    + "budget/preference/people/special）";
        }

        // 必填收齐则置 DONE（DEGRADED 一旦置位不回退——放行决策已做出）
        if (s.missingFields().isEmpty() && s.status == TripRequirementState.Status.COLLECTING) {
            s.status = TripRequirementState.Status.DONE;
        }
        log.info("update_requirement_state: 用户={}, 会话={}, 写回 {} 个字段", userId, sessionId, applied);
        return "已写回 " + applied + " 个字段。" + s.summary();
    }
}
