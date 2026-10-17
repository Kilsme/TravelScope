package com.travelscope.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.travelscope.tool.flyai.FlyaiCliTool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F4 验证：TrainTicketTool / FlightTicketTool 的飞猪响应格式化与来源标注。
 */
class TicketToolsTest {

    private static final String TRAIN_RESPONSE = """
            {"data":{"itemList":[{"price":"57.00","jumpUrl":"https://router.feizhu.com/x",
              "totalDuration":"54","journeys":[{"journeyType":"直达","segments":[{
                "marketingTransportNo":"G7331","marketingTransportName":"高铁",
                "depStationName":"上海虹桥站","arrStationName":"杭州东站",
                "depDateTime":"2026-10-25 06:13:00","arrDateTime":"2026-10-25 07:07:00",
                "duration":"54","seatClassName":"二等座"}]}]}]},
             "status":0,"systemMessage":null}
            """;

    private static final String FLIGHT_RESPONSE = """
            {"data":{"itemList":[{"ticketPrice":"500.00","jumpUrl":"https://router.feizhu.com/y",
              "totalDuration":"135","journeys":[{"journeyType":"直达","segments":[{
                "marketingTransportNo":"CA8322","marketingTransportName":"国航",
                "depStationName":"浦东国际机场","arrStationName":"大兴国际机场",
                "depDateTime":"2026-10-25 06:45:00","arrDateTime":"2026-10-25 09:00:00",
                "duration":"135","seatClassName":"经济舱"}]}]}]},
             "status":0,"systemMessage":null}
            """;

    @Test
    void trainTicketFormatsSegmentsWithSource() {
        FlyaiCliTool flyai = mock(FlyaiCliTool.class);
        when(flyai.searchTrain("上海", "杭州", "2026-10-25"))
                .thenReturn(JSON.parseObject(TRAIN_RESPONSE));

        String result = new TrainTicketTool(flyai)
                .queryTrainTickets("上海", "杭州", "2026-10-25");

        JSONObject json = JSON.parseObject(result);
        assertThat(json.getIntValue("count")).isEqualTo(1);
        assertThat(json.getString("source")).isEqualTo("飞猪");
        JSONObject train = json.getJSONArray("trains").getJSONObject(0);
        assertThat(train.getString("price")).isEqualTo("57.00");
        assertThat(train.getString("source")).isEqualTo("飞猪");
        JSONObject seg = train.getJSONArray("segments").getJSONObject(0);
        assertThat(seg.getString("trainNo")).isEqualTo("G7331");
        assertThat(seg.getString("depStation")).isEqualTo("上海虹桥站");
        assertThat(seg.getString("seatClass")).isEqualTo("二等座");
    }

    @Test
    void flightTicketFormatsSegmentsWithSource() {
        FlyaiCliTool flyai = mock(FlyaiCliTool.class);
        when(flyai.searchFlight("上海", "北京", "2026-10-25"))
                .thenReturn(JSON.parseObject(FLIGHT_RESPONSE));

        String result = new FlightTicketTool(flyai)
                .queryFlightTickets("上海", "北京", "2026-10-25");

        JSONObject json = JSON.parseObject(result);
        JSONObject flight = json.getJSONArray("flights").getJSONObject(0);
        assertThat(flight.getString("price")).isEqualTo("500.00");
        JSONObject seg = flight.getJSONArray("segments").getJSONObject(0);
        assertThat(seg.getString("flightNo")).isEqualTo("CA8322");
        assertThat(seg.getString("airline")).isEqualTo("国航");
        assertThat(flight.getString("source")).isEqualTo("飞猪");
    }

    @Test
    void emptyResultReturnsError() {
        FlyaiCliTool flyai = mock(FlyaiCliTool.class);
        when(flyai.searchTrain("上海", "杭州", null))
                .thenReturn(FlyaiCliTool.errorResult("飞猪查询超时（30s）"));

        String result = new TrainTicketTool(flyai).queryTrainTickets("上海", "杭州", null);

        JSONObject json = JSON.parseObject(result);
        assertThat(json.containsKey("error")).isTrue();
    }
}
