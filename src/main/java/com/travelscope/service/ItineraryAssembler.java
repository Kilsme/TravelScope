package com.travelscope.service;

import com.travelscope.collab.ItineraryDraft;
import com.travelscope.collab.ItineraryDraft.Budget;
import com.travelscope.collab.ItineraryDraft.BudgetItem;
import com.travelscope.collab.ItineraryDraft.DraftDay;
import com.travelscope.collab.ItineraryDraft.DraftItem;
import com.travelscope.collab.RoutePlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 行程草案组装器（卡 C：组装交给代码，零 LLM）
 * <p>
 * 把 planner 决策后的结构化字段（days/budget/notes/pendingConfirm）渲染成
 * itinerary_draft.json 的 markdownBody——原来由 LLM 花 ~60s 手写整篇 markdown，
 * 改为代码按模板拼装（~0.1s），LLM 只产结构字段。
 * </p>
 * <p>
 * 职责：按 route_plan 分日铺时间线（上一项结束=下一项开始）、每项「时间|活动|地点|费用」、
 * 来源标注按数据源头插【酒店·飞猪】【火车票·飞猪】等、末尾预算汇总+天气穿衣+注意事项；
 * 并做确定性校验（时间线连续性）。
 * </p>
 */
@Service
public class ItineraryAssembler {

    private static final Logger log = LoggerFactory.getLogger(ItineraryAssembler.class);

    /**
     * 渲染 markdownBody 并做校验，返回可落盘的完整 ItineraryDraft。
     *
     * @param skeleton LLM 产的结构字段（days/budget/notes/pendingConfirm），markdownBody 可为占位
     * @param routePlan plan_route 的分日路线（通勤/顺序参考，可空降级）
     * @param weatherSummary 天气摘要文本（缓存读，可空）
     * @param hotelSummary 酒店摘要文本（缓存读，可空）
     * @param trainSummary 车票/机票摘要文本（缓存读，可空）
     * @param knownPoiNames poi_shortlist 中已核验的 POI 名集合（防幻觉，可空跳过校验）
     * @param preferences intake.preference 的偏好关键词（已拆词，可空跳过校验）
     * @return 完整 ItineraryDraft（markdownBody 已渲染，幻觉项已剔除，偏好缺失已标注）
     */
    public ItineraryDraft assemble(ItineraryDraft skeleton,
                                   RoutePlan routePlan,
                                   String weatherSummary,
                                   String hotelSummary,
                                   String trainSummary,
                                   com.travelscope.collab.PoiShortlist shortlist,
                                   List<String> preferences) {
        // 已核验 POI 名集合（防幻觉）+ POI 名+主题文本（偏好匹配，只看 name，不看 reason 的取舍描述）
        java.util.Set<String> knownPoiNames = new java.util.HashSet<>();
        StringBuilder poiNames = new StringBuilder();
        if (shortlist != null) {
            shortlist.pois().forEach(p -> {
                knownPoiNames.add(p.name());
                poiNames.append(p.name()).append(' ');
            });
        }
        // 校验 1（防幻觉）：剔除候选池没有的 POI 项
        List<DraftDay> cleanedDays = skeleton.days();
        List<String> hallucinations = new ArrayList<>();
        if (!knownPoiNames.isEmpty()) {
            cleanedDays = filterHallucinatedPois(skeleton.days(), knownPoiNames, hallucinations);
        }
        // v5-F2 图片透传：shortlist 的 imageUrl 挂载到匹配行程项（随草案 JSON 持久化，
        // ItineraryProgress 逐天事件与修订增量链自动继承；renderDaySection 渲染图集行）
        cleanedDays = attachPoiImages(cleanedDays, shortlist);
        // 校验 2（偏好硬约束）：每个偏好关键词必须在 days 实际排程中有覆盖
        List<String> pending = new ArrayList<>(skeleton.pendingConfirm());
        if (preferences != null && !preferences.isEmpty()) {
            appendUncoveredPreferences(cleanedDays, preferences, pending);
        }

        ItineraryDraft cleaned = new ItineraryDraft(cleanedDays, skeleton.budget(),
                skeleton.notes(), pending, skeleton.markdownBody());
        // 分段化组合（v6 阶段五）：标题 + Σ 逐天节 + 全局节——与原 renderMarkdown 逐字节等价
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(cleaned.days().isEmpty() ? "行程方案"
                : "行程方案（" + cleaned.days().size() + " 天）").append("\n\n");
        for (DraftDay day : cleaned.days()) {
            sb.append(renderDaySection(day));
        }
        sb.append(renderGlobalSections(cleaned, weatherSummary, hotelSummary, trainSummary));
        String markdown = sb.toString();

        List<String> notes = new ArrayList<>(cleaned.notes());
        for (String h : hallucinations) {
            notes.add("已剔除未核验项: " + h);
        }
        List<String> timeIssues = validateTimeline(cleaned);
        for (String issue : timeIssues) {
            notes.add("时间线提示: " + issue);
        }
        log.info("ItineraryAssembler 渲染完成: {} 天, markdownBody={} 字符, 幻觉剔除={}, 时间线提示={} 条",
                cleanedDays.size(), markdown.length(), hallucinations.size(), timeIssues.size());
        return new ItineraryDraft(cleanedDays, cleaned.budget(), notes,
                pending, markdown);
    }

    /** 兼容旧签名（无校验入参，单测/降级用） */
    public ItineraryDraft assemble(ItineraryDraft skeleton, RoutePlan routePlan,
                                   String weatherSummary, String hotelSummary, String trainSummary) {
        return assemble(skeleton, routePlan, weatherSummary, hotelSummary, trainSummary,
                null, List.of());
    }

    /**
     * 防幻觉校验：剔除非景点的 POI 项（location/activity 含候选池没有的地名）。
     * 酒店/车站/机场/餐厅/车次等非景点项豁免（名称不含候选池 POI 但属合理行程要素）。
     */
    private List<DraftDay> filterHallucinatedPois(List<DraftDay> days,
                                                  java.util.Set<String> knownPoiNames,
                                                  List<String> hallucinations) {
        List<DraftDay> result = new ArrayList<>();
        for (DraftDay day : days) {
            List<DraftItem> kept = new ArrayList<>();
            for (DraftItem item : day.items()) {
                if (isNonPoiItem(item) || poiMentioned(item, knownPoiNames)) {
                    kept.add(item);
                } else {
                    hallucinations.add("Day" + day.day() + "「" + item.activity() + "/" + item.location()
                            + "」不在已核验 POI 清单，已剔除");
                }
            }
            result.add(new DraftDay(day.day(), day.date(), day.theme(), kept));
        }
        return result;
    }

    /** 非景点项豁免：车站/机场/酒店/餐厅/车次/航班/交通/集合等（包内共享给质检） */
    static boolean isNonPoiItem(DraftItem item) {
        String text = (nvl(item.activity()) + nvl(item.location())).toLowerCase();
        String[] exempt = {"站", "机场", "酒店", "宾馆", "餐", "饭", "吃", "高铁", "火车",
                "航班", "飞机", "地铁", "公交", "打车", "集合", "出发", "抵达", "入住",
                "退房", "休息", "早餐", "午餐", "晚餐", "夜市", "购物"};
        for (String kw : exempt) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /** 该项是否命中候选池 POI（双向包含；包内共享给质检） */
    static boolean poiMentioned(DraftItem item, java.util.Set<String> knownPoiNames) {
        String text = nvl(item.activity()) + nvl(item.location());
        for (String poi : knownPoiNames) {
            if (poi == null || poi.isBlank()) {
                continue;
            }
            // 双向包含：草案写「拙政园」对候选「拙政园」，或草案「苏州博物馆」对候选「苏州博物馆(本馆)」
            if (text.contains(poi) || poi.contains(nvl(item.location())) && !item.location().isBlank()) {
                return true;
            }
        }
        return false;
    }

    /** 偏好关键词同义/关联映射（包内共享：RuleReviewService 质检判定复用） */
    static final java.util.Map<String, List<String>> PREF_SYNONYMS = java.util.Map.ofEntries(
            java.util.Map.entry("园林", List.of("园林", "拙政园", "留园", "狮子林", "网师园", "个园", "何园", "豫园")),
            java.util.Map.entry("古镇", List.of("古镇", "同里", "周庄", "乌镇", "西塘", "甪直", "朱家角", "南浔")),
            java.util.Map.entry("海滨", List.of("海滨", "海滩", "海边", "海水浴场", "海岸", "沙滩", "海景")),
            java.util.Map.entry("美食", List.of("美食", "小吃", "餐", "菜", "火锅", "烧烤", "海鲜", "夜市", "茶")),
            java.util.Map.entry("自然", List.of("自然", "山", "湖", "公园", "森林", "湿地", "峡", "瀑", "峰")),
            java.util.Map.entry("文化", List.of("文化", "博物", "古迹", "遗址", "寺", "庙", "塔", "故居", "纪念")),
            java.util.Map.entry("历史", List.of("历史", "博物", "古迹", "遗址", "城墙", "古", "故居")),
            java.util.Map.entry("夜景", List.of("夜景", "夜", "灯", "灯光秀")),
            java.util.Map.entry("亲子", List.of("亲子", "儿童", "乐园", "动物园", "海洋馆", "游乐场")),
            java.util.Map.entry("购物", List.of("购物", "商场", "市集", "步行街", "免税店")),
            java.util.Map.entry("徒步", List.of("徒步", "登山", "步道", "爬山")),
            java.util.Map.entry("滑雪", List.of("滑雪", "雪场", "滑雪场")));

    /** 偏好覆盖校验：每个偏好关键词必须在 days 实际排程条目（activity/location/note/theme）中命中 */
    private void appendUncoveredPreferences(List<DraftDay> days,
                                            List<String> preferences,
                                            List<String> pending) {
        StringBuilder itineraryText = new StringBuilder();
        for (DraftDay day : days) {
            itineraryText.append(nvl(day.theme())).append(' ');
            for (DraftItem item : day.items()) {
                itineraryText.append(nvl(item.activity())).append(' ')
                        .append(nvl(item.location())).append(' ')
                        .append(nvl(item.note())).append(' ');
            }
        }
        String haystack = itineraryText.toString();
        for (String pref : preferences) {
            String kw = pref == null ? "" : pref.trim();
            if (kw.isEmpty()) {
                continue;
            }
            if (!preferenceCovered(kw, haystack)) {
                pending.add("偏好「" + kw + "」未在行程中体现，请确认是否接受（代码校验标注）");
            }
        }
    }

    /** 偏好是否被行程覆盖：关键词本身或其关联词任一命中（包内共享给质检） */
    static boolean preferenceCovered(String keyword, String haystack) {
        if (haystack.contains(keyword)) {
            return true;
        }
        List<String> synonyms = PREF_SYNONYMS.get(keyword);
        if (synonyms != null) {
            for (String syn : synonyms) {
                if (!syn.equals(keyword) && haystack.contains(syn)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ==================== 渲染分段化（v6 阶段五）：骨架/逐天/全局三个出口 ====================

    /**
     * 骨架渲染出口（零 LLM）：RouteDay → DraftDay 结构直出——item 的
     * time/location/activity 全由 route_plan 生成（LLM 原本只是誊写，P3 实证）。
     * 供组装编排（CollabTools）与流式进度（ItineraryProgress）共用。
     */
    public static DraftDay skeletonDay(RoutePlan.RouteDay routeDay) {
        List<DraftItem> items = new ArrayList<>();
        for (RoutePlan.RoutePoi p : routeDay.pois()) {
            items.add(new DraftItem(
                    (p.startTime() == null ? "" : p.startTime()) + "-" + (p.endTime() == null ? "" : p.endTime()),
                    p.name(), p.name(), null, null));
        }
        return new DraftDay(routeDay.day(), routeDay.date(), null, items);
    }

    /**
     * 逐天渲染出口：单天 markdown 节（## Day N（date）theme 标题 + 时间线表格 + ↳ note 行，
     * 末尾一个空行）。与 assemble 组合的拼接序逐字节等价于原 renderMarkdown 天循环体；
     * ItineraryProgress 以它做逐天事件的精确按构造渲染（替代正则切分）。
     */
    public String renderDaySection(DraftDay day) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Day ").append(day.day());
        if (day.date() != null && !day.date().isBlank()) {
            sb.append("（").append(day.date()).append("）");
        }
        if (day.theme() != null && !day.theme().isBlank()) {
            sb.append(" ").append(day.theme());
        }
        sb.append("\n\n");
        sb.append("| 时间 | 活动 | 地点 | 费用 |\n");
        sb.append("| --- | --- | --- | --- |\n");
        for (DraftItem item : day.items()) {
            sb.append("| ").append(nvl(item.time())).append(" | ")
                    .append(nvl(item.activity())).append(" | ")
                    .append(nvl(item.location())).append(" | ")
                    .append(item.cost() == null ? "—" : item.cost()).append(" |\n");
            if (item.note() != null && !item.note().isBlank()) {
                sb.append("| | ↳ ").append(item.note()).append(" | | |\n");
            }
        }
        // v5-F2 图集行：该天带图 POI 横排缩略图（代码组装，模型不参与；无图天不输出）
        List<DraftItem> withImages = new ArrayList<>();
        for (DraftItem item : day.items()) {
            if (item.imageUrl() != null && !item.imageUrl().isBlank()) {
                withImages.add(item);
            }
        }
        if (!withImages.isEmpty()) {
            sb.append("\n📷 图集：");
            for (DraftItem item : withImages) {
                sb.append("![").append(galleryAlt(item)).append("](").append(item.imageUrl()).append(") ");
            }
            sb.append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * v5-F2 图片挂载：把 poi_shortlist 的 imageUrl（非空者）挂到名称匹配的行程项上。
     * 匹配保守（防错图比无图更糟）：非景点项不配图（午餐/酒店/交通行）；名称精确等值
     * 或池名含条目地名（与防幻觉 poiMentioned 同款包含语义）。无任何挂载时原列表引用返回。
     */
    private List<DraftDay> attachPoiImages(List<DraftDay> days,
                                           com.travelscope.collab.PoiShortlist shortlist) {
        if (shortlist == null || shortlist.pois() == null || shortlist.pois().isEmpty()) {
            return days;
        }
        java.util.Map<String, String> images = new java.util.LinkedHashMap<>();
        for (com.travelscope.collab.PoiShortlist.PoiItem p : shortlist.pois()) {
            if (p.name() != null && p.imageUrl() != null && !p.imageUrl().isBlank()) {
                images.putIfAbsent(p.name(), p.imageUrl());
            }
        }
        if (images.isEmpty()) {
            return days;
        }
        List<DraftDay> result = new ArrayList<>();
        boolean changed = false;
        for (DraftDay day : days) {
            List<DraftItem> items = new ArrayList<>();
            for (DraftItem item : day.items()) {
                String url = matchPoiImage(item, images);
                if (url != null) {
                    items.add(new DraftItem(item.time(), item.activity(), item.location(),
                            item.cost(), item.note(), url));
                    changed = true;
                } else {
                    items.add(item);
                }
            }
            result.add(new DraftDay(day.day(), day.date(), day.theme(), items));
        }
        return changed ? result : days;
    }

    /** 单条行程项 → 匹配 POI 图（null=不配图） */
    private static String matchPoiImage(DraftItem item, java.util.Map<String, String> images) {
        if (isNonPoiItem(item)) {
            return null;
        }
        String location = nvl(item.location());
        if (location.isBlank()) {
            return null;
        }
        for (java.util.Map.Entry<String, String> e : images.entrySet()) {
            if (e.getKey().equals(location) || e.getKey().equals(item.activity())
                    || e.getKey().contains(location)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 图集 alt 文本：条目地名（方括号转全角防 markdown 冲突；空名兜底「图片」） */
    private static String galleryAlt(DraftItem item) {
        String alt = nvl(item.location()).replace("[", "（").replace("]", "）");
        return alt.isBlank() ? "图片" : alt;
    }

    /**
     * 全局节渲染出口：大交通/住宿/天气（缓存摘要）+ 预算汇总 + 待确认 + 注意事项。
     * 逐天事件不携带这些节（随 done 全文交付）。
     */
    public String renderGlobalSections(ItineraryDraft skeleton, String weatherSummary,
                                       String hotelSummary, String trainSummary) {
        StringBuilder sb = new StringBuilder();
        // 交通/住宿/天气块（代码按缓存数据插入，来源标注保留）
        if (trainSummary != null && !trainSummary.isBlank()) {
            sb.append("## 大交通\n\n").append(trainSummary).append("\n\n");
        }
        if (hotelSummary != null && !hotelSummary.isBlank()) {
            sb.append("## 住宿\n\n").append(hotelSummary).append("\n\n");
        }
        if (weatherSummary != null && !weatherSummary.isBlank()) {
            sb.append("## 天气与穿衣建议\n\n").append(weatherSummary).append("\n\n");
        }

        // 预算汇总
        Budget budget = skeleton.budget();
        if (budget != null) {
            sb.append("## 预算汇总\n\n");
            sb.append("- 总预算：").append(budget.totalEstimate()).append("（")
                    .append(budget.currency()).append("）\n");
            for (BudgetItem bi : budget.breakdown()) {
                sb.append("- ").append(bi.category()).append("：").append(bi.amount());
                if (bi.note() != null && !bi.note().isBlank()) {
                    sb.append("（").append(bi.note()).append("）");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // 待确认项
        if (!skeleton.pendingConfirm().isEmpty()) {
            sb.append("## 待确认\n\n");
            for (String p : skeleton.pendingConfirm()) {
                sb.append("- ").append(p).append("\n");
            }
            sb.append("\n");
        }

        // 注意事项
        if (!skeleton.notes().isEmpty()) {
            sb.append("## 注意事项\n\n");
            for (String n : skeleton.notes()) {
                sb.append("- ").append(n).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 确定性校验：时间线连续性（同一天内上一项结束 ≤ 下一项开始）。
     * 返回问题清单（空 = 无冲突），不抛异常——问题进 notes 提示而非拒写。
     */
    private List<String> validateTimeline(ItineraryDraft skeleton) {
        List<String> issues = new ArrayList<>();
        for (DraftDay day : skeleton.days()) {
            List<DraftItem> items = day.items();
            for (int i = 1; i < items.size(); i++) {
                String prevEnd = endTimeOf(items.get(i - 1).time());
                String curStart = startTimeOf(items.get(i).time());
                if (prevEnd != null && curStart != null && prevEnd.compareTo(curStart) > 0) {
                    issues.add("Day" + day.day() + " 第" + i + "项「" + items.get(i).activity()
                            + "」(" + curStart + ") 早于上一项结束(" + prevEnd + ")");
                }
            }
        }
        return issues;
    }

    /** 从 "09:00-11:30" 取结束时间 "11:30"；取不到返回 null */
    private static String endTimeOf(String timeRange) {
        if (timeRange == null) {
            return null;
        }
        int dash = timeRange.indexOf('-');
        return dash >= 0 ? timeRange.substring(dash + 1).trim() : null;
    }

    /** 从 "09:00-11:30" 取开始时间 "09:00"；取不到返回 null */
    private static String startTimeOf(String timeRange) {
        if (timeRange == null) {
            return null;
        }
        int dash = timeRange.indexOf('-');
        return dash >= 0 ? timeRange.substring(0, dash).trim() : timeRange.trim();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
