package com.travelscope.service.workflow;

import com.travelscope.collab.BudgetConstraint;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetConstraintParserTest {

    private final BudgetConstraintParser parser = new BudgetConstraintParser();

    @Test
    void parsesPerPersonTotalUpperBound() {
        var result = parser.parse("人均 2000 以内");
        assertThat(result.requiresClarification()).isFalse();
        assertThat(result.constraint().scope()).isEqualTo(BudgetConstraint.Scope.PER_PERSON_TOTAL);
        assertThat(result.constraint().max()).isEqualByComparingTo("2000");
        assertThat(result.constraint().rawText()).isEqualTo("人均 2000 以内");
    }

    @Test
    void parsesGroupTotalUpperBound() {
        var result = parser.parse("两个人总共 5000");
        assertThat(result.requiresClarification()).isFalse();
        assertThat(result.constraint().scope()).isEqualTo(BudgetConstraint.Scope.GROUP_TOTAL);
        assertThat(result.constraint().max()).isEqualByComparingTo("5000");
    }

    @Test
    void parsesHotelPerNightApproximateAmount() {
        var result = parser.parse("酒店每晚 800 左右");
        assertThat(result.requiresClarification()).isFalse();
        assertThat(result.constraint().scope()).isEqualTo(BudgetConstraint.Scope.HOTEL_PER_NIGHT);
        assertThat(result.constraint().min()).isEqualByComparingTo("720");
        assertThat(result.constraint().max()).isEqualByComparingTo("880");
    }

    @Test
    void amountWithoutScopeRequiresClarification() {
        var result = parser.parse("2000以上");
        assertThat(result.requiresClarification()).isTrue();
        assertThat(result.reasonCode()).isEqualTo("BUDGET_SCOPE_AMBIGUOUS");
        assertThat(result.constraint().scope()).isNull();
        assertThat(result.constraint().min()).isEqualByComparingTo("2000");
    }

    @Test
    void parsesTravelStyleWithoutInventingAmount() {
        var result = parser.parse("高端一点");
        assertThat(result.requiresClarification()).isFalse();
        assertThat(result.constraint().travelStyle()).isEqualTo(BudgetConstraint.TravelStyle.LUXURY);
        assertThat(result.constraint().min()).isNull();
        assertThat(result.constraint().max()).isNull();
    }
}
