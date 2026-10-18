package com.travelscope.service.workflow;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.travelscope.agent.tools.TransportTool;
import com.travelscope.collab.IntakeResult;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.dto.TripRequirementState;
import com.travelscope.service.TripRequirementStore;
import com.travelscope.service.workflow.dto.CollectOutcome;
import com.travelscope.service.workflow.dto.RequirementExtraction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 需求采集 workflow 主体（S2.2，清单卡片方案）：COLLECTING → DONE/DEGRADED 状态机，
 * 取代 v3 的 intake-agent（v4 计划 §6.1）。
 * <p>
 * 输入三分流（{@link #handle}）：
 * <ol>
 *   <li><b>表单提交</b>（消息体含 {@code __form__} 键）→ 按表单值直接 merge（不过 LLM）；
 *       {@code __action__}=cancel 复位状态机 / skip 与留空提交走默认值放行；</li>
 *   <li><b>普通文本</b>（COLLECTING 期间的自然语言增量修改）→ {@link RequirementExtractor}
 *       抽取提及字段（null 字段不动）→ merge；抽取全空返回清单规格 + 提示文案；</li>
 *   <li><b>非 COLLECTING</b>（DONE/DEGRADED）→ 幂等直通 COLLECTED，不重新采集。</li>
 * </ol>
 * </p>
 * <p>
 * 收齐（必填四项无缺失）→ status=DONE、落盘 intake_result.json（degraded=false）、
 * 返回 COLLECTED；留空提交/「按推荐安排」→ 缺失必填填确定性默认值、status=DEGRADED、
 * degraded=true 落盘、返回 COLLECTED（「已尽力」语义由下游 itinerary-agent 标注）。
 * </p>
 * <p>
 * 与 RequirementTools（v3 工具面，S2.9 前仍在线）的写回语义对齐：8 键白名单、
 * COLLECTING→DONE 单向、DEGRADED 不回退、merge 后必显式 save（Redis 副本语义）。
 * 差异（Javadoc 注明）：days/people 非法值此处静默跳过该字段——工具面的 ERROR 文案
 * 是给 LLM 的，表单面由前端控件兜底 + 缺项重呈现。
 * </p>
 */
@Service
public class RequirementWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(RequirementWorkflowService.class);

    /** 默认值（确定性常量；v3 的默认值原在 intake-agent 提示词层，2026-10-17 起代码化） */
    private static final String DEFAULT_DESTINATION = "杭州";
    private static final int DEFAULT_DAYS = 2;
    /** startDate 默认 = 明天 */
    private static final long DEFAULT_START_DATE_OFFSET_DAYS = 1;
    /** fromCity 默认 = 与目的地同城 */

    private final RequirementExtractor extractor;
    private final TripRequirementStore store;
    private final TaskWorkspace taskWorkspace;
    private final TransportTool transportTool;

    public RequirementWorkflowService(RequirementExtractor extractor,
                                      TripRequirementStore store,
                                      TaskWorkspace taskWorkspace,
                                      TransportTool transportTool) {
        this.extractor = extractor;
        this.store = store;
        this.taskWorkspace = taskWorkspace;
        this.transportTool = transportTool;
    }

    /**
     * 处理一轮需求采集输入。
     *
     * @param userMessage 用户消息原文或 __form__ 结构化提交（{"__form__":{...}}）
     * @param userId      用户 ID
     * @param sessionId   会话 ID（conv- 前缀）
     * @return 本轮终态（COLLECTED / NEED_USER_INPUT / CANCELLED）
     */
    public CollectOutcome handle(String userMessage, String userId, String sessionId) {
        TripRequirementState state = store.get(userId, sessionId);
        // 分流 c：DONE/DEGRADED 幂等直通（重复触发不重新采集）
        if (state.status != TripRequirementState.Status.COLLECTING) {
            log.info("需求采集幂等直通: status={}, 用户={}, 会话={}", state.status, userId, sessionId);
            return CollectOutcome.collected();
        }
        JSONObject form = parseFormMessage(userMessage);
        if (form != null) {
            // 分流 a：结构化表单提交（不过 LLM）
            return handleForm(form, state, userId, sessionId);
        }
        // 分流 b：普通文本（增量修改）
        return handleText(userMessage, state, userId, sessionId);
    }

    // ==================== 分支 a：表单提交 ====================

    private CollectOutcome handleForm(JSONObject form, TripRequirementState state,
                                      String userId, String sessionId) {
        String action = form.getString("__action__");
        if ("cancel".equals(action)) {
            store.clear(userId, sessionId);
            log.info("需求采集取消（状态机复位）: 用户={}, 会话={}", userId, sessionId);
            return CollectOutcome.cancelled();
        }
        // 用户已填字段先 merge（__action__ 不在白名单自然忽略；skip 同样先保留已填项）
        int applied = merge(state, form);
        // S2.3 提交校验：destination/fromCity 走高德 geocode（控件天然免疫错值的字段不查）。
        // 校验只针对本次提交携带的字段（避免每轮重复请求）；失败时不落状态机、不填默认值，
        // 用内存中已合并的 state 回推清单（用户已填内容不丢）+ 打回字段标红
        List<CollectOutcome.FieldError> errors = validatePlaces(form);
        if (!errors.isEmpty()) {
            log.info("需求采集表单校验失败: 用户={}, 会话={}, 错误字段={}",
                    userId, sessionId, errors.stream().map(CollectOutcome.FieldError::field).toList());
            return CollectOutcome.needUserInput(RequirementFormSpec.generate(state),
                    "以下地点没认出来，请修改", errors);
        }
        Set<String> defaulted = new LinkedHashSet<>();
        if (!state.missingFields().isEmpty()) {
            // 留空提交 / 按推荐安排：缺失必填填默认值，DEGRADED 放行（用户主动选默认）
            fillDefaults(state, defaulted);
        }
        finalizeCollected(state, userId, sessionId, defaulted,
                "form:" + (action == null ? "submit" : action));
        log.info("需求采集表单提交完成: 用户={}, 会话={}, 写入字段={}, 默认值={}, 终态={}",
                userId, sessionId, applied, defaulted.size(), state.status);
        return CollectOutcome.collected();
    }

    // ==================== 分支 b：普通文本（增量修改） ====================

    private CollectOutcome handleText(String userMessage, TripRequirementState state,
                                      String userId, String sessionId) {
        RequirementExtraction extraction = extractor.extract(userMessage);
        if (extraction.fields().isEmpty()) {
            log.info("增量修改抽取为空（回提示）: 用户={}, 会话={}", userId, sessionId);
            return CollectOutcome.needUserInput(RequirementFormSpec.generate(state),
                    "没听懂要改哪项，可直接点卡片修改");
        }
        int applied = merge(state, extraction.fields());
        if (state.missingFields().isEmpty()) {
            finalizeCollected(state, userId, sessionId, Set.of(), "text-complete");
            return CollectOutcome.collected();
        }
        store.save(userId, sessionId, state);
        log.info("增量修改完成: 用户={}, 会话={}, 写入字段={}, 仍缺={}",
                userId, sessionId, applied, state.missingFields());
        return CollectOutcome.needUserInput(RequirementFormSpec.generate(state), null);
    }

    // ==================== 内部：提交校验 ====================

    /**
     * 提交时地点校验（S2.3）：destination/fromCity 非空则调高德 geocode——
     * 返回 error 或无 location 视为「查无此地」，该字段进 errors 打回。
     * <p>
     * 只校验本次提交携带的字段（避免每轮重复请求）；高德 Key 未配置时整体跳过
     * （log.debug 不阻断——开发/测试环境无 key 不应使表单不可用）。
     * </p>
     */
    private List<CollectOutcome.FieldError> validatePlaces(JSONObject form) {
        List<CollectOutcome.FieldError> errors = new ArrayList<>();
        for (String field : List.of("destination", "fromCity")) {
            String place = form.getString(field);
            if (place == null || place.isBlank()) {
                continue;
            }
            try {
                String json = transportTool.geocode(place.trim());
                JSONObject result = JSON.parseObject(json);
                if (result == null) {
                    continue;   // 非法响应按放行（校验是增强，不是硬门禁）
                }
                String error = result.getString("error");
                if (error != null && error.contains("未配置")) {
                    log.debug("高德 Key 未配置，跳过地点校验: field={}", field);
                    return List.of();
                }
                if (error != null || result.getString("location") == null) {
                    errors.add(new CollectOutcome.FieldError(field,
                            "没认出这个地点「" + place.trim() + "」，请换成明确的城市名"));
                }
            } catch (Exception e) {
                log.warn("地点校验异常（按放行处理）: field={}, place={}, 原因={}",
                        field, place, e.getMessage());
            }
        }
        return errors;
    }

    // ==================== 内部：merge / 默认值 / 落盘 ====================

    /**
     * 白名单写回（语义对齐 RequirementTools.update_requirement_state）：只写
     * 8 个认可字段；null/空白跳过；days/people 正整数强转（非法或 ≤0 静默跳过该
     * 字段——工具面的 ERROR 中止是给 LLM 的，表单面由控件兜底 + 缺项重呈现）。
     *
     * @return 实际写入的字段数
     */
    private static int merge(TripRequirementState state, Map<String, Object> fields) {
        int applied = 0;
        for (String key : RequirementExtraction.FIELDS) {
            if (!fields.containsKey(key)) {
                continue;
            }
            Object raw = fields.get(key);
            if (raw == null || (raw instanceof String s && s.isBlank())) {
                continue;
            }
            switch (key) {
                case "days" -> {
                    Integer v = positiveInt(raw);
                    if (v == null) {
                        continue;
                    }
                    state.days = v;
                }
                case "people" -> {
                    Integer v = positiveInt(raw);
                    if (v == null) {
                        continue;
                    }
                    state.people = v;
                }
                case "destination" -> state.destination = String.valueOf(raw);
                case "startDate" -> state.startDate = String.valueOf(raw);
                case "fromCity" -> state.fromCity = String.valueOf(raw);
                case "budget" -> state.budget = String.valueOf(raw);
                case "preference" -> state.preference = String.valueOf(raw);
                case "special" -> state.special = String.valueOf(raw);
                default -> {
                    // 白名单外的键不出现（FIELDS 即白名单）
                }
            }
            applied++;
        }
        return applied;
    }

    /** 正整数强转：Number/String 皆收；非法或非正返回 null（调用方跳过该字段） */
    private static Integer positiveInt(Object raw) {
        if (raw instanceof Number n) {
            int v = n.intValue();
            return v > 0 ? v : null;
        }
        try {
            int v = Integer.parseInt(raw.toString().trim());
            return v > 0 ? v : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 缺失必填填确定性默认值：destination=杭州、days=2、startDate=明天、fromCity=同城 */
    private static void fillDefaults(TripRequirementState state, Set<String> defaulted) {
        if (state.destination == null || state.destination.isBlank()) {
            state.destination = DEFAULT_DESTINATION;
            defaulted.add("destination");
        }
        if (state.days == null || state.days <= 0) {
            state.days = DEFAULT_DAYS;
            defaulted.add("days");
        }
        if (state.startDate == null || state.startDate.isBlank()) {
            state.startDate = LocalDate.now().plusDays(DEFAULT_START_DATE_OFFSET_DAYS)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE);
            defaulted.add("startDate");
        }
        if (state.fromCity == null || state.fromCity.isBlank()) {
            state.fromCity = state.destination;
            defaulted.add("fromCity");
        }
    }

    /**
     * 终态收尾：置 DONE（无默认值）或 DEGRADED（有默认值放行）→ 显式 save（Redis
     * 副本语义）→ 落盘 intake_result.json。COLLECTING→DONE/DEGRADED 单向不回退，
     * 且 DONE/DEGRADED 态在入口已被幂等直通拦截，到不了这里。
     */
    private void finalizeCollected(TripRequirementState state, String userId, String sessionId,
                                   Set<String> defaulted, String channel) {
        boolean degraded = !defaulted.isEmpty();
        state.status = degraded
                ? TripRequirementState.Status.DEGRADED
                : TripRequirementState.Status.DONE;
        store.save(userId, sessionId, state);
        taskWorkspace.writeIntakeResult(userId, sessionId, toIntakeResult(sessionId, state, defaulted));
        log.info("需求采集收齐: 用户={}, 会话={}, 渠道={}, 终态={}, 落盘=intake_result.json",
                userId, sessionId, channel, state.status);
    }

    /**
     * 组装落盘 DTO：已填字段 confirmed=true；默认值 confirmed=false + note="默认值"；
     * 未填选填项 value=null。degraded 标记 = 本次放行是否用了默认值。
     */
    private static IntakeResult toIntakeResult(String sessionId, TripRequirementState state,
                                               Set<String> defaulted) {
        IntakeResult.RequirementFields fields = new IntakeResult.RequirementFields(
                fieldValue(state.destination, "destination", defaulted),
                fieldValue(state.days, "days", defaulted),
                fieldValue(state.startDate, "startDate", defaulted),
                fieldValue(state.fromCity, "fromCity", defaulted),
                fieldValue(state.budget, "budget", defaulted),
                fieldValue(state.preference, "preference", defaulted),
                fieldValue(state.people, "people", defaulted),
                fieldValue(state.special, "special", defaulted));
        return new IntakeResult(sessionId,
                IntakeResult.Status.valueOf(state.status.name()),
                state.clarifyCycles, fields, !defaulted.isEmpty(),
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }

    private static IntakeResult.FieldValue fieldValue(Object value, String name,
                                                      Set<String> defaulted) {
        if (value == null || String.valueOf(value).isBlank()) {
            return new IntakeResult.FieldValue(null, false, null);
        }
        String text = String.valueOf(value);
        if (defaulted.contains(name)) {
            return new IntakeResult.FieldValue(text, false, "默认值");
        }
        return new IntakeResult.FieldValue(text, true, null);
    }

    /** 识别 __form__ 结构化提交：JSON 对象且含 __form__ 键（值为对象）；非 JSON/纯文本返回 null */
    private static JSONObject parseFormMessage(String userMessage) {
        if (userMessage == null) {
            return null;
        }
        String trimmed = userMessage.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            JSONObject object = JSON.parseObject(trimmed);
            return object == null ? null : object.getJSONObject("__form__");
        } catch (Exception e) {
            return null;
        }
    }
}
