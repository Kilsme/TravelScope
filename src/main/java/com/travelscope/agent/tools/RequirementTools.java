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

    /**
     * 主会话 ID 的路由指令来源键（IntentRouterMiddleware.CTX_COLLAB_DIR_KEY，
     * 值形如 "tasks/conv-29"）。master 在每轮注入，子代理经 RuntimeContext 继承可见——
     * intake-agent 被 spawn 后自身 ctx.sessionId 是 sub-xxx（框架生成），不能作为状态键。
     */
    private static final String CTX_COLLAB_DIR_KEY = "travelscope.collab.dir";

    private final TripRequirementStore store;

    public RequirementTools(TripRequirementStore store) {
        this.store = store;
    }

    /**
     * 解析主会话 ID（状态键的会话维度）：
     * ① 参数为合法主会话格式（conv-数字）→ 直接信任（master/planning-agent 显式传值）。
     *    刻意要求「conv-数字」而不仅 conv- 前缀——实测 intake 偶尔把参数填成框架生成的
     *    UUID 会话号（conv-05c5fa75-…，同样以 conv- 开头），骗过前缀检查后状态写进
     *    孤儿键，主会话键上字段丢失（2026-09-21 E2E 复现）。
     * ② 否则从 ctx 的协作目录键解析（tasks/conv-29 → conv-29；intake-agent 子代理继承 master 注入）
     * ③ 都没有 → 回退参数原值（保持旧行为，日志可查）
     */
    private static String resolveSessionId(String sessionId, RuntimeContext ctx) {
        if (isMainSessionId(sessionId)) {
            return sessionId;
        }
        Object collabDir = ctx.get(CTX_COLLAB_DIR_KEY);
        if (collabDir != null) {
            String dir = String.valueOf(collabDir);
            int idx = dir.lastIndexOf('/');
            if (idx >= 0 && idx < dir.length() - 1) {
                String parsed = dir.substring(idx + 1);
                if (isMainSessionId(parsed)) {
                    return parsed;
                }
            }
        }
        if (sessionId != null && !sessionId.isBlank()) {
            log.warn("sessionId 参数非主会话格式（{}），已回退 ctx 协作目录/原值解析", sessionId);
        }
        return sessionId;
    }

    /** 主会话 ID 格式：conv- + 纯数字（conversations 表主键） */
    private static boolean isMainSessionId(String sessionId) {
        return sessionId != null && sessionId.matches("conv-\\d+");
    }

    /**
     * 查询还缺哪些必填项（纯代码计算，判断环节零模型调用）
     *
     * @param ctx Toolkit 自动注入的运行时上下文（取 userId，勿加 @ToolParam 注解）
     */
    @Tool(description = "查询当前会话行程需求的收集状态：已收集哪些字段、还缺哪些必填项"
            + "（destination/days/startDate/fromCity）、已反问几轮。"
            + "必填项收齐时返回「已全部收齐」——此时应写 intake_done.md 并收尾；"
            + "状态显示 DEGRADED（已反问满 3 轮仍缺）时不得再反问，带默认值收尾")
    public String get_missing_fields(
            @ToolParam(name = "sessionId", description = "本轮会话 ID，使用路由指令中给出的值，如 conv-13")
            String sessionId,
            RuntimeContext ctx) {
        String userId = ctx.getUserId();
        sessionId = resolveSessionId(sessionId, ctx);
        TripRequirementState s = store.get(userId, sessionId);
        log.info("get_missing_fields: 用户={}, 会话={}", userId, sessionId);
        return s.summary();
    }

    /**
     * 反问统一出口（FR-S02）：intake-agent 的每一次反问都必须经本工具发出——
     * 直接文本输出的反问不会实时到达用户（SSE clarify_question 事件挂在工具结果上）。
     * 每次调用自动累加反问轮次，达到 3 轮且必填仍缺时状态置 DEGRADED。
     */
    @Tool(description = "向用户发出一轮反问（intake-agent 唯一反问出口，不要在文本里反问）。"
            + "每次调用自动累加反问轮次（上限 3 轮，超限后 get_missing_fields 显示 DEGRADED，"
            + "届时不得再调用本工具，须带默认值收尾）。question 为一句选择题式反问，"
            + "如「想去哪里玩？国内 / 国外 / 还没定（热门：成都、杭州、西安）」")
    public String ask_user(
            @ToolParam(name = "question", description = "反问内容（一句，选择题式，一次只问最关键的 1~2 项）")
            String question,
            @ToolParam(name = "sessionId", description = "本轮会话 ID，使用路由指令中给出的值")
            String sessionId,
            RuntimeContext ctx) {
        String userId = ctx.getUserId();
        sessionId = resolveSessionId(sessionId, ctx);
        TripRequirementState s = store.incrementClarifyCycles(userId, sessionId);
        log.info("ask_user: 用户={}, 会话={}, 第 {} 轮反问, 状态={}",
                userId, sessionId, s.clarifyCycles, s.status);
        if (s.status == TripRequirementState.Status.DEGRADED) {
            return "[DEGRADED]已反问满 " + s.clarifyCycles + " 轮仍缺必填项。"
                    + "不要再调用 ask_user，请立即按默认值收尾：写 intake_done.md"
                    + "（缺项标注「待确认」）并返回「信息已收齐（部分待确认）」。";
        }
        // 返回值经 ToolResultTextDelta 流向 SSE 层：分号前是用户可见的反问（clarify_question 事件截取），
        // 分号后是给 LLM 的轮次提示（不出现在事件里）。附带已收集字段摘要是失忆修复 Fix 4：
        // 硬提醒 intake「这些字段已经问过了，绝不能再问」——防止无视 get_missing_fields
        // 重复追问已答过的目的地/出发城市（2026-09-21 实测复现过）
        return question + ";;[intake]反问已发送给用户（第 " + s.clarifyCycles + "/"
                + TripRequirementStore.MAX_CLARIFY_CYCLES
                + " 轮），等待用户下轮回答后由主 Agent 重新委派你继续。"
                + "当前已收集: " + s.collectedDescription()
                + "。已收集的字段绝不能再次反问；下轮继续时先 update_requirement_state 写回新信息，"
                + "再 get_missing_fields 确认剩余缺项";
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
        sessionId = resolveSessionId(sessionId, ctx);
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
        store.save(userId, sessionId, s);   // Redis 模式下 get 返回副本，必须显式写回
        log.info("update_requirement_state: 用户={}, 会话={}, 写回 {} 个字段", userId, sessionId, applied);
        return "已写回 " + applied + " 个字段。" + s.summary();
    }
}
