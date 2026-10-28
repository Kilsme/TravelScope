package com.travelscope.service.hotel;

import com.travelscope.collab.BudgetConstraint;
import com.travelscope.collab.HotelSelection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HotelOptionSelectorTest {
    private final HotelOptionSelector selector = new HotelOptionSelector();

    @Test
    void luxuryBudgetDoesNotDefaultToEconomyHotel() {
        var economy = hotel("经济酒店", 260, 120.10, 30.20, 2, true, true);
        var luxury = hotel("湖景豪华酒店", 900, 120.11, 30.21, 5, true, true);
        var budget = new BudgetConstraint(BudgetConstraint.Scope.HOTEL_PER_NIGHT,
                BigDecimal.valueOf(700), BigDecimal.valueOf(1200), "CNY",
                BudgetConstraint.TravelStyle.LUXURY, "酒店每晚 700-1200");

        HotelSelection result = selector.select(List.of(economy, luxury), budget,
                120.11, 30.21, 120.12, 30.22, 3);

        assertThat(result.selected().name()).isEqualTo("湖景豪华酒店");
        assertThat(result.selected().nightlyPrice()).isEqualByComparingTo("900");
    }

    @Test
    void continuousAvailabilityIsHardConstraint() {
        var unavailable = hotel("中心但缺房", 600, 120.10, 30.20, 4, false, true);
        var available = hotel("连续有房", 650, 120.13, 30.23, 4, true, true);
        var budget = budget(800);

        HotelSelection result = selector.select(List.of(unavailable, available), budget,
                120.10, 30.20, 120.15, 30.25, 3);

        assertThat(result.selected().name()).isEqualTo("连续有房");
    }

    @Test
    void routeCenterBeatsSlightlyCheaperRemoteHotel() {
        var remote = hotel("远郊酒店", 450, 120.50, 30.50, 4, true, true);
        var central = hotel("中心酒店", 520, 120.10, 30.20, 4, true, true);

        HotelSelection result = selector.select(List.of(remote, central), budget(700),
                120.10, 30.20, 120.15, 30.25, 2);

        assertThat(result.selected().name()).isEqualTo("中心酒店");
        assertThat(result.explanation()).anyMatch(s -> s.contains("路线中心"));
    }

    private static BudgetConstraint budget(int max) {
        return new BudgetConstraint(BudgetConstraint.Scope.HOTEL_PER_NIGHT, null,
                BigDecimal.valueOf(max), "CNY", BudgetConstraint.TravelStyle.COMFORT, "酒店每晚 " + max);
    }

    private static HotelSelection.HotelOption hotel(String name, int price, double lng, double lat,
                                                      int star, boolean available, boolean refundable) {
        return new HotelSelection.HotelOption(name, BigDecimal.valueOf(price), lng, lat, star,
                available, LocalDate.parse("2026-10-25"), LocalDate.parse("2026-10-28"),
                500, true, refundable, "飞猪", null);
    }
}
