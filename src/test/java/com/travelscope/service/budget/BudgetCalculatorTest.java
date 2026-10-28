package com.travelscope.service.budget;

import com.travelscope.price.PriceFact;
import com.travelscope.price.PriceUnit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetCalculatorTest {
    private static final Instant NOW = Instant.parse("2026-10-28T04:00:00Z");
    private final BudgetCalculator calculator = new BudgetCalculator();

    @Test
    void appliesPeopleRoomsNightsAndBothIntercityDirections() {
        var input = new BudgetCalculator.Input(3, 2, 2,
                List.of(item("灵隐寺", BudgetCalculator.Category.TICKET, fact("30", PriceUnit.PER_TICKET)),
                        item("酒店", BudgetCalculator.Category.HOTEL, fact("500", PriceUnit.PER_ROOM_NIGHT)),
                        item("去程", BudgetCalculator.Category.INTERCITY_TRANSPORT, fact("100", PriceUnit.PER_PERSON_TRIP)),
                        item("返程", BudgetCalculator.Category.INTERCITY_TRANSPORT, fact("120", PriceUnit.PER_PERSON_TRIP))),
                List.of());

        var result = calculator.calculate(input);

        assertThat(amount(result, BudgetCalculator.Category.TICKET)).isEqualByComparingTo("90.00");
        assertThat(amount(result, BudgetCalculator.Category.HOTEL)).isEqualByComparingTo("2000.00");
        assertThat(amount(result, BudgetCalculator.Category.INTERCITY_TRANSPORT)).isEqualByComparingTo("660.00");
        assertThat(result.total().amount()).isEqualByComparingTo("2750.00");
    }

    @Test
    void conv102LingyinUsesOneStructuredPriceEverywhere() {
        var lingyin = fact("45.00", PriceUnit.PER_TICKET);
        var result = calculator.calculate(new BudgetCalculator.Input(1, 1, 2,
                List.of(item("灵隐寺", BudgetCalculator.Category.TICKET, lingyin)), List.of()));
        var legacy = calculator.toLegacyBudget(result);

        assertThat(result.items().getFirst().amount().amount()).isEqualByComparingTo("45.00");
        assertThat(legacy.breakdown().getFirst().amount()).isEqualTo("45.00");
        assertThat(legacy.totalEstimate()).isEqualTo("45.00");
    }

    @Test
    void freeItemsRemainExplicitAndMissingPricesBecomePending() {
        var input = new BudgetCalculator.Input(2, 1, 1,
                List.of(item("西湖", BudgetCalculator.Category.TICKET, fact("0", PriceUnit.PER_TICKET)),
                        new BudgetCalculator.PricedItem("午餐", BudgetCalculator.Category.MEAL, null, 1)),
                List.of());

        var result = calculator.calculate(input);

        assertThat(result.items()).anyMatch(i -> i.name().equals("西湖") && i.amount().amount().signum() == 0);
        assertThat(result.pending()).containsExactly("午餐：缺失价格");
        assertThat(result.total().amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void localTransfersMealsAndRoundingAreDeterministicAndTotalEqualsBreakdown() {
        var input = new BudgetCalculator.Input(3, 1, 1,
                List.of(item("地铁", BudgetCalculator.Category.LOCAL_TRANSPORT, fact("2.345", PriceUnit.PER_PERSON)),
                        item("晚餐", BudgetCalculator.Category.MEAL, fact("33.335", PriceUnit.PER_PERSON))),
                List.of());

        var result = calculator.calculate(input);
        BigDecimal sum = result.breakdown().stream().map(b -> b.amount().amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(result.total().amount()).isEqualByComparingTo("107.05");
        assertThat(result.total().amount()).isEqualByComparingTo(sum);
    }

    private static BudgetCalculator.PricedItem item(String name, BudgetCalculator.Category category,
                                                     PriceFact fact) {
        return new BudgetCalculator.PricedItem(name, category, fact, 1);
    }

    private static PriceFact fact(String amount, PriceUnit unit) {
        return new PriceFact(new BigDecimal(amount), "CNY", unit, "fixture", NOW, false);
    }

    private static BigDecimal amount(BudgetCalculator.Result result, BudgetCalculator.Category category) {
        return result.breakdown().stream().filter(b -> b.category() == category).findFirst().orElseThrow().amount().amount();
    }
}
