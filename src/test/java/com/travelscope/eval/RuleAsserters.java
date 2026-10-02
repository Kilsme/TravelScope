package com.travelscope.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static com.travelscope.config.AgentConfig.TaskWorkspaceService;

/**
 * 确定性规则断言器（FR-S15 双轨评估的规则轨；A5 约束：纯函数、零 LLM、可独立单测）。
 * <p>
 * 每个规则类型一个纯静态方法（输入 {@link EvalRunArtifact} → {@link RuleAssertion}），
 * 由 {@link #assertRule} 按 rule.type 分发；断言逻辑不写在测试类里。
 * </p>
 */
public final class RuleAsserters {

    /** 天数标题：第X天 / Day N / D1（大小写不敏感） */
    private static final Pattern DAY_HEADER = Pattern.compile(
            "(第\\s*[0-9一二三四五六七八九十]+\\s*天|\\bday\\s*\\d+|\\bd\\s?\\d+\\b)", Pattern.CASE_INSENSITIVE);

    /** 排期时刻（09:30 / 9:00）——"每日POI"的代理检查 */
    private static final Pattern SCHEDULE_TIME = Pattern.compile("\\b\\d{1,2}:\\d{2}\\b");

    /** 预算汇总行关键词（该行需同时含数字才算预算汇总） */
    private static final List<String> BUDGET_KEYWORDS =
            List.of("预算", "费用", "花费", "合计", "总计", "人均", "开销");

    /** 交通维度关键词族 */
    private static final List<String> TRANSPORT_KEYWORDS = List.of(
            "交通", "高铁", "地铁", "公交", "打车", "步行", "自驾", "航班", "火车", "飞机", "机场", "车站", "城际", "通勤", "大巴");

    /** 住宿维度关键词族 */
    private static final List<String> ACCOMMODATION_KEYWORDS = List.of(
            "住宿", "酒店", "民宿", "青旅", "客栈", "宾馆", "旅馆", "房型", "入住");

    /** 天气维度关键词族 */
    private static final List<String> WEATHER_KEYWORDS = List.of(
            "天气", "气温", "温度", "降雨", "降水", "晴", "多云", "阴天", "小雨", "中雨", "雷阵雨", "风力", "降温", "℃");

    /** 「指出不存在」类否定标记（A5：回复含"不存在/抱歉/替代"类标记而非编造） */
    private static final List<String> NEGATIVE_MARKERS = List.of(
            "不存在", "没有找到", "未找到", "无法找到", "查无", "无法查询到", "无法确认", "并非真实", "不是真实",
            "虚构", "已下线", "抱歉", "替代");

    /** 时间冲突的承认/调整标记（ROUTE_TOO_DENSE：应指出冲突并给出拆分/取舍方案） */
    private static final List<String> CONFLICT_MARKERS = List.of(
            "时间冲突", "不可行", "无法完成", "来不及", "太赶", "过于紧凑", "太紧凑", "装不下", "安排不下",
            "建议拆分", "拆分为", "分成两天", "分两天", "分多天", "多天", "分日", "取舍", "舍弃", "优先保留", "通勤超");

    private RuleAsserters() {
    }

    /** 规则分发入口：按 rule.type 路由到对应断言器 */
    public static RuleAssertion assertRule(EvalCase.Rule rule, EvalRunArtifact artifact) {
        return switch (rule.type()) {
            case FIELD_COMPLETE -> fieldComplete(artifact);
            case CONSTRAINT_COVERED -> constraintCovered(rule, artifact);
            case NO_HALLUCINATION -> noHallucination(rule, artifact);
            case TOOL_ORDER -> toolOrder(artifact);
            case TIME_CONFLICT_HANDLED -> timeConflictHandled(artifact);
        };
    }

    /**
     * FIELD_COMPLETE：行程四维完整——天数标题、每日POI排期（HH:mm 条目）、
     * 含数字的预算行、交通/住宿/天气三族关键词。行程草案缺失时用最终回复兜底。
     */
    public static RuleAssertion fieldComplete(EvalRunArtifact artifact) {
        String text = artifact.itineraryOrReply();
        if (text == null || text.isBlank()) {
            return RuleAssertion.fail("FIELD_COMPLETE", "无行程产物（itinerary_draft.md 与最终回复均为空）");
        }
        List<String> misses = new ArrayList<>();
        int dayHeaders = countMatches(DAY_HEADER, text);
        int scheduleEntries = countMatches(SCHEDULE_TIME, text);
        if (dayHeaders == 0) {
            misses.add("缺少天数标题（第X天/Day N）");
        }
        if (scheduleEntries == 0) {
            misses.add("缺少每日POI排期（HH:mm 时刻条目）");
        }
        if (!hasBudgetSummary(text)) {
            misses.add("缺少预算汇总（含金额数字的预算行）");
        }
        if (!containsAny(text, TRANSPORT_KEYWORDS)) {
            misses.add("缺少交通维度信息");
        }
        if (!containsAny(text, ACCOMMODATION_KEYWORDS)) {
            misses.add("缺少住宿维度信息");
        }
        if (!containsAny(text, WEATHER_KEYWORDS)) {
            misses.add("缺少天气维度信息");
        }
        if (misses.isEmpty()) {
            return RuleAssertion.pass("FIELD_COMPLETE",
                    "天数标题 %d 处、排期时刻 %d 处，预算/交通/住宿/天气齐备（%s）".formatted(
                            dayHeaders, scheduleEntries, text == artifact.itineraryText() ? "依据行程草案" : "依据最终回复"));
        }
        return RuleAssertion.fail("FIELD_COMPLETE", String.join("；", misses));
    }

    /**
     * CONSTRAINT_COVERED：用户硬约束关键词（params.keyword）出现在行程草案或最终回复中。
     */
    public static RuleAssertion constraintCovered(EvalCase.Rule rule, EvalRunArtifact artifact) {
        String keyword = rule.params().get("keyword");
        if (keyword == null || keyword.isBlank()) {
            return RuleAssertion.fail("CONSTRAINT_COVERED", "规则缺少 params.keyword（应由加载器校验拦截）");
        }
        boolean inItinerary = artifact.itineraryText() != null && artifact.itineraryText().contains(keyword);
        boolean inReply = artifact.finalReply() != null && artifact.finalReply().contains(keyword);
        if (inItinerary || inReply) {
            return RuleAssertion.pass("CONSTRAINT_COVERED",
                    "关键词「%s」出现于%s".formatted(keyword, inItinerary ? "行程草案" : "最终回复"));
        }
        return RuleAssertion.fail("CONSTRAINT_COVERED",
                "关键词「%s」未出现在行程草案与最终回复中（硬约束未被覆盖）".formatted(keyword));
    }

    /**
     * NO_HALLUCINATION（params.poi = 待核验的虚构地点名）：
     * 回复需含「指出不存在/替代」类否定标记，且虚构地点未被编入行程草案（排期产物）——
     * 回复里讨论该地点（指出其不存在）是允许的。
     */
    public static RuleAssertion noHallucination(EvalCase.Rule rule, EvalRunArtifact artifact) {
        String poi = rule.params().get("poi");
        if (poi == null || poi.isBlank()) {
            return RuleAssertion.fail("NO_HALLUCINATION", "规则缺少 params.poi（应由加载器校验拦截）");
        }
        String reply = artifact.finalReply() == null ? "" : artifact.finalReply();
        List<String> hits = new ArrayList<>();
        for (String marker : NEGATIVE_MARKERS) {
            if (reply.contains(marker)) {
                hits.add(marker);
            }
        }
        if (hits.isEmpty()) {
            return RuleAssertion.fail("NO_HALLUCINATION",
                    "回复未出现任何「指出不存在/替代」类标记（可能将虚构地点「%s」当真实景点编造）".formatted(poi));
        }
        String markers = String.join("/", hits);
        if (artifact.itineraryText() != null && !artifact.itineraryText().isBlank()) {
            if (artifact.itineraryText().contains(poi)) {
                return RuleAssertion.fail("NO_HALLUCINATION",
                        "虚构地点「%s」被编入行程草案（编造）；命中否定标记: [%s]".formatted(poi, markers));
            }
            return RuleAssertion.pass("NO_HALLUCINATION",
                    "回复含否定标记[%s]，且「%s」未编入行程草案".formatted(markers, poi));
        }
        return RuleAssertion.pass("NO_HALLUCINATION",
                "回复含否定标记[%s]；无行程草案可核验编造（缺草案由 FIELD_COMPLETE 独立判罚）".formatted(markers));
    }

    /**
     * TOOL_ORDER：按协作目录三个产物的修改时间序判断「先 POI 后路线后组装」。
     * 产物缺失或时间戳相同（文件系统精度不足）→ 降级跳过并注明；时间倒序 → FAIL。
     */
    public static RuleAssertion toolOrder(EvalRunArtifact artifact) {
        Path dir = artifact.taskDir();
        if (dir == null) {
            return RuleAssertion.skip("TOOL_ORDER", "无协作目录，无法判断产物时间序（降级跳过）");
        }
        Path poi = dir.resolve(TaskWorkspaceService.FILE_POI_SHORTLIST);
        Path route = dir.resolve(TaskWorkspaceService.FILE_ROUTE_PLAN);
        Path itinerary = dir.resolve(TaskWorkspaceService.FILE_ITINERARY_DRAFT);
        List<String> missing = new ArrayList<>();
        if (!Files.isRegularFile(poi)) {
            missing.add(TaskWorkspaceService.FILE_POI_SHORTLIST);
        }
        if (!Files.isRegularFile(route)) {
            missing.add(TaskWorkspaceService.FILE_ROUTE_PLAN);
        }
        if (!Files.isRegularFile(itinerary)) {
            missing.add(TaskWorkspaceService.FILE_ITINERARY_DRAFT);
        }
        if (!missing.isEmpty()) {
            return RuleAssertion.skip("TOOL_ORDER",
                    "产物缺失[%s]，无法判断工具链顺序（降级跳过）".formatted(String.join(", ", missing)));
        }
        try {
            long poiM = Files.getLastModifiedTime(poi).toMillis();
            long routeM = Files.getLastModifiedTime(route).toMillis();
            long itineraryM = Files.getLastModifiedTime(itinerary).toMillis();
            if (poiM == routeM || routeM == itineraryM) {
                return RuleAssertion.skip("TOOL_ORDER", "产物修改时间相同（文件系统精度不足），顺序无法判定（降级跳过）");
            }
            if (poiM < routeM && routeM < itineraryM) {
                return RuleAssertion.pass("TOOL_ORDER", "产物时间序正确: poi_shortlist → route_plan → itinerary_draft");
            }
            return RuleAssertion.fail("TOOL_ORDER",
                    "产物时间序异常: poi=%s route=%s itinerary=%s（应先 POI 后路线后组装）".formatted(
                            Instant.ofEpochMilli(poiM), Instant.ofEpochMilli(routeM), Instant.ofEpochMilli(itineraryM)));
        } catch (Exception e) {
            return RuleAssertion.skip("TOOL_ORDER", "读取产物时间戳失败（降级跳过）: " + e.getMessage());
        }
    }

    /**
     * TIME_CONFLICT_HANDLED（ROUTE_TOO_DENSE）：回复/行程应指出时间冲突并给出拆分或取舍方案——
     * 命中冲突/调整标记，或行程被拆分为 ≥2 个天标题（用户只要求 1 天）即视为已处理。
     */
    public static RuleAssertion timeConflictHandled(EvalRunArtifact artifact) {
        String reply = artifact.finalReply() == null ? "" : artifact.finalReply();
        String itinerary = artifact.itineraryText() == null ? "" : artifact.itineraryText();
        List<String> hits = new ArrayList<>();
        for (String marker : CONFLICT_MARKERS) {
            if (reply.contains(marker) || itinerary.contains(marker)) {
                hits.add(marker);
            }
        }
        if (!hits.isEmpty()) {
            return RuleAssertion.pass("TIME_CONFLICT_HANDLED", "命中冲突/调整标记[%s]".formatted(String.join("/", hits)));
        }
        int dayHeaders = itinerary.isBlank() ? 0 : countMatches(DAY_HEADER, itinerary);
        if (dayHeaders >= 2) {
            return RuleAssertion.pass("TIME_CONFLICT_HANDLED",
                    "行程已拆分为 %d 天（用户仅要求 1 天），过密冲突被消化".formatted(dayHeaders));
        }
        return RuleAssertion.fail("TIME_CONFLICT_HANDLED",
                "回复与行程均未指出时间冲突/过密问题，也未拆分多天（可能直接输出了不可行的时刻表）");
    }

    // ==================== 工具方法 ====================

    private static int countMatches(Pattern pattern, String text) {
        return (int) pattern.matcher(text).results().count();
    }

    private static boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }

    /** 预算行 = 同一行内含预算关键词且含数字（排除仅有"预算"字样而无金额的空标题行） */
    private static boolean hasBudgetSummary(String text) {
        for (String line : text.split("\n")) {
            if (line.chars().anyMatch(Character::isDigit) && containsAny(line, BUDGET_KEYWORDS)) {
                return true;
            }
        }
        return false;
    }
}
