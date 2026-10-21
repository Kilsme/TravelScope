package com.travelscope.service;

import com.alibaba.fastjson2.JSONObject;
import com.travelscope.collab.ItineraryDraft;
import com.travelscope.collab.RoutePlan;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.dto.ChatEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R3② 流式行程进度（v6 生产化·P1-lite 切片）：PLANNING 轮把行程产物以
 * 「骨架先行 + 逐天填充 + 修订天重推」的粒度推给前端，替代全程占位等待。
 * <p>
 * 事件契约（SSE）：
 * <ul>
 *   <li>{@code itinerary_skeleton}：route_plan.json 落盘即推（代码渲染零 LLM），
 *       载荷 {days:[{day, poiNames[]}], dayCount}；骨架变化（如修订轮重排）时重推</li>
 *   <li>{@code itinerary_day}：itinerary_draft.json 落盘后按「## Day N」节切分逐天推送，
 *       载荷 {day, version, markdown}；天内容变化（修订轮）按 version+1 重推该天</li>
 * </ul>
 * 触发点两处：planner 事件流的工具完成钩子（plan_route / assemble_itinerary /
 * revise_poi_selection 落盘即读即推，见 ChatService.mapAgentEventStream）；
 * 编排 step5 的修订差量补推（reviewer 流不经主流，见 planningOrchestration）。
 * 状态为轮级局部（方法局部变量，并发轮天然隔离）；幂等：内容未变不重发。
 * </p>
 */
public class ItineraryProgress {

    private static final Logger log = LoggerFactory.getLogger(ItineraryProgress.class);

    /** 落盘即触发进度推送的工具（plan_route 自动链组装时同时落 route_plan 与 draft） */
    public static final Set<String> TRIGGER_TOOLS =
            Set.of("plan_route", "assemble_itinerary", "revise_poi_selection");

    /** 已推骨架的内容键（null = 未推过；变化即重推） */
    private String lastSkeletonKey;
    /** 已推各天的 markdown（内容变化即重推该天） */
    private final Map<Integer, String> emittedDayMarkdown = new LinkedHashMap<>();
    /** 各天当前版本号（从 1 起，重推 +1） */
    private final Map<Integer, Integer> dayVersions = new HashMap<>();

    /**
     * 读工作区产物 → 产出增量进度事件（骨架首推/变化重推 + 变化天版本递增）。
     * 产物缺失/读取异常只记 debug 不中断（流式进度是增强，绝不影响主链路）。
     */
    public List<ChatEvent> emit(TaskWorkspace workspace, String userId, String sessionId) {
        List<ChatEvent> events = new ArrayList<>();
        try {
            workspace.readRoutePlan(userId, sessionId).ifPresent(plan -> {
                String key = skeletonKey(plan);
                if (!key.equals(lastSkeletonKey)) {
                    lastSkeletonKey = key;
                    events.add(skeletonEvent(plan));
                }
            });
            workspace.readItineraryDraft(userId, sessionId).ifPresent(draft ->
                    events.addAll(dayEventsFor(draft)));
        } catch (Exception e) {
            log.debug("行程进度事件构建失败（不影响主链路）: {}", e.getMessage());
        }
        return events;
    }

    /** 草案逐天差量：按「## Day N」节切分 markdownBody，内容变化的天以 version+1 重推 */
    private List<ChatEvent> dayEventsFor(ItineraryDraft draft) {
        List<ChatEvent> events = new ArrayList<>();
        if (draft.markdownBody() == null || draft.markdownBody().isBlank()) {
            return events;
        }
        splitDaySections(draft.markdownBody()).forEach((day, markdown) -> {
            if (!markdown.equals(emittedDayMarkdown.get(day))) {
                emittedDayMarkdown.put(day, markdown);
                int version = dayVersions.merge(day, 1, Integer::sum);
                events.add(dayEvent(day, version, markdown));
            }
        });
        return events;
    }

    /** 骨架内容键：天数 + 每日 POI 名序列（变化即视为新骨架） */
    static String skeletonKey(RoutePlan plan) {
        StringBuilder sb = new StringBuilder();
        for (RoutePlan.RouteDay day : plan.days()) {
            sb.append(day.day()).append(':');
            day.pois().forEach(p -> sb.append(p.name()).append(','));
            sb.append('|');
        }
        return sb.toString();
    }

    static ChatEvent skeletonEvent(RoutePlan plan) {
        List<JSONObject> days = new ArrayList<>();
        plan.days().forEach(d -> {
            JSONObject o = new JSONObject();
            o.put("day", d.day());
            o.put("poiNames", d.pois().stream().map(RoutePlan.RoutePoi::name).toList());
            days.add(o);
        });
        JSONObject payload = new JSONObject();
        payload.put("dayCount", plan.days().size());
        payload.put("days", days);
        return ChatEvent.of(ChatEvent.TYPE_ITINERARY_SKELETON, payload.toJSONString());
    }

    static ChatEvent dayEvent(int day, int version, String markdown) {
        JSONObject payload = new JSONObject();
        payload.put("day", day);
        payload.put("version", version);
        payload.put("markdown", markdown);
        return ChatEvent.of(ChatEvent.TYPE_ITINERARY_DAY, payload.toJSONString());
    }

    /**
     * markdownBody 按「## Day N」标题节切分（标题行归该节）。标题前的总述与
     * Day 节之后的大交通/住宿/天气/预算等非天节不入返回（它们随 done 全文交付）。
     */
    static Map<Integer, String> splitDaySections(String markdownBody) {
        Map<Integer, String> sections = new LinkedHashMap<>();
        String[] lines = markdownBody.split("\n", -1);
        Integer currentDay = null;
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            java.util.regex.Matcher m = DAY_HEADING.matcher(line);
            if (m.find()) {
                if (currentDay != null) {
                    sections.put(currentDay, current.toString().strip());
                }
                currentDay = Integer.parseInt(m.group(1));
                current = new StringBuilder();
            } else if (currentDay != null && H2_HEADING.matcher(line).lookingAt()) {
                // 非天节的二级标题（大交通/住宿/天气/预算…）→ 当前天节收束，
                // 后续内容不积累（随 done 全文交付）
                sections.put(currentDay, current.toString().strip());
                currentDay = null;
                current = new StringBuilder();
            }
            if (currentDay != null) {
                current.append(line).append('\n');
            }
        }
        if (currentDay != null) {
            sections.put(currentDay, current.toString().strip());
        }
        return sections;
    }

    /** 「## Day 3（2026-10-24）主题」样式标题（ItineraryAssembler.renderMarkdown 的产出格式） */
    private static final java.util.regex.Pattern DAY_HEADING =
            java.util.regex.Pattern.compile("^##\\s+Day\\s+(\\d+)");
    /** 二级标题（不含更深层级###）——天节内出现的非 Day 二级标题即天节边界 */
    private static final java.util.regex.Pattern H2_HEADING =
            java.util.regex.Pattern.compile("^##\\s");
}
