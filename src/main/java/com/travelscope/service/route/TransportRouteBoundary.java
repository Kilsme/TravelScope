package com.travelscope.service.route;

import com.travelscope.collab.RoutePlan;
import com.travelscope.collab.TransportSelection;
import com.travelscope.service.route.dto.RouteConstraints;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 将主选往返交通转换为首末日固定节点和不可压缩的可用时间边界。 */
public record TransportRouteBoundary(RouteConstraints constraints,
                                     RoutePlan.RouteNode outboundNode,
                                     RoutePlan.RouteNode inboundNode) {
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    public static TransportRouteBoundary from(TransportSelection selection, RouteConstraints base,
                                              int exitBufferMin, int arrivalTransferMin,
                                              int luggageMin, int stationTransferMin,
                                              int securityBufferMin) {
        var outbound = selection.outbound().selected();
        var inbound = selection.inbound().selected();
        LocalDateTime firstAvailable = outbound.arrivalTime()
                .plusMinutes(exitBufferMin + arrivalTransferMin);
        LocalDateTime lastPoiEnd = inbound.departureTime()
                .minusMinutes(luggageMin + stationTransferMin + securityBufferMin);
        RouteConstraints constrained = base.withTransportWindows(
                firstAvailable.format(HH_MM), lastPoiEnd.format(HH_MM));
        return new TransportRouteBoundary(constrained, node(outbound), node(inbound));
    }

    public List<RoutePlan.RouteNode> nodesForDay(int day, int totalDays,
                                                 List<RoutePlan.RouteNode> poiNodes) {
        java.util.ArrayList<RoutePlan.RouteNode> nodes = new java.util.ArrayList<>();
        if (day == 1) nodes.add(outboundNode);
        nodes.addAll(poiNodes);
        if (day == totalDays) nodes.add(inboundNode);
        return List.copyOf(nodes);
    }

    private static RoutePlan.RouteNode node(TransportSelection.TransportOption option) {
        String name = option.serviceNo() + " " + option.mode();
        return new RoutePlan.RouteNode(name, RoutePlan.NodeType.INTERCITY_TRANSPORT, true,
                null, null, option.departureTime().format(HH_MM), option.arrivalTime().format(HH_MM),
                null, null, "固定交通，不得压缩缓冲");
    }
}
