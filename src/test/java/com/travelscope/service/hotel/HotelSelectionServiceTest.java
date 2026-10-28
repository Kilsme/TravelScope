package com.travelscope.service.hotel;

import com.travelscope.collab.BudgetConstraint;
import com.travelscope.collab.CollabFileReader;
import com.travelscope.collab.CollabFileWriter;
import com.travelscope.collab.HotelSelection;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.service.route.dto.RouteConstraints;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HotelSelectionServiceTest {
    @TempDir Path temp;

    @Test
    void selectedRecordFeedsBothRouteAnchorAndBudget() {
        var workspace = new TaskWorkspace(new CollabFileWriter(temp.toString()), new CollabFileReader());
        var service = new HotelSelectionService(workspace);
        var hotel = new HotelSelection.HotelOption("主酒店", BigDecimal.valueOf(600), 120.1, 30.2,
                4, true, LocalDate.parse("2026-10-25"), LocalDate.parse("2026-10-28"),
                800, true, true, "飞猪", null);
        var budget = new BudgetConstraint(BudgetConstraint.Scope.HOTEL_PER_NIGHT, null,
                BigDecimal.valueOf(800), "CNY", BudgetConstraint.TravelStyle.COMFORT, "酒店每晚800");

        HotelSelection selection = service.selectAndWrite("u1", "conv-d4", List.of(hotel), budget,
                120.1, 30.2, 120.12, 30.22, 3);
        RouteConstraints route = service.applyToRoute(RouteConstraints.ofDays(3), selection);
        var cost = service.hotelBudget(selection);

        assertThat(route.hotelName()).isEqualTo(selection.selected().name());
        assertThat(cost.hotelName()).isEqualTo(selection.selected().name());
        assertThat(cost.totalPrice()).isEqualByComparingTo("1800");
        assertThat(workspace.readHotelSelection("u1", "conv-d4")).contains(selection);
    }
}
