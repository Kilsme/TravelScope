package com.travelscope.service.workflow;

import com.travelscope.dto.TripRequirementState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2.3 验证：RequirementFormSpec 需求清单规格生成。
 * 覆盖：8 字段规格完整（name/label/控件映射、必填在前展示顺序）/ 已填值透传 /
 * 缺失项带建议选项与必填标记。
 */
class RequirementFormSpecTest {

    /** 全空状态：8 行全部缺失 */
    private static TripRequirementState empty() {
        return new TripRequirementState();
    }

    // ---------- 用例 1：8 字段规格完整（含控件映射与展示顺序） ----------
    @Test
    void eightFieldSpecsComplete() {
        List<RequirementFormSpec.FieldSpec> specs = RequirementFormSpec.generate(empty());

        assertThat(specs).hasSize(8);
        assertThat(specs).extracting(RequirementFormSpec.FieldSpec::name).containsExactly(
                "destination", "days", "startDate", "fromCity",
                "budget", "preference", "people", "special");

        // 控件映射
        assertThat(typeOf(specs, "destination")).isEqualTo("text");
        assertThat(typeOf(specs, "days")).isEqualTo("number");
        assertThat(typeOf(specs, "startDate")).isEqualTo("date");
        assertThat(typeOf(specs, "fromCity")).isEqualTo("text");
        assertThat(typeOf(specs, "budget")).isEqualTo("select");
        assertThat(typeOf(specs, "preference")).isEqualTo("multi-select");
        assertThat(typeOf(specs, "people")).isEqualTo("number");
        assertThat(typeOf(specs, "special")).isEqualTo("text");

        // 文案与占位
        assertThat(specs).allSatisfy(s -> {
            assertThat(s.label()).isNotBlank();
            assertThat(s.placeholder()).isNotBlank();
        });

        // 必填标记：前四项 true，其余 false
        assertThat(specs.subList(0, 4)).allSatisfy(s -> assertThat(s.required()).isTrue());
        assertThat(specs.subList(4, 8)).allSatisfy(s -> assertThat(s.required()).isFalse());
    }

    // ---------- 用例 2：已填值透传（含数值字段转字符串） ----------
    @Test
    void filledValuesPassThrough() {
        TripRequirementState state = new TripRequirementState();
        state.destination = "杭州";
        state.days = 3;
        state.people = 2;
        state.preference = "自然风景";

        List<RequirementFormSpec.FieldSpec> specs = RequirementFormSpec.generate(state);

        assertThat(fieldOf(specs, "destination").value()).isEqualTo("杭州");
        assertThat(fieldOf(specs, "destination").filled()).isTrue();
        assertThat(fieldOf(specs, "days").value()).as("Integer 转字符串").isEqualTo("3");
        assertThat(fieldOf(specs, "days").filled()).isTrue();
        assertThat(fieldOf(specs, "people").value()).isEqualTo("2");
        assertThat(fieldOf(specs, "preference").value()).isEqualTo("自然风景");
        // 未填项
        assertThat(fieldOf(specs, "startDate").value()).isNull();
        assertThat(fieldOf(specs, "startDate").filled()).isFalse();
        assertThat(fieldOf(specs, "budget").filled()).isFalse();
    }

    // ---------- 用例 3：缺失项带建议选项 ----------
    @Test
    void missingFieldsCarryOptions() {
        List<RequirementFormSpec.FieldSpec> specs = RequirementFormSpec.generate(empty());

        assertThat(fieldOf(specs, "budget").options())
                .containsExactly("人均500内", "500-1000", "1000-2000", "2000以上");
        assertThat(fieldOf(specs, "preference").options())
                .containsExactly("自然风景", "人文古迹", "美食", "亲子", "购物", "夜景", "海滨", "徒步");
        // 文本/数值/日期类无选项
        assertThat(fieldOf(specs, "destination").options()).isEmpty();
        assertThat(fieldOf(specs, "days").options()).isEmpty();
        assertThat(fieldOf(specs, "startDate").options()).isEmpty();
    }

    private static String typeOf(List<RequirementFormSpec.FieldSpec> specs, String name) {
        return fieldOf(specs, name).type();
    }

    private static RequirementFormSpec.FieldSpec fieldOf(List<RequirementFormSpec.FieldSpec> specs,
                                                         String name) {
        return specs.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }
}
