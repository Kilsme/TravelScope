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
 * 火车票查询工具（飞猪 flyai search-train，2026-10-17 飞猪比赛接入）
 * <p>
 * 飞猪火车票统一入口：直达/中转车次、时刻、座席、价格、预订链接。
 * 与 12306 MCP（mcp__c12306__*）双源并存——本工具返回标注【飞猪】来源，
 * 供前端展示数据来源；12306 MCP 保留作对照与降级。
 * </p>
 */
@Component
public class TrainTicketTool {

    private static final Logger log = LoggerFactory.getLogger(TrainTicketTool.class);

    /** 数据来源标签 */
    public static final String SOURCE_FLIGGY = "飞猪";

    private final FlyaiCliTool flyaiCliTool;

    public TrainTicketTool(FlyaiCliTool flyaiCliTool) {
        this.flyaiCliTool = flyaiCliTool;
    }

    /**
     * 查询城市间火车票（飞猪数据）
     *
     * @param origin      出发城市或车站（如 "上海"、"上海虹桥"）
     * @param destination 到达城市或车站（如 "杭州"）
     * @param depDate     出发日期（YYYY-MM-DD，可空=当天）
     * @return 车次列表 JSON（trains[] 每条含 source=飞猪）
     */
    @Tool(description = "查询城市间火车票（飞猪数据源），包括车次、时刻、座席、价格与预订链接。"
            + "用于跨城出行规划；结果标注来源为飞猪。")
    public String queryTrainTickets(
            @ToolParam(name = "origin", description = "出发城市或车站，如 '上海'、'上海虹桥'")
            String origin,
            @ToolParam(name = "destination", description = "到达城市或车站，如 '杭州'")
            String destination,
            @ToolParam(name = "depDate", description = "出发日期 YYYY-MM-DD，如 '2026-10-25'，可空")
            String depDate
    ) {
        log.info("查询火车票（飞猪）: {}→{} depDate={}", origin, destination, depDate);
        JSONObject response = flyaiCliTool.searchTrain(origin, destination, depDate);
        if (!FlyaiCliTool.hasItems(response)) {
            return errorResult("飞猪火车票查询无结果: " + response.getString("error"));
        }

        List<JSONObject> trains = new ArrayList<>();
        for (Object item : FlyaiCliTool.itemListOf(response)) {
            JSONObject raw = (JSONObject) item;
            JSONObject train = new JSONObject();
            train.put("price", raw.getString("price"));
            train.put("jumpUrl", raw.getString("jumpUrl"));
            train.put("totalDuration", raw.getString("totalDuration"));
            // journeys[0].segments：直达单段、中转多段
            JSONArray journeys = raw.getJSONArray("journeys");
            if (journeys != null && !journeys.isEmpty()) {
                JSONObject journey = journeys.getJSONObject(0);
                train.put("journeyType", journey.getString("journeyType"));
                JSONArray segments = journey.getJSONArray("segments");
                if (segments != null && !segments.isEmpty()) {
                    List<JSONObject> legs = new ArrayList<>();
                    for (int i = 0; i < segments.size(); i++) {
                        JSONObject seg = segments.getJSONObject(i);
                        JSONObject leg = new JSONObject();
                        leg.put("trainNo", seg.getString("marketingTransportNo"));
                        leg.put("transportName", seg.getString("marketingTransportName"));
                        leg.put("depStation", seg.getString("depStationName"));
                        leg.put("arrStation", seg.getString("arrStationName"));
                        leg.put("depTime", seg.getString("depDateTime"));
                        leg.put("arrTime", seg.getString("arrDateTime"));
                        leg.put("duration", seg.getString("duration"));
                        leg.put("seatClass", seg.getString("seatClassName"));
                        legs.add(leg);
                    }
                    train.put("segments", legs);
                }
            }
            train.put("source", SOURCE_FLIGGY);
            trains.add(train);
        }

        JSONObject result = new JSONObject();
        result.put("count", trains.size());
        result.put("trains", trains);
        result.put("source", SOURCE_FLIGGY);
        return JSON.toJSONString(result);
    }

    private static String errorResult(String msg) {
        JSONObject error = new JSONObject();
        error.put("error", msg);
        return JSON.toJSONString(error);
    }
}
