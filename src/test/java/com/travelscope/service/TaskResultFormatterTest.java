package com.travelscope.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TaskResultFormatter 单测（2026-10-20 raw JSON 上屏修复）。
 * 样例数据取自生产实证（杭州→哈尔滨往返飞猪查询 + 哈尔滨酒店双源 + 和风三日预报）。
 */
class TaskResultFormatterTest {

    /** 去程缓存段（飞猪 queryTrainTickets 返回原文，精简到 2 条） */
    private static final String OUTBOUND_JSON = """
            {"count":2,"trains":[{"price":"910.00","jumpUrl":"https://router.feizhu.com/multi/webview?url=abc",\
            "totalDuration":"1057","journeyType":"中转","segments":[\
            {"trainNo":"G1224","transportName":"高铁","depStation":"杭州东站","arrStation":"沈阳北站",\
            "depTime":"2026-10-22 08:29:00","arrTime":"2026-10-22 18:46:00","duration":"617","seatClass":"二等座"},\
            {"trainNo":"T297","transportName":"普快","depStation":"沈阳北站","arrStation":"哈尔滨站",\
            "depTime":"2026-10-22 20:54:00","arrTime":"2026-10-23 02:06:00","duration":"312","seatClass":"硬座"}],\
            "source":"飞猪"},\
            {"price":"278.50","jumpUrl":"https://router.feizhu.com/multi/webview?url=def",\
            "totalDuration":"2152","journeyType":"直达","segments":[\
            {"trainNo":"K552","transportName":"普快","depStation":"杭州站","arrStation":"哈尔滨站",\
            "depTime":"2026-10-22 20:12:00","arrTime":"2026-10-24 08:04:00","duration":"2152","seatClass":"硬座"}],\
            "source":"飞猪"}],"source":"飞猪"}""";

    private static final String RETURN_JSON = """
            {"count":1,"trains":[{"price":"285.50","jumpUrl":"https://router.feizhu.com/multi/webview?url=ghi",\
            "totalDuration":"1880","journeyType":"直达","segments":[\
            {"trainNo":"Z178","transportName":"普快","depStation":"哈尔滨西站","arrStation":"杭州站",\
            "depTime":"2026-10-24 22:30:00","arrTime":"2026-10-26 05:50:00","duration":"1880","seatClass":"硬座"}],\
            "source":"飞猪"}],"source":"飞猪"}""";

    private static final String HOTEL_JSON = """
            {"count":10,"hotels":[\
            {"name":"哈尔滨暖橙公寓（中央大街店）","address":"道里区东七道街20号99公馆7楼713室",\
            "type":"经济型","price":"¥35","detailUrl":"https://router.feizhu.com/ws/2m65pE","source":"飞猪"},\
            {"name":"如家旗下-哈尔滨中央大街西五道街UP华驿智旅酒店","address":"黑龙江省哈尔滨市道里区西五道街37-6号",\
            "type":"经济型","price":"¥93","detailUrl":"https://router.feizhu.com/ws/0ElxqU","source":"飞猪"},\
            {"name":"锦江之星品尚哈尔滨中央大街步行街机场巴士站酒店","address":"中央大街32号-46号",\
            "type":"经济型","price":"¥98","detailUrl":"https://router.feizhu.com/ws/0jyOuJ","source":"飞猪"},\
            {"name":"格林豪泰哈尔滨中央大街商务酒店","address":"大安街54号",\
            "type":"经济型","price":"¥81","detailUrl":"https://router.feizhu.com/ws/3bFZXE","source":"飞猪"},\
            {"name":"速8优选酒店哈尔滨中央大街店","address":"经纬四道街5号",\
            "type":"舒适型","price":"¥75","detailUrl":"https://router.feizhu.com/ws/16ztqh","source":"飞猪"},\
            {"name":"百联公寓","address":"上海街90-3号爱建公寓C座1层111","tel":"13704813828",\
            "type":"住宿服务;住宿服务相关;住宿服务相关|商务住宅;住宅区;住宅小区","source":"高德"}],\
            "sources":{"高德":10,"飞猪":9}}""";

    private static final String WEATHER_JSON = """
            {"city":"哈尔滨","days":3,"forecasts":[\
            {"date":"2026-10-20","text_day":"多云","text_night":"多云","temp_max":"9","temp_min":"-3",\
            "wind_dir_day":"西风","wind_scale_day":"1-3","humidity":"51","uv_index":"1"},\
            {"date":"2026-10-21","text_day":"多云","text_night":"晴","temp_max":"5","temp_min":"-2",\
            "wind_dir_day":"西北风","wind_scale_day":"1-3","humidity":"52","uv_index":"3"},\
            {"date":"2026-10-22","text_day":"晴","text_night":"晴","temp_max":"10","temp_min":"1",\
            "wind_dir_day":"西南风","wind_scale_day":"1-3","humidity":"56","uv_index":"3"}]}""";

    @Test
    @DisplayName("大交通：append 双段（往返）渲成两组，元信息头行全部消失")
    void trainTwoSegmentsRenderWithoutMetaHeaders() {
        // TaskResultCache.append 形态：首段头被 get() 剥掉，中段分隔头仍在正文里
        String cached = OUTBOUND_JSON + "\n# task_id=T1 # registered_at=2026-10-20 23:08:08\n" + RETURN_JSON;

        String out = TaskResultFormatter.formatTrain(cached);

        assertThat(out).doesNotContain("task_id").doesNotContain("registered_at");
        assertThat(out).doesNotContain("{").doesNotContain("\"trains\"");
        assertThat(out).contains("杭州东站 → 哈尔滨站").contains("哈尔滨西站 → 杭州站");
        // 中转段明细：车次、站名、座席、来源、预订链接保留
        assertThat(out).contains("G1224").contains("T297").contains("二等座").contains("【飞猪】");
        assertThat(out).contains("[预订链接](https://router.feizhu.com/multi/webview?url=abc)");
        // 时长人性化 + 跨天标注（T297 当日 20:54 → 次日 02:06；K552 +2 天）
        assertThat(out).contains("17小时37分").contains("次日02:06").contains("第三日08:04");
        // 价格去尾零
        assertThat(out).contains("910元").contains("278.5元");
    }

    @Test
    @DisplayName("大交通：每组最多 3 条，超出折叠为「另有 N 个方案未列出」")
    void trainCapsOptionsPerGroup() {
        StringBuilder many = new StringBuilder("{\"count\":5,\"trains\":[");
        for (int i = 0; i < 5; i++) {
            many.append(i > 0 ? "," : "").append(
                    "{\"price\":\"100.00\",\"totalDuration\":\"60\",\"journeyType\":\"直达\",\"segments\":["
                            + "{\"trainNo\":\"G" + (100 + i) + "\",\"depStation\":\"杭州东站\","
                            + "\"arrStation\":\"哈尔滨站\",\"depTime\":\"2026-10-22 08:00:00\","
                            + "\"arrTime\":\"2026-10-22 09:00:00\",\"seatClass\":\"二等座\"}],"
                            + "\"source\":\"飞猪\"}");
        }
        many.append("],\"source\":\"飞猪\"}");

        String out = TaskResultFormatter.formatTrain(many.toString());

        assertThat(out).contains("G100").contains("G102").doesNotContain("G103");
        assertThat(out).contains("另有 2 个方案未列出");
    }

    @Test
    @DisplayName("大交通：非车票 JSON（12306 MCP 文本）剥头行后原样透传，不丢数据")
    void trainFallbackPassthrough() {
        String text = "# task_id=T1 # registered_at=2026-10-20 23:08:08\n"
                + "G1224 杭州东-沈阳北 08:29-18:46 二等座 ¥553.5 余票充足";

        String out = TaskResultFormatter.formatTrain(text);

        assertThat(out).doesNotContain("task_id");
        assertThat(out).contains("G1224 杭州东-沈阳北 08:29-18:46 二等座 ¥553.5 余票充足");
    }

    @Test
    @DisplayName("大交通：机票 flights[] 同构渲染（flightNo/机场/舱位字段回退）")
    void flightSchemaRenders() {
        String flightJson = """
                {"count":1,"flights":[{"price":"620.00","jumpUrl":"https://router.feizhu.com/ws/x",\
                "totalDuration":"135","journeyType":"直达","segments":[\
                {"flightNo":"CA1701","airline":"国航","depAirport":"杭州萧山","arrAirport":"哈尔滨太平",\
                "depTime":"2026-10-22 09:30:00","arrTime":"2026-10-22 11:45:00","duration":"135","cabinClass":"经济舱"}],\
                "source":"飞猪"}],"source":"飞猪"}""";

        String out = TaskResultFormatter.formatTrain(flightJson);

        assertThat(out).contains("CA1701").contains("杭州萧山→哈尔滨太平").contains("经济舱")
                .contains("2小时15分").doesNotContain("{");
    }

    @Test
    @DisplayName("住宿：渲成表格前 5 家，高德 POI 分类串清洗为 —，附总数说明")
    void hotelTableWithTypeCleaningAndCountNote() {
        String out = TaskResultFormatter.formatHotel(HOTEL_JSON);

        assertThat(out).doesNotContain("{").doesNotContain("task_id");
        assertThat(out).contains("| 酒店 | 类型 | 参考价 | 地址 | 来源 |");
        assertThat(out).contains("[哈尔滨暖橙公寓（中央大街店）](https://router.feizhu.com/ws/2m65pE)");
        assertThat(out).contains("经济型").contains("¥93");
        // 只取前 5 家：第 6 家（百联公寓）不进表，但出现在总数说明里
        assertThat(out).doesNotContain("百联公寓");
        assertThat(out).contains("共 10 家可选").contains("前 5 家");
    }

    @Test
    @DisplayName("住宿：高德条目（无价格、分类串 type）渲成 — 占位")
    void hotelAmapEntryRenders() {
        String amapOnly = """
                {"count":1,"hotels":[{"name":"百联公寓","address":"上海街90-3号",\
                "type":"住宿服务;住宿服务相关|商务住宅","source":"高德"}],"sources":{"高德":1}}""";

        String out = TaskResultFormatter.formatHotel(amapOnly);

        assertThat(out).contains("百联公寓").contains("| — | — |");
        assertThat(out).doesNotContain("住宿服务;");
    }

    @Test
    @DisplayName("v5-F2：酒店图集行——缓存 hotels[].imageUrl 代码组装，无图酒店不输出")
    void hotelGalleryFromImageUrls() {
        String raw = """
                {"count":2,"hotels":[\
                {"name":"杭州望湖宾馆","type":"四星级","price":"¥420","address":"西湖边",\
                "source":"飞猪","imageUrl":"https://img.alicdn.com/h1.jpg"},\
                {"name":"西湖大酒店","type":"舒适型","price":"¥350","address":"解放路","source":"高德"}],\
                "sources":{"飞猪":1,"高德":1}}""";

        String out = TaskResultFormatter.formatHotel(raw);

        assertThat(out).contains("📷 图集：")
                .contains("![杭州望湖宾馆](https://img.alicdn.com/h1.jpg)")
                .doesNotContain("西湖大酒店](");
    }

    @Test
    @DisplayName("天气：渲成预报表 + 按全程最低温给穿衣建议（-3℃ → 羽绒服档）")
    void weatherTableWithClothingAdvice() {
        String out = TaskResultFormatter.formatWeather(WEATHER_JSON);

        assertThat(out).doesNotContain("{").doesNotContain("temp_max");
        assertThat(out).contains("| 日期 | 白天 | 夜间 | 气温 | 风 | 湿度 |");
        assertThat(out).contains("10-20").contains("-3~9℃").contains("西风 1-3级").contains("51%");
        assertThat(out).contains("穿衣建议：").contains("羽绒服");
        // uv 峰值 3 < 5，不追加防晒
        assertThat(out).doesNotContain("防晒");
    }

    @Test
    @DisplayName("天气：高温低档 + 强紫外线追加防晒提示")
    void weatherHotWithUvAdvice() {
        String hot = """
                {"city":"三亚","days":1,"forecasts":[{"date":"2026-08-01","text_day":"晴","text_night":"晴",\
                "temp_max":"34","temp_min":"27","wind_dir_day":"南风","wind_scale_day":"3-4",\
                "humidity":"80","uv_index":"7"}]}""";

        String out = TaskResultFormatter.formatWeather(hot);

        assertThat(out).contains("轻薄衣物").contains("防晒");
    }

    @Test
    @DisplayName("降级与边界：null/空白原样返回；剥头行覆盖首行头与中间分隔头")
    void nullBlankAndStripHeaders() {
        assertThat(TaskResultFormatter.formatTrain(null)).isNull();
        assertThat(TaskResultFormatter.formatWeather("")).isEmpty();
        assertThat(TaskResultFormatter.formatHotel("  ")).isEqualTo("  ");

        String out = TaskResultFormatter.stripMetaHeaders(
                "# task_id=T1 # registered_at=2026-10-20 23:08:08\n正文A\n"
                        + "# task_id=T2 # registered_at=2026-10-20 23:09:09\n正文B");
        assertThat(out).isEqualTo("正文A\n\n正文B");
    }
}
