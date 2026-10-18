package com.travelscope.service.workflow;

import com.travelscope.dto.TripRequirementState;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 需求清单规格生成器（清单卡片方案，v4 清单 S2.2 落地后端部分）：由需求状态机
 * 产出前端清单卡片所需的完整渲染规格——8 字段 × 控件类型/已填值/是否必填/建议选项。
 * <p>
 * 控件映射：days/people=number、startDate=date、budget=select（四档）、
 * preference=multi-select（选项与检索词典 city-aliases.json 的偏好类目对齐）、
 * destination/fromCity=text、special=text（可空）。文案平移自 v3 反问文案表。
 * 纯函数组件：无 Spring、无外部状态。
 * </p>
 */
public class RequirementFormSpec {

    /**
     * 单字段渲染规格。
     *
     * @param name        字段名（与 TripRequirementState 字段名一致）
     * @param label       展示文案（如「目的地」）
     * @param type        控件类型：text / number / date / select / multi-select
     * @param value       已填值（字符串形态；未填为 null）
     * @param filled      是否已填
     * @param required    是否必填（前端标星 + 缺失高亮）
     * @param options     建议选项（select/multi-select 用；其余为空表）
     * @param placeholder 空值占位提示
     */
    public record FieldSpec(String name, String label, String type, String value,
                            boolean filled, boolean required, List<String> options,
                            String placeholder) {

        public FieldSpec {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(type, "type");
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /** 展示顺序：必填四项在前，选填四项在后（与 RequirementExtraction.FIELDS 一致） */
    private static final List<String> FIELD_ORDER = List.of(
            "destination", "days", "startDate", "fromCity",
            "budget", "preference", "people", "special");

    private static final List<String> BUDGET_OPTIONS = List.of(
            "人均500内", "500-1000", "1000-2000", "2000以上");

    /** 偏好建议选项（与检索词典的偏好类目对齐，便于检索变体命中） */
    private static final List<String> PREFERENCE_OPTIONS = List.of(
            "自然风景", "人文古迹", "美食", "亲子", "购物", "夜景", "海滨", "徒步");

    /** 字段定义（label/控件/占位文案/选项） */
    private record FieldDef(String label, String type, String placeholder, List<String> options) {
    }

    private static final Map<String, FieldDef> DEFS = Map.of(
            "destination", new FieldDef("目的地", "text", "您想去哪个城市？", List.of()),
            "days", new FieldDef("游玩天数", "number", "几天？", List.of()),
            "startDate", new FieldDef("出发日期", "date", "具体日期，或说「下周」「月底」", List.of()),
            "fromCity", new FieldDef("出发城市", "text", "从哪里出发？", List.of()),
            "budget", new FieldDef("预算范围", "select", "人均预算档位", BUDGET_OPTIONS),
            "preference", new FieldDef("偏好", "multi-select", "可多选，也可补充描述", PREFERENCE_OPTIONS),
            "people", new FieldDef("人数", "number", "几人同行？", List.of()),
            "special", new FieldDef("特殊需求", "text", "可不填，如不吃辣、带老人小孩", List.of()));

    /**
     * 生成清单规格。
     *
     * @param state 当前需求状态（Redis 副本——本方法只读不写）
     * @return 8 字段渲染规格（展示顺序固定：必填在前）
     */
    public static List<FieldSpec> generate(TripRequirementState state) {
        Objects.requireNonNull(state, "state");
        List<FieldSpec> specs = FIELD_ORDER.stream().map(name -> {
            FieldDef def = DEFS.get(name);
            String value = readValue(state, name);
            boolean filled = value != null && !value.isBlank();
            return new FieldSpec(name, def.label(), def.type(), value, filled,
                    TripRequirementState.REQUIRED_FIELDS.contains(name),
                    def.options(), def.placeholder());
        }).toList();
        return specs;
    }

    /** 按字段名从状态机读字符串形态值（days/people 为 Integer 转字符串） */
    private static String readValue(TripRequirementState state, String name) {
        return switch (name) {
            case "destination" -> state.destination;
            case "days" -> state.days == null ? null : String.valueOf(state.days);
            case "startDate" -> state.startDate;
            case "fromCity" -> state.fromCity;
            case "budget" -> state.budget;
            case "preference" -> state.preference;
            case "people" -> state.people == null ? null : String.valueOf(state.people);
            case "special" -> state.special;
            default -> null;
        };
    }
}
