package com.travelscope.service.workflow.dto;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 需求抽取结果（S2.1，RequirementExtractor 的产出；S2.2 RequirementWorkflowService 的输入）。
 * <p>
 * fields 为通过置信度过滤后的字段值（键 ∈ {@link #FIELDS}，与
 * TripRequirementState 字段名一致）；值为模型原样的字符串或数字——
 * <b>days/people 的 Integer 转换是 S2.2 merge 的职责</b>（对齐
 * RequirementTools.update_requirement_state 的 getInteger 语义）。
 * </p>
 */
public record RequirementExtraction(Map<String, Object> fields, String rawJson,
                                    com.travelscope.collab.BudgetConstraint budgetConstraint) {

    public RequirementExtraction(Map<String, Object> fields, String rawJson) {
        this(fields, rawJson, parseBudget(fields));
    }

    private static com.travelscope.collab.BudgetConstraint parseBudget(Map<String, Object> fields) {
        Object raw = fields == null ? null : fields.get("budget");
        return new com.travelscope.service.workflow.BudgetConstraintParser()
                .parse(raw == null ? null : String.valueOf(raw)).constraint();
    }

    /** 8 个需求字段（与 TripRequirementState 字段名一一对应） */
    public static final List<String> FIELDS = List.of(
            "destination", "days", "startDate", "fromCity",
            "budget", "preference", "people", "special",
            "pace", "dailyWindow", "walkingTolerance");

    /** 空结果（模型异常/超时/解析失败——状态机以空抽取走表单/反问兜底） */
    public static final RequirementExtraction EMPTY = new RequirementExtraction(Map.of(), "");

    public RequirementExtraction {
        // 保序（FIELDS 顺序）：Map.copyOf 会按哈希序重排
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(fields, "fields")));
        Objects.requireNonNull(rawJson, "rawJson");
    }

    /**
     * 纯函数（单测主战场）：从模型原始输出解析并过滤。
     * <p>
     * 宽容解析链：markdown 围栏剥离 → 整体 JSON → 截取首 { 到末 } 的片段；
     * 字段形状两种皆收——标准形 {@code {"字段":{"value":…,"confidence":0~1}}}
     * （schema 约束的标准输出）与扁平形 {@code {"字段": 值}}（模型无视 schema 直出时
     * 的容忍，视为满置信）；value 为 null/空白、confidence 缺失或低于阈值的字段丢弃。
     * 任何解析失败返回 {@link #EMPTY}，不抛。
     * </p>
     */
    public static RequirementExtraction parse(String rawJson, double minConfidence) {
        if (rawJson == null || rawJson.isBlank()) {
            return EMPTY;
        }
        JSONObject object = parseJson(stripFences(rawJson));
        if (object == null) {
            return EMPTY;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String name : FIELDS) {
            Object raw = object.get(name);
            if (raw instanceof JSONObject field) {
                Object value = field.get("value");
                if (value == null || (value instanceof String s && s.isBlank())) {
                    continue;
                }
                Double confidence = field.getDouble("confidence");
                if (confidence == null || confidence < minConfidence) {
                    continue;
                }
                fields.put(name, value);
            } else if (raw != null && !(raw instanceof String s && s.isBlank())) {
                // 扁平形容忍：视为满置信（schema 路径产出标准形，此处兜模型直出偏差）
                fields.put(name, raw);
            }
        }
        if (fields.isEmpty()) {
            return EMPTY;
        }
        return new RequirementExtraction(fields, rawJson);
    }

    /** 剥离 markdown 代码围栏（```json … ``` / ``` … ```） */
    private static String stripFences(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence);
            }
        }
        return trimmed;
    }

    /** 宽容 JSON 解析：整体解析失败时截取首个 { 到最后一个 } 再试 */
    private static JSONObject parseJson(String text) {
        try {
            return JSON.parseObject(text);
        } catch (Exception ignored) {
            // 落片段截取
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                return JSON.parseObject(text.substring(start, end + 1));
            } catch (Exception ignored) {
                // 彻底无法解析
            }
        }
        return null;
    }
}
