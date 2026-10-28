package com.travelscope.service.hotel;

import com.travelscope.collab.BudgetConstraint;
import com.travelscope.collab.HotelSelection;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.service.route.dto.RouteConstraints;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/** 酒店选择单一入口：同一 selected 记录同时供路线锚和预算引擎消费。 */
@Service
public class HotelSelectionService {
    private final TaskWorkspace workspace;
    private final HotelOptionSelector selector = new HotelOptionSelector();

    public HotelSelectionService(TaskWorkspace workspace) {
        this.workspace = workspace;
    }

    public HotelSelection selectAndWrite(String userId, String sessionId,
                                         List<HotelSelection.HotelOption> options,
                                         BudgetConstraint budget, double routeCenterLng,
                                         double routeCenterLat, double stationLng,
                                         double stationLat, int nights) {
        HotelSelection selection = selector.select(options, budget, routeCenterLng, routeCenterLat,
                stationLng, stationLat, nights);
        workspace.writeHotelSelection(userId, sessionId, selection);
        return selection;
    }

    public RouteConstraints applyToRoute(RouteConstraints base, HotelSelection selection) {
        var selected = selection.selected();
        return base.withHotel(selected.name(), selected.lng(), selected.lat());
    }

    public HotelBudget hotelBudget(HotelSelection selection) {
        var selected = selection.selected();
        return new HotelBudget(selected.name(), selected.nightlyPrice(), selected.nights(),
                selected.totalPrice(), selected.source());
    }

    public record HotelBudget(String hotelName, BigDecimal nightlyPrice, long nights,
                              BigDecimal totalPrice, String source) {}
}
