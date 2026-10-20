package com.travelscope.service;

import com.travelscope.collab.IntakeResult;
import com.travelscope.collab.ItineraryDraft;
import com.travelscope.collab.ItineraryDraft.DraftDay;
import com.travelscope.collab.ItineraryDraft.DraftItem;
import com.travelscope.collab.PoiShortlist;
import com.travelscope.collab.ReviewPassed;
import com.travelscope.collab.ReviewReport;
import com.travelscope.collab.RoutePlan;
import com.travelscope.collab.TaskWorkspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则质检服务（质检降级为规则校验：常规路径零 LLM，<1s 出结论）
 * <p>
 * 替代 reviewer-agent 的需求符合性检查：三段规则（基本要素 / 时间线自洽 / 预算+偏好）
 * 全部由确定性代码判定，产物走 TaskWorkspace 落 review_passed.json / review_report.json
 * ——与 reviewer-agent 同契约，下游消费方（reviewAnnotation / readReviewPayload）零改动。
 * </p>
 * <p>
 * 通过标准与 reviewer 对齐：总分 ≥80 且单维 ≥12 且三段全 pass。
 * 规则不通过时由编排层唤起 reviewer-agent 做定向修订（兜底闭环保留），修订收敛后重跑本服务。
 * </p>
 */
@Service
public class RuleReviewService {

    private static final Logger log = LoggerFactory.getLogger(RuleReviewService.class);

    /** 通过阈值（与 ReviewerAgent.PASS_TOTAL_SCORE / PASS_MIN_DIMENSION 对齐） */
    private static final int PASS_TOTAL = 80;
    private static final int PASS_MIN_DIM = 12;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 预算数字提取：取文本中的全部数字（"约 763 元/人"、"¥950–1100" 均可） */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d{1,2})?");

    private final TaskWorkspace taskWorkspace;

    public RuleReviewService(TaskWorkspace taskWorkspace) {
        this.taskWorkspace = taskWorkspace;
    }

    /** 质检结论：passed + 各段问题 + 修订任务说明（给 reviewer 的输入） */
    public record Outcome(boolean passed, List<String> issues) {
        /** 规则失败时给 reviewer 的定向修订任务说明 */
        public String revisionTask(String collabDir, String sessionId) {
            StringBuilder sb = new StringBuilder();
            sb.append("协作目录: ").append(collabDir).append("；sessionId: ").append(sessionId).append('\n');
            sb.append("【系统规则质检发现以下问题，请定向修订】\n");
            for (String issue : issues) {
                sb.append("- ").append(issue).append('\n');
            }
            sb.append("请读 ").append(collabDir).append("/intake_result.json 与 ")
                    .append(collabDir).append("/itinerary_draft.json，按你的修订流程就地修复")
                    .append("（POI/偏好问题调 revise_poi_selection，路线问题调 plan_route，预算问题调 searchHotels），")
                    .append("修订收敛后照常写 review_passed.json 或 review_report.json。");
            return sb.toString();
        }
    }

    /**
     * 执行规则质检：读契约文件 → 三段检查 → 评分 → 落产物。
     * review_passed.json 一旦存在不被覆盖（粘滞语义，与 ReviewerRetryMiddleware 重置锚点一致）。
     */
    public Outcome check(String userId, String sessionId) {
        Optional<ItineraryDraft> draftOpt = taskWorkspace.readItineraryDraft(userId, sessionId);
        if (draftOpt.isEmpty()) {
            log.info("规则质检跳过（无行程草案产物）: 用户={}, 会话={}", userId, sessionId);
            return new Outcome(true, List.of());
        }
        ItineraryDraft draft = draftOpt.get();
        Optional<IntakeResult> intakeOpt = taskWorkspace.readIntakeResult(userId, sessionId);
        Optional<PoiShortlist> shortlistOpt = taskWorkspace.readPoiShortlist(userId, sessionId);
        Optional<RoutePlan> routePlanOpt = taskWorkspace.readRoutePlan(userId, sessionId);

        List<String> seg1Issues = new ArrayList<>();
        List<String> seg2Issues = new ArrayList<>();
        List<String> seg3Issues = new ArrayList<>();
        List<String> verified = new ArrayList<>();

        // ---------- 段1：基本要素（天数 / 目的地 / 出发地） ----------
        int completeness = 20;
        if (intakeOpt.isPresent()) {
            IntakeResult intake = intakeOpt.get();
            String daysStr = valueOf(intake.fields().days());
            if (daysStr != null) {
                try {
                    int expected = Integer.parseInt(daysStr.trim());
                    if (draft.days().size() == expected) {
                        verified.add("天数=" + expected + " 与 intake 一致");
                    } else {
                        seg1Issues.add("天数不符：intake 要求 " + expected + " 天，草案实际 " + draft.days().size() + " 天");
                        completeness = 0;
                    }
                } catch (NumberFormatException e) {
                    verified.add("天数无法解析（intake 值=" + daysStr + "），跳过比对");
                }
            }
            String destination = valueOf(intake.fields().destination());
            if (destination != null && !destination.isBlank()) {
                if (textCovers(draft, destination)) {
                    verified.add("目的地「" + destination + "」在草案中体现");
                } else {
                    seg1Issues.add("目的地不符：intake 要求「" + destination + "」，草案全文未提及");
                    completeness = Math.min(completeness, 12);
                }
            }
            String fromCity = valueOf(intake.fields().fromCity());
            if (fromCity != null && !fromCity.isBlank()) {
                if (textCovers(draft, fromCity)) {
                    verified.add("出发地「" + fromCity + "」在草案中体现");
                } else {
                    seg1Issues.add("出发地未体现：intake 要求从「" + fromCity + "」出发，草案全文未提及");
                    completeness = Math.min(completeness, 12);
                }
            }
        }

        // ---------- 段2：时间线自洽（结构字段级区间重叠） ----------
        List<String> overlaps = findTimelineOverlaps(draft);
        int timeConflict = Math.max(0, 20 - 5 * overlaps.size());
        if (overlaps.isEmpty()) {
            verified.add("时间线自洽：各日时段无重叠");
        } else {
            seg2Issues.addAll(overlaps);
        }

        // ---------- 段3：预算 + 偏好 + 防幻觉 ----------
        int costBudget = 20;
        if (intakeOpt.isPresent() && draft.budget() != null) {
            String budgetLimit = valueOf(intakeOpt.get().fields().budget());
            String estimate = draft.budget().totalEstimate();
            Double limit = maxNumber(budgetLimit);
            Double est = maxNumber(estimate);
            if (limit == null || est == null || limit <= 0) {
                verified.add("预算无法解析为数值（limit=" + budgetLimit + ", estimate=" + estimate + "），按 UNKNOWN 保守放行");
                costBudget = 12;
            } else {
                double deviation = Math.abs(est - limit) / limit;
                if (deviation <= 0.10) {
                    verified.add("总费用 " + est + " vs 预算 " + limit + "，偏差在 ±10% 内");
                } else {
                    seg3Issues.add("预算超支：估算 " + est + " vs 预算上限 " + limit
                            + "（偏差 " + (int) (deviation * 100) + "%）");
                    costBudget = deviation <= 0.25 ? 10 : 0;
                }
            }
        }

        int poiReasonableness = 20;
        if (intakeOpt.isPresent()) {
            List<String> prefs = splitPrefs(valueOf(intakeOpt.get().fields().preference()));
            if (!prefs.isEmpty()) {
                String haystack = draftText(draft);
                int covered = 0;
                for (String pref : prefs) {
                    if (ItineraryAssembler.preferenceCovered(pref, haystack)) {
                        covered++;
                        verified.add("偏好「" + pref + "」已被行程覆盖");
                    } else {
                        seg3Issues.add("偏好「" + pref + "」未在行程中体现（未覆盖且未显式披露取舍）");
                    }
                }
                poiReasonableness = 20 * covered / prefs.size();
            }
        }

        int feasibility = 20;
        if (routePlanOpt.isPresent()) {
            int unplaced = routePlanOpt.get().solverMeta().unplaced() == null
                    ? 0 : routePlanOpt.get().solverMeta().unplaced().size();
            feasibility = Math.max(0, 20 - 4 * unplaced);
            if (unplaced == 0) {
                verified.add("路线求解无溢出（全部 POI 排入）");
            } else {
                seg1Issues.add("路线求解有 " + unplaced + " 个 POI 未排入（unplaced 溢出）");
            }
        } else {
            feasibility = 12;
        }
        // 防幻觉（报告模式）：候选池外 POI 检出即扣可行性 + 记段1
        if (shortlistOpt.isPresent()) {
            Set<String> known = new HashSet<>();
            shortlistOpt.get().pois().forEach(p -> known.add(p.name()));
            if (!known.isEmpty()) {
                for (DraftDay day : draft.days()) {
                    for (DraftItem item : day.items()) {
                        if (!ItineraryAssembler.isNonPoiItem(item)
                                && !ItineraryAssembler.poiMentioned(item, known)) {
                            seg1Issues.add("Day" + day.day() + "「" + item.activity() + "/" + item.location()
                                    + "」不在已核验 POI 清单（疑似幻觉）");
                            feasibility = Math.max(0, feasibility - 5);
                        }
                    }
                }
                if (seg1Issues.stream().noneMatch(i -> i.contains("疑似幻觉"))) {
                    verified.add("全部景点项均来自已核验 POI 清单");
                }
            }
        }

        // ---------- 汇总评分与通过判定 ----------
        boolean seg1Pass = seg1Issues.isEmpty();
        boolean seg2Pass = seg2Issues.isEmpty();
        boolean seg3Pass = seg3Issues.isEmpty();
        int total = completeness + feasibility + timeConflict + costBudget + poiReasonableness;
        int minDim = Math.min(completeness, Math.min(feasibility,
                Math.min(timeConflict, Math.min(costBudget, poiReasonableness))));
        boolean passed = total >= PASS_TOTAL && minDim >= PASS_MIN_DIM
                && seg1Pass && seg2Pass && seg3Pass;

        String now = java.time.LocalDateTime.now().format(FMT);
        List<String> issues = new ArrayList<>();
        issues.addAll(seg1Issues);
        issues.addAll(seg2Issues);
        issues.addAll(seg3Issues);

        if (passed) {
            taskWorkspace.writeReviewPassed(userId, sessionId,
                    new ReviewPassed(total, minDim, verified, now));
            log.info("规则质检通过: 用户={}, 会话={}, 总分={}, 检查项={}", userId, sessionId, total, verified.size());
        } else {
            // review_passed.json 粘滞：已被通过的产物不被规则失败覆盖
            if (taskWorkspace.readReviewPassed(userId, sessionId).isPresent()) {
                log.info("规则质检未通过但 review_passed.json 已存在（粘滞保留）: 用户={}, 会话={}", userId, sessionId);
                return new Outcome(true, List.of());
            }
            ReviewReport.ReviewScores scores = new ReviewReport.ReviewScores(
                    completeness, feasibility, timeConflict, costBudget, poiReasonableness);
            Map<String, List<String>> suggestions = new HashMap<>();
            if (!seg1Issues.isEmpty()) suggestions.put("1", seg1Issues);
            if (!seg2Issues.isEmpty()) suggestions.put("2", seg2Issues);
            if (!seg3Issues.isEmpty()) suggestions.put("3", seg3Issues);
            taskWorkspace.writeReviewReport(userId, sessionId, new ReviewReport(
                    List.of(new ReviewReport.ReviewSegment(1, seg1Pass, seg1Issues),
                            new ReviewReport.ReviewSegment(2, seg2Pass, seg2Issues),
                            new ReviewReport.ReviewSegment(3, seg3Pass, seg3Issues)),
                    scores, total, suggestions, now));
            log.info("规则质检未通过: 用户={}, 会话={}, 总分={}, 问题数={}", userId, sessionId, total, issues.size());
        }
        return new Outcome(passed, issues);
    }

    // ==================== 内部 ====================

    /** 结构字段级时间线重叠检查（报告模式：不改产物，只列问题） */
    private List<String> findTimelineOverlaps(ItineraryDraft draft) {
        List<String> issues = new ArrayList<>();
        for (DraftDay day : draft.days()) {
            List<DraftItem> items = day.items();
            for (int i = 1; i < items.size(); i++) {
                Integer prevEnd = endMinute(items.get(i - 1).time());
                Integer curStart = startMinute(items.get(i).time());
                if (prevEnd != null && curStart != null && curStart < prevEnd) {
                    issues.add("Day" + day.day() + " 第" + (i + 1) + "项「" + items.get(i).activity()
                            + "」开始(" + items.get(i).time() + ") 早于上一项「" + items.get(i - 1).activity()
                            + "」结束(" + items.get(i - 1).time() + ")");
                }
            }
        }
        return issues;
    }

    private static Integer startMinute(String timeRange) {
        if (timeRange == null) return null;
        int dash = timeRange.indexOf('-');
        return parseMinute(dash >= 0 ? timeRange.substring(0, dash).trim() : timeRange.trim());
    }

    private static Integer endMinute(String timeRange) {
        if (timeRange == null) return null;
        int dash = timeRange.indexOf('-');
        return dash >= 0 ? parseMinute(timeRange.substring(dash + 1).trim()) : null;
    }

    private static Integer parseMinute(String hhmm) {
        try {
            return LocalTime.parse(hhmm).toSecondOfDay() / 60;
        } catch (Exception e) {
            return null;
        }
    }

    /** 目的地/出发地覆盖：草案全文（主题+条目+备注+markdown）包含目标词 */
    private boolean textCovers(ItineraryDraft draft, String keyword) {
        return draftText(draft).contains(keyword);
    }

    private String draftText(ItineraryDraft draft) {
        StringBuilder sb = new StringBuilder();
        if (draft.markdownBody() != null) {
            sb.append(draft.markdownBody()).append(' ');
        }
        for (DraftDay day : draft.days()) {
            sb.append(day.theme() == null ? "" : day.theme()).append(' ');
            for (DraftItem item : day.items()) {
                sb.append(item.activity()).append(' ').append(item.location()).append(' ')
                        .append(item.note() == null ? "" : item.note()).append(' ');
            }
        }
        draft.notes().forEach(n -> sb.append(n).append(' '));
        draft.pendingConfirm().forEach(p -> sb.append(p).append(' '));
        return sb.toString();
    }

    /** 提取文本中的最大数字（预算区间取上限做保守比对）；无数字返回 null */
    private static Double maxNumber(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = NUMBER.matcher(text);
        Double max = null;
        while (m.find()) {
            double v = Double.parseDouble(m.group());
            max = max == null ? v : Math.max(max, v);
        }
        return max;
    }

    private static String valueOf(IntakeResult.FieldValue fv) {
        return fv == null ? null : fv.value();
    }

    private static List<String> splitPrefs(String preference) {
        if (preference == null || preference.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(preference.split("[,，、\\s]+"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
