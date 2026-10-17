package com.travelscope.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.travelscope.tool.flyai.FlyaiCliTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 机票查询工具（飞猪 flyai search-flight，2026-10-17 飞猪比赛接入）
 * <p>
 * 与飞常准 MCP（mcp__variflight__*）双源并存——本工具返回标注【飞猪】来源，
 * 供前端展示数据来源；飞常准 MCP 保留作对照与降级。
 * </p>
 */
@Component
public class FlightTicketTool {

    private static final Logger log = LoggerFactory.getLogger(FlightTicketTool.class);

    /** 数据来源标签 */
    public static final String SOURCE_FLIGGY = "飞猪";

    private final FlyaiCliTool flyaiCliTool;

    public FlightTicketTool(FlyaiCliTool flyaiCliTool) {
        this.flyaiCliTool = flyaiCliTool;
    }

    /**
     * 查询城市间机票（飞猪数据）
     *
     * @param origin      出发城市或机场（如 "上海"、"上海浦东"）
     * @param destination 到达城市或机场（如 "北京"）
     * @param depDate     出发日期（YYYY-MM-DD，可空=当天）
     * @return 航班列表 JSON（flights[] 每条含 source=飞猪）
     */
    @Tool(description = "查询城市间机票（飞猪数据源），包括航班号、时刻、票价与预订链接。"
            + "用于跨城出行规划；结果标注来源为飞猪。")
    public String queryFlightTickets(
            @ToolParam(name = "origin", description = "出发城市或机场，如 '上海'、'上海浦东'")
            String origin,
            @ToolParam(name = "destination", description = "到达城市或机场，如 '北京'")
            String destination,
            @ToolParam(name = "depDate", description = "出发日期 YYYY-MM-DD，如 '2026-10-25'，可空")
            String depDate
    ) {
        log.info("查询机票（飞猪）: {}→{} depDate={}", origin, destination, depDate);
        JSONObject response = flyaiCliTool.searchFlight(origin, destination, depDate);
        if (!FlyaiCliTool.hasItems(response)) {
            return errorResult("飞猪机票查询无结果: " + response.getString("error"));
        }

        List<JSONObject> flights = new ArrayList<>();
        for (Object item : FlyaiCliTool.itemListOf(response)) {
            JSONObject raw = (JSONObject) item;
            JSONObject flight = new JSONObject();
            flight.put("price", raw.getString("ticketPrice"));
            flight.put("jumpUrl", raw.getString("jumpUrl"));
            flight.put("totalDuration", raw.getString("totalDuration"));
            JSONArray journeys = raw.getJSONArray("journeys");
            if (journeys != null && !journeys.isEmpty()) {
                JSONObject journey = journeys.getJSONObject(0);
                flight.put("journeyType", journey.getString("journeyType"));
                JSONArray segments = journey.getJSONArray("segments");
                if (segments != null && !segments.isEmpty()) {
                    List<JSONObject> legs = new ArrayList<>();
                    for (int i = 0; i < segments.size(); i++) {
                        JSONObject seg = segments.getJSONObject(i);
                        JSONObject leg = new JSONObject();
                        leg.put("flightNo", seg.getString("marketingTransportNo"));
                        leg.put("airline", seg.getString("marketingTransportName"));
                        leg.put("depAirport", seg.getString("depStationName"));
                        leg.put("arrAirport", seg.getString("arrStationName"));
                        leg.put("depTime", seg.getString("depDateTime"));
                        leg.put("arrTime", seg.getString("arrDateTime"));
                        leg.put("duration", seg.getString("duration"));
                        leg.put("cabinClass", seg.getString("seatClassName"));
                        legs.add(leg);
                    }
                    flight.put("segments", legs);
                }
            }
            flight.put("source", SOURCE_FLIGGY);
            flights.add(flight);
        }

        JSONObject result = new JSONObject();
        result.put("count", flights.size());
        result.put("flights", flights);
        result.put("source", SOURCE_FLIGGY);
        return JSON.toJSONString(result);
    }

    private static String errorResult(String msg) {
        JSONObject error = new JSONObject();
        error.put("error", msg);
        return JSON.toJSONString(error);
    }
}
