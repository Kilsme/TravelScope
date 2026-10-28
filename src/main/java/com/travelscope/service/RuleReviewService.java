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

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * R1 质检分级（v6）：规则违例分 HARD / SOFT 两级，裁决三级——
 * PASS（全清）/ PASS_WITH_WARNINGS（仅 SOFT：不回炉，软提示随 review_passed.json 落盘带
 * warning 交付）/ FAIL（任一 HARD：走既有 reviewer 定向修订链路，修订收敛后重跑本服务）。
 * 评分照常计算并落产物（供展示与 eval 回归），但不再作为回炉门控——门控只看违例级别
 * （「无违例必高分」由各维满分 20、扣分必伴随违例保证）。
 * </p>
 */
@Service
public class RuleReviewService {

    private static final Logger log = LoggerFactory.getLogger(RuleReviewService.class);

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 预算数字提取：取文本中的全部数字（"约 763 元/人"、"¥950–1100" 均可） */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d{1,2})?");

    // ---------- R1 规则名（warnings 载荷与日志的稳定标识） ----------
    private static final String RULE_DAYS_MISMATCH = "DAYS_MISMATCH";
    private static final String RULE_DESTINATION_MISSING = "DESTINATION_MISSING";
    private static final String RULE_FROM_CITY_MISSING = "FROM_CITY_MISSING";
    private static final String RULE_POI_HALLUCINATION = "POI_HALLUCINATION";
    private static final String RULE_TIMELINE_OVERLAP = "TIMELINE_OVERLAP";
    private static final String RULE_UNPLACED_OVERFLOW = "UNPLACED_OVERFLOW";
    private static final String RULE_UNPLACED_TRADEOFF = "UNPLACED_TRADEOFF";
    private static final String RULE_BUDGET_DEVIATION = "BUDGET_DEVIATION";
    private static final String RULE_BUDGET_UNPARSEABLE = "BUDGET_UNPARSEABLE";
    private static final String RULE_BUDGET_ARITHMETIC_MISMATCH = "BUDGET_ARITHMETIC_MISMATCH";
    private static final String RULE_PRICE_SOURCE_MISSING = "PRICE_SOURCE_MISSING";
    private static final String RULE_PREFERENCE_COVERAGE = "PREFERENCE_COVERAGE";
    private static final String RULE_ROUTE_PLAN_MISSING = "ROUTE_PLAN_MISSING";

    private final TaskWorkspace taskWorkspace;

    public RuleReviewService(TaskWorkspace taskWorkspace) {
        this.taskWorkspace = taskWorkspace;
    }

    /** 违例级别：HARD=硬伤（回炉），SOFT=软问题（带 warning 交付，零回炉） */
    enum Severity { HARD, SOFT }

    /**
     * 分级违例（R1）：severity 决定回炉路由；detail 进 warning 载荷 / 修订任务；
     * suggestion 可空（并非每条规则都有可执行建议）。
     */
    record Violation(String rule, int segment, Severity severity, String detail, String suggestion) {
    }

    /**
     * 质检结论：三级裁决 + 各段问题 + 软提示 + 给 reviewer 的定向修订任务文本。
     * revisionTaskText 仅 FAIL 时非空（在 check() 失败分支一次性生成，
     * 内联 intake/草案 JSON 正文——reviewer 首轮免 read_file；修订后重读由提示词约束）。
     */
    public record Outcome(Verdict verdict, List<String> issues,
                          List<ReviewPassed.ReviewWarning> warnings, String revisionTaskText,
                          String reasonCode, boolean retryable) {

        /** 三级裁决：PASS 全过；PASS_WITH_WARNINGS 仅软问题（带 warning 交付）；FAIL 含硬伤（回炉） */
        public enum Verdict { PASS, PASS_WITH_WARNINGS, FAIL }

        public Outcome {
            issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
            warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        }

        /** 保持既有规则调用方兼容；无单一稳定原因码时省略。 */
        public Outcome(Verdict verdict, List<String> issues,
                       List<ReviewPassed.ReviewWarning> warnings, String revisionTaskText) {
            this(verdict, issues, warnings, revisionTaskText,
                    verdict == Verdict.FAIL ? "RULE_VIOLATION" : "NONE",
                    verdict == Verdict.FAIL);
        }

        /** 非 FAIL 即视为通过（编排层据此决定是否唤起 reviewer；SOFT 不占用回炉额度） */
        public boolean passed() {
            return verdict != Verdict.FAIL;
        }

        /** 兼容构造（布尔视角）：true→PASS / false→FAIL，无软提示——既有打桩与测试零改动 */
        public Outcome(boolean passed, List<String> issues, String revisionTaskText) {
            this(passed ? Verdict.PASS : Verdict.FAIL, issues, List.of(), revisionTaskText,
                    passed ? "NONE" : "RULE_VIOLATION", !passed);
        }
    }

    /**
     * 执行规则质检：读契约文件 → 三段检查（逐条分级）→ 评分 → 落产物。
     * PASS / PASS_WITH_WARNINGS 写 review_passed.json（后者带 warnings）；
     * FAIL 写 review_report.json。review_passed.json 一旦存在不被 FAIL 覆盖
     * （粘滞语义，与 ReviewerRetryMiddleware 重置锚点一致）。
     */
    public Outcome check(String userId, String sessionId) {
        Optional<ItineraryDraft> draftOpt = taskWorkspace.readItineraryDraft(userId, sessionId);
        if (draftOpt.isEmpty()) {
            String issue = "缺少 itinerary_draft.json，规划草案尚未生成，无法执行质量复检";
            log.warn("规则质检失败（DRAFT_MISSING）: 用户={}, 会话={}", userId, sessionId);
            // 缺少上游草案不是 reviewer 可修订的问题：FAIL 但不可重试，且绝不写 review_passed。
            return new Outcome(Outcome.Verdict.FAIL, List.of(issue), List.of(), null,
                    "DRAFT_MISSING", false);
        }
        ItineraryDraft draft = draftOpt.get();
        Optional<IntakeResult> intakeOpt = taskWorkspace.readIntakeResult(userId, sessionId);
        Optional<PoiShortlist> shortlistOpt = taskWorkspace.readPoiShortlist(userId, sessionId);
        Optional<RoutePlan> routePlanOpt = taskWorkspace.readRoutePlan(userId, sessionId);

        List<String> seg1Issues = new ArrayList<>();
        List<String> seg2Issues = new ArrayList<>();
        List<String> seg3Issues = new ArrayList<>();
        List<String> verified = new ArrayList<>();
        List<Violation> violations = new ArrayList<>();

        // ---------- 段1：基本要素（天数 / 目的地 / 出发地）——均为 HARD ----------
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
                        violations.add(new Violation(RULE_DAYS_MISMATCH, 1, Severity.HARD,
                                "天数不符：intake 要求 " + expected + " 天，草案实际 " + draft.days().size() + " 天", null));
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
                    violations.add(new Violation(RULE_DESTINATION_MISSING, 1, Severity.HARD,
                            "目的地不符：intake 要求「" + destination + "」，草案全文未提及", null));
                    completeness = Math.min(completeness, 12);
                }
            }
            String fromCity = valueOf(intake.fields().fromCity());
            if (fromCity != null && !fromCity.isBlank()) {
                if (textCovers(draft, fromCity)) {
                    verified.add("出发地「" + fromCity + "」在草案中体现");
                } else {
                    seg1Issues.add("出发地未体现：intake 要求从「" + fromCity + "」出发，草案全文未提及");
                    violations.add(new Violation(RULE_FROM_CITY_MISSING, 1, Severity.HARD,
                            "出发地未体现：intake 要求从「" + fromCity + "」出发，草案全文未提及", null));
                    completeness = Math.min(completeness, 12);
                }
            }
        }

        // ---------- 段2：时间线自洽（结构字段级区间重叠）——HARD ----------
        List<String> overlaps = findTimelineOverlaps(draft);
        int timeConflict = Math.max(0, 20 - 5 * overlaps.size());
        if (overlaps.isEmpty()) {
            verified.add("时间线自洽：各日时段无重叠");
        } else {
            seg2Issues.addAll(overlaps);
            overlaps.forEach(o -> violations.add(
                    new Violation(RULE_TIMELINE_OVERLAP, 2, Severity.HARD, o, null)));
        }

        // ---------- 段3：预算 + 偏好（预算贴线/偏好部分覆盖为 SOFT，其余 HARD） ----------
        int costBudget = 20;
        if (draft.budget() != null && !draft.budget().breakdown().isEmpty()) {
            validateBudgetFacts(draft.budget(), violations, seg3Issues, verified);
        }
        if (intakeOpt.isPresent() && draft.budget() != null) {
            var budgetConstraint = intakeOpt.get().effectiveBudgetConstraint();
            String budgetLimit = budgetConstraint == null ? valueOf(intakeOpt.get().fields().budget())
                    : budgetConstraint.rawText();
            String estimate = draft.budget().totalEstimate();
            Double limit = budgetConstraint != null && budgetConstraint.max() != null
                    ? budgetConstraint.max().doubleValue() : maxNumber(budgetLimit);
            Double est = maxNumber(estimate);
            if (limit == null || est == null || limit <= 0) {
                // R1：预算无法核对从「静默保守放行」改为可见软提示
                violations.add(new Violation(RULE_BUDGET_UNPARSEABLE, 3, Severity.SOFT,
                        "预算无法核对（limit=" + budgetLimit + ", estimate=" + estimate + "），按保守分放行",
                        "可让规划重新产出带数字的预算汇总"));
                costBudget = 12;
            } else {
                double deviation = Math.abs(est - limit) / limit;
                if (deviation <= 0.10) {
                    verified.add("总费用 " + est + " vs 预算 " + limit + "，偏差在 ±10% 内");
                } else {
                    String issueText = "预算超支：估算 " + est + " vs 预算上限 " + limit
                            + "（偏差 " + (int) (deviation * 100) + "%）";
                    seg3Issues.add(issueText);
                    if (deviation > 0.15) {
                        violations.add(new Violation(RULE_BUDGET_DEVIATION, 3, Severity.HARD, issueText, null));
                    } else {
                        violations.add(new Violation(RULE_BUDGET_DEVIATION, 3, Severity.SOFT, issueText,
                                "偏差在 10%~15% 贴线区间，可按当前预算交付或让规划调整住宿/交通档位"));
                    }
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
                List<String> uncovered = new ArrayList<>();
                for (String pref : prefs) {
                    if (ItineraryAssembler.preferenceCovered(pref, haystack)) {
                        covered++;
                        verified.add("偏好「" + pref + "」已被行程覆盖");
                    } else {
                        seg3Issues.add("偏好「" + pref + "」未在行程中体现（未覆盖且未显式披露取舍）");
                        uncovered.add(pref);
                    }
                }
                poiReasonableness = 20 * covered / prefs.size();
                if (!uncovered.isEmpty()) {
                    if (covered == 0) {
                        violations.add(new Violation(RULE_PREFERENCE_COVERAGE, 3, Severity.HARD,
                                "全部 " + prefs.size() + " 项偏好均未覆盖：" + String.join("、", uncovered), null));
                    } else {
                        violations.add(new Violation(RULE_PREFERENCE_COVERAGE, 3, Severity.SOFT,
                                "偏好部分未覆盖：" + String.join("、", uncovered),
                                "可在后续轮次让规划补排对应类型景点"));
                    }
                }
            }
        }

        // ---------- 可行性：unplaced 溢出（≥2 HARD；=1 带取舍说明 SOFT）+ 防幻觉（HARD） ----------
        int feasibility = 20;
        if (routePlanOpt.isPresent()) {
            List<RoutePlan.UnplacedPoi> unplacedList = routePlanOpt.get().solverMeta().unplaced();
            int unplaced = unplacedList == null ? 0 : unplacedList.size();
            feasibility = Math.max(0, 20 - 4 * unplaced);
            if (unplaced == 0) {
                verified.add("路线求解无溢出（全部 POI 排入）");
            } else {
                String unplacedDetails = unplacedList.stream()
                        .map(p -> p.name() + "（" + p.reason().name() + "）")
                        .collect(java.util.stream.Collectors.joining("、"));
                seg1Issues.add("路线求解有 " + unplaced + " 个 POI 未排入：" + unplacedDetails);
                if (unplaced >= 2) {
                    violations.add(new Violation(RULE_UNPLACED_OVERFLOW, 1, Severity.HARD,
                            unplaced + " 个 POI 未排入路线：" + unplacedDetails, null));
                } else {
                    RoutePlan.UnplacedPoi p = unplacedList.get(0);
                    String reason = p.reason() == null ? "未说明" : p.reason().name();
                    violations.add(new Violation(RULE_UNPLACED_TRADEOFF, 1, Severity.SOFT,
                            "「" + p.name() + "」未排入路线（" + reason + "），按取舍交付",
                            "如需纳入可让规划调整候选或延长行程"));
                }
            }
        } else {
            feasibility = 12;
            // R1：路线产物缺失从「静默保守分」改为可见软提示
            violations.add(new Violation(RULE_ROUTE_PLAN_MISSING, 1, Severity.SOFT,
                    "路线产物缺失，可行性按保守分 12/20 评估", null));
        }
        // 防幻觉（报告模式）：候选池外 POI 检出即扣可行性 + 记段1（HARD：事实错误，交付即欺诈）
        if (shortlistOpt.isPresent()) {
            Set<String> known = new HashSet<>();
            shortlistOpt.get().pois().forEach(p -> known.add(p.name()));
            Set<String> nonPoiNodes = routePlanOpt.map(ItineraryAssembler::nonPoiNodeNames)
                    .orElseGet(Set::of);
            if (!known.isEmpty()) {
                for (DraftDay day : draft.days()) {
                    for (DraftItem item : day.items()) {
                        if (!ItineraryAssembler.isNonPoiItem(item)
                                && !ItineraryAssembler.routeNodeMentioned(item, nonPoiNodes)
                                && !ItineraryAssembler.poiMentioned(item, known)) {
                            String issueText = "Day" + day.day() + "「" + item.activity() + "/" + item.location()
                                    + "」不在已核验 POI 清单（疑似幻觉）";
                            seg1Issues.add(issueText);
                            violations.add(new Violation(RULE_POI_HALLUCINATION, 1, Severity.HARD,
                                    issueText, null));
                            feasibility = Math.max(0, feasibility - 5);
                        }
                    }
                }
                if (seg1Issues.stream().noneMatch(i -> i.contains("疑似幻觉"))) {
                    verified.add("全部景点项均来自已核验 POI 清单");
                }
            }
        }

        // ---------- R1 三级裁决（评分照常计算落产物，门控只看违例级别） ----------
        boolean anyHard = violations.stream().anyMatch(v -> v.severity() == Severity.HARD);
        Outcome.Verdict verdict = anyHard ? Outcome.Verdict.FAIL
                : violations.isEmpty() ? Outcome.Verdict.PASS : Outcome.Verdict.PASS_WITH_WARNINGS;
        List<ReviewPassed.ReviewWarning> warnings = violations.stream()
                .filter(v -> v.severity() == Severity.SOFT)
                .map(v -> new ReviewPassed.ReviewWarning(v.rule(), v.detail(), v.suggestion()))
                .toList();

        boolean seg1Pass = seg1Issues.isEmpty();
        boolean seg2Pass = seg2Issues.isEmpty();
        boolean seg3Pass = seg3Issues.isEmpty();
        int total = completeness + feasibility + timeConflict + costBudget + poiReasonableness;
        int minDim = Math.min(completeness, Math.min(feasibility,
                Math.min(timeConflict, Math.min(costBudget, poiReasonableness))));

        String now = java.time.LocalDateTime.now().format(FMT);
        List<String> issues = new ArrayList<>();
        issues.addAll(seg1Issues);
        issues.addAll(seg2Issues);
        issues.addAll(seg3Issues);

        if (verdict != Outcome.Verdict.FAIL) {
            // PASS / PASS_WITH_WARNINGS 均写 review_passed（「存在即通过」不变量），后者带软提示
            taskWorkspace.writeReviewPassed(userId, sessionId,
                    new ReviewPassed(total, minDim, verified, warnings, now));
            if (verdict == Outcome.Verdict.PASS_WITH_WARNINGS) {
                log.info("规则质检通过（含软提示，带 warning 交付零回炉）: 用户={}, 会话={}, 总分={}, 软提示={}",
                        userId, sessionId, total,
                        warnings.stream().map(ReviewPassed.ReviewWarning::rule).toList());
            } else {
                log.info("规则质检通过: 用户={}, 会话={}, 总分={}, 检查项={}", userId, sessionId, total, verified.size());
            }
        } else {
            // review_passed.json 粘滞：已被通过的产物不被规则失败覆盖
            if (taskWorkspace.readReviewPassed(userId, sessionId).isPresent()) {
                log.info("规则质检未通过但 review_passed.json 已存在（粘滞保留）: 用户={}, 会话={}", userId, sessionId);
                return new Outcome(Outcome.Verdict.PASS, List.of(), List.of(), null);
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
        return new Outcome(verdict, issues, warnings,
                verdict == Outcome.Verdict.FAIL
                        ? buildRevisionTask(userId, sessionId, issues, intakeOpt.orElse(null), draft) : null);
    }

    /** 规则失败时给 reviewer 的定向修订任务文本（内联 intake/草案正文，首轮免 read_file） */
    private String buildRevisionTask(String userId, String sessionId, List<String> issues,
                                     IntakeResult intake, ItineraryDraft draft) {
        StringBuilder sb = new StringBuilder();
        sb.append("sessionId: ").append(sessionId).append('\n');
        sb.append("【系统规则质检发现以下问题，请定向修订】\n");
        for (String issue : issues) {
            sb.append("- ").append(issue).append('\n');
        }
        sb.append("评审输入已内联在本说明末尾两节（与协作目录文件一致），直接解析，禁止首轮 read_file；")
                .append("按你的修订流程就地修复（POI/偏好问题调 revise_poi_selection，路线问题调 plan_route，")
                .append("预算问题调 searchHotels）；注意：任何修订动作后内联草案即过期，")
                .append("复审前必须 read_file 重读 tasks/").append(sessionId)
                .append("/itinerary_draft.json。修订收敛后照常写 review_passed.json 或 review_report.json。");
        sb.append("\n\n【intake_result.json】\n")
                .append(intake == null ? "（缺失）" : com.alibaba.fastjson2.JSON.toJSONString(intake));
        sb.append("\n\n【itinerary_draft.json】\n")
                .append(com.alibaba.fastjson2.JSON.toJSONString(draft));
        return sb.toString();
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
    private static void validateBudgetFacts(ItineraryDraft.Budget budget, List<Violation> violations,
                                            List<String> issues, List<String> verified) {
        BigDecimal sum = BigDecimal.ZERO;
        boolean complete = true;
        for (ItineraryDraft.BudgetItem item : budget.breakdown()) {
            BigDecimal amount = firstDecimal(item.amount());
            if (amount == null) {
                complete = false;
                continue;
            }
            sum = sum.add(amount);
            String note = item.note();
            if (note == null || (!note.contains("来源=") && !note.contains("估算") && !note.contains("待确认"))) {
                String detail = "预算分项缺少价格来源或估算标记: " + item.category();
                issues.add(detail);
                violations.add(new Violation(RULE_PRICE_SOURCE_MISSING, 3, Severity.HARD, detail,
                        "补充来源=平台，或明确标注估算/待确认"));
            }
        }
        BigDecimal total = firstDecimal(budget.totalEstimate());
        if (complete && total != null && sum.compareTo(total) != 0) {
            String detail = "预算算术不一致: 分项合计 " + sum + "，总额 " + total;
            issues.add(detail);
            violations.add(new Violation(RULE_BUDGET_ARITHMETIC_MISMATCH, 3, Severity.HARD, detail,
                    "用分项金额机械求和生成总额"));
        } else if (complete && total != null) {
            verified.add("预算分项可机械复算，总额=" + total);
        }
    }

    private static BigDecimal firstDecimal(String text) {
        if (text == null) return null;
        Matcher matcher = NUMBER.matcher(text);
        return matcher.find() ? new BigDecimal(matcher.group()) : null;
    }

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
