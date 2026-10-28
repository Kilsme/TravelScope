package com.travelscope.service.route;

import com.travelscope.collab.RoutePlan;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.service.route.dto.PoiCandidate;
import com.travelscope.service.route.dto.RouteConstraints;
import com.travelscope.service.route.dto.RouteSolveResult;
import com.travelscope.service.route.dto.TimeMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ForkJoinTask;
import java.util.stream.Collectors;

/**
 * 路线求解实现（S1.5，v4 计算代码化主线收口）：串起
 * {@link CommuteMatrixBuilder}（S1.2 矩阵）→ {@link DaySplitter}（S1.3 分日）→
 * {@link TwoOptImprover}（S1.4 局部优化）→ {@link TimelineScheduler}（时段顺排），
 * 全程零 LLM。
 * <p>
 * 每日的 2-opt 改进与时段顺排<b>按天并行提交 ForkJoinPool</b>（CPU 密集走任务
 * 窃取线程池——用户指定的线程池约定；每日一个 fork 任务、join 汇合）；矩阵
 * 构建是 IO 密集（高德 API），其内部走虚拟线程，与本处的 CPU 并行互不混杂。
 * </p>
 * <p>
 * 无坐标 POI 在矩阵构建（含 geocode 补齐）后仍缺坐标的，不进分日，按
 * MATRIX_MISSING 记入 unplaced——无坐标即无法建立通勤关系，也防止孤岛日落位
 * 后 RoutePoi 坐标必填校验失败。空天保留在 days（编号完整，呈现由渲染层决定）。
 * </p>
 */
@Service
public class RoutePlannerServiceImpl implements RoutePlannerService {

    private static final Logger log = LoggerFactory.getLogger(RoutePlannerServiceImpl.class);

    private final CommuteMatrixBuilder commuteMatrixBuilder;
    private final TaskWorkspace taskWorkspace;
    private final RouteWindowPolicyApplier windowPolicyApplier;
    /** 纯函数组件：无状态线程安全，直接实例化（同包风格） */
    private final DaySplitter daySplitter = new DaySplitter();
    private final TwoOptImprover twoOptImprover = new TwoOptImprover();
    private final TimelineScheduler timelineScheduler = new TimelineScheduler();

    public RoutePlannerServiceImpl(CommuteMatrixBuilder commuteMatrixBuilder,
                                   TaskWorkspace taskWorkspace) {
        this(commuteMatrixBuilder, taskWorkspace, city -> java.time.LocalTime.of(18, 0));
    }

    /** 测试/后续日照服务注入入口。 */
    public RoutePlannerServiceImpl(CommuteMatrixBuilder commuteMatrixBuilder,
                                   TaskWorkspace taskWorkspace,
                                   SunTimeProvider sunTimeProvider) {
        this.commuteMatrixBuilder = commuteMatrixBuilder;
        this.taskWorkspace = taskWorkspace;
        this.windowPolicyApplier = new RouteWindowPolicyApplier(sunTimeProvider);
    }

    @Override
    public RouteSolveResult solve(List<PoiCandidate> pois, RouteConstraints constraints,
                                  String city) {
        Objects.requireNonNull(pois, "pois");
        Objects.requireNonNull(constraints, "constraints");
        if (city == null && constraints.preferTransit()) {
            throw new IllegalArgumentException(
                    "公交档（preferTransit=true）必须提供 city，否则通勤矩阵无法构建");
        }
        long startNano = System.nanoTime();

        // ① 通勤矩阵：酒店锚由本服务作为节点 0 加入（与 DaySplitter 的锚构造一致，
        //    矩阵节点顺序 [酒店?, ...POI] 与 DaySplitter 内部节点表对齐）
        PoiCandidate hotelAnchor = constraints.hotelName() != null
                ? new PoiCandidate(constraints.hotelName(), constraints.hotelLng(),
                        constraints.hotelLat(), null, null, null, null, "hotel-anchor")
                : null;
        List<PoiCandidate> effectivePois = pois.stream()
                .map(poi -> windowPolicyApplier.apply(poi, constraints, city)).toList();
        List<PoiCandidate> buildInput = new ArrayList<>();
        if (hotelAnchor != null) {
            buildInput.add(hotelAnchor);
        }
        buildInput.addAll(effectivePois);
        MatrixBuildOutcome outcome = commuteMatrixBuilder.build(
                buildInput, city, constraints.preferTransit());
        TimeMatrix matrix = outcome.matrix();
        log.info("路线求解开始: nodes={}（含酒店锚={}）, matrixCacheHit={}, missingPairs={}",
                buildInput.size(), hotelAnchor != null, outcome.cacheHit(),
                outcome.missingPairs().size());

        // ② 坐标仍缺失的 POI 不进分日，按矩阵缺失记入 unplaced
        List<RouteSolveResult.UnplacedPoi> unplaced = new ArrayList<>();
        List<PoiCandidate> resolvedPois = new ArrayList<>();
        for (PoiCandidate node : outcome.nodes()) {
            if (hotelAnchor != null && node.name().equals(constraints.hotelName())) {
                continue;  // 酒店锚不参与分日
            }
            if (node.lng() == null || node.lat() == null) {
                log.warn("POI 坐标补齐失败，按矩阵缺失记入 unplaced: {}", node.name());
                unplaced.add(new RouteSolveResult.UnplacedPoi(node.name(),
                        RouteSolveResult.UnplacedReason.MATRIX_MISSING));
            } else {
                resolvedPois.add(node);
            }
        }

        // ③ 最近邻分日
        DaySplitter.SplitOutcome split = daySplitter.split(resolvedPois, matrix, constraints);
        unplaced.addAll(split.unplaced());
        log.info("分日完成: days={}, 每日项数={}, unplaced={}",
                split.days().size(),
                split.days().stream().map(d -> d.nodeIndices().size()).toList(),
                unplaced.size());

        // ④ 每日并行：2-opt 改进 + 时段顺排（ForkJoinPool 任务窃取，每日一个 fork 任务）
        List<ForkJoinTask<RouteSolveResult.RouteDay>> forks = new ArrayList<>();
        for (DaySplitter.DayAcc day : split.days()) {
            forks.add(ForkJoinTask.adapt(() -> planOneDay(day, matrix, constraints)));
        }
        forks.forEach(ForkJoinTask::fork);
        List<RouteSolveResult.RouteDay> days = new ArrayList<>(forks.size());
        for (ForkJoinTask<RouteSolveResult.RouteDay> fork : forks) {
            days.add(fork.join());
        }

        long durationMs = (System.nanoTime() - startNano) / 1_000_000;
        log.info("路线求解完成: days={}, unplaced={}, durationMs={}",
                days.size(), unplaced.size(), durationMs);
        return new RouteSolveResult(days, List.copyOf(unplaced),
                new RouteSolveResult.SolverMeta("NN+2opt",
                        constraints.preferTransit() ? "amap-transit" : "amap-driving",
                        durationMs));
    }

    /**
     * 求解并落盘 route_plan.json（单一生产者 = 本服务；文件名→DTO 绑定由
     * CollabFileWriter 强制）。逐天记录输入指纹（R2 增量轮的复用判据）。
     *
     * @return 落盘的 RoutePlan（与磁盘内容一致）
     */
    public RoutePlan planAndWrite(String userId, String sessionId, List<PoiCandidate> pois,
                                  RouteConstraints constraints, String city) {
        RouteSolveResult result = solve(pois, constraints, city);
        RoutePlan plan = toPlan(result, constraints);
        taskWorkspace.writeRoutePlan(userId, sessionId, plan);
        log.info("route_plan.json 已落盘: userId={}, sessionId={}, days={}, unplaced={}",
                userId, sessionId, plan.days().size(), plan.solverMeta().unplaced().size());
        return plan;
    }

    /** D3：交通主方案仅收紧首末日窗口并附加固定节点，不改变 POI 求解策略。 */
    public RoutePlan planAndWriteWithTransport(String userId, String sessionId, List<PoiCandidate> pois,
                                               RouteConstraints constraints, String city,
                                               com.travelscope.collab.TransportSelection selection,
                                               int exitBufferMin, int arrivalTransferMin,
                                               int luggageMin, int stationTransferMin,
                                               int securityBufferMin) {
        TransportRouteBoundary boundary = TransportRouteBoundary.from(selection, constraints,
                exitBufferMin, arrivalTransferMin, luggageMin, stationTransferMin, securityBufferMin);
        RouteSolveResult result = solve(pois, boundary.constraints(), city);
        RoutePlan base = toPlan(result, boundary.constraints());
        List<RoutePlan.RouteDay> days = base.days().stream().map(day ->
                new RoutePlan.RouteDay(day.day(), day.date(), day.pois(),
                        boundary.nodesForDay(day.day(), base.days().size(), day.nodes()),
                        day.commuteMinutesTotal(), day.inputFingerprint())).toList();
        RoutePlan plan = new RoutePlan(days, base.solverMeta());
        taskWorkspace.writeRoutePlan(userId, sessionId, plan);
        return plan;
    }

    /**
     * R2 增量落盘：全量求解（矩阵经对级缓存增量补查）后，按天级输入指纹匹配旧产物——
     * POI 集合全等的天输入未变 ⟹ 矩阵值相同 ⟹ 旧解仍有效，字节级复用旧 RouteDay
     * （时段/顺序/通勤总分钟原样，仅天号取新位）；未命中天用新解。复用数为 0 时
     * 与全量 {@link #planAndWrite} 等价（安全退化，无变更数量门控）。
     * <p>
     * 复用条件是集合全等而非相似——防「看到已有进展过早宣布完成」；约束沿用
     * intake 派生默认值（revise 链不传覆盖参数，与旧解同约束系）。
     * </p>
     */
    public IncrementalOutcome planAndWriteIncremental(String userId, String sessionId,
                                                      List<PoiCandidate> pois,
                                                      RouteConstraints constraints, String city,
                                                      RoutePlan previous) {
        long startNano = System.nanoTime();
        RouteSolveResult result = solve(pois, constraints, city);
        // 旧天指纹映射（存量产物无 fingerprint 字段 → 按 RoutePoi 现算，兼容）
        Map<String, RoutePlan.RouteDay> oldByFingerprint = new HashMap<>();
        for (RoutePlan.RouteDay d : previous.days()) {
            oldByFingerprint.putIfAbsent(fingerprintOf(d.pois()), d);
        }
        List<RoutePlan.RouteDay> days = new ArrayList<>();
        Set<Integer> reusedDays = new LinkedHashSet<>();
        Set<Integer> changedDays = new LinkedHashSet<>();
        for (RouteSolveResult.RouteDay d : result.days()) {
            List<RoutePlan.RoutePoi> newPois = toRoutePois(d);
            String fingerprint = fingerprintOf(newPois);
            RoutePlan.RouteDay old = oldByFingerprint.get(fingerprint);
            if (old != null) {
                days.add(new RoutePlan.RouteDay(d.day(), old.date(), old.pois(), old.nodes(),
                        old.commuteMinutesTotal(), fingerprint));
                reusedDays.add(d.day());
            } else {
                days.add(new RoutePlan.RouteDay(d.day(), null, newPois,
                        d.commuteMinutesTotal(), fingerprint));
                changedDays.add(d.day());
            }
        }
        RoutePlan plan = new RoutePlan(days, toSolverMeta(result));
        taskWorkspace.writeRoutePlan(userId, sessionId, plan);
        log.info("route_plan.json 增量落盘: route_incremental_reused_days={}/{}, 变化天={}, unplaced={}, durationMs={}",
                reusedDays.size(), days.size(), changedDays,
                plan.solverMeta().unplaced().size(), (System.nanoTime() - startNano) / 1_000_000);
        return new IncrementalOutcome(plan, reusedDays, changedDays);
    }

    /** 增量落盘结论：plan + 复用天/变化天编号（revise 链据此定重组装范围并向 reviewer 披露） */
    public record IncrementalOutcome(RoutePlan plan, Set<Integer> reusedDays, Set<Integer> changedDays) {
    }

    /** 求解结果 → 落盘契约（逐天记录输入指纹） */
    private static RoutePlan toPlan(RouteSolveResult result, RouteConstraints constraints) {
        List<RoutePlan.RouteDay> days = new ArrayList<>();
        for (RouteSolveResult.RouteDay d : result.days()) {
            List<RoutePlan.RoutePoi> pois = toRoutePois(d);
            List<RoutePlan.RouteNode> nodes = pois.stream().map(RoutePlan.RouteNode::fromPoi).toList();
            if (constraints.hotelName() != null) {
                java.util.ArrayList<RoutePlan.RouteNode> anchored = new java.util.ArrayList<>();
                anchored.add(hotelNode(constraints, true));
                anchored.addAll(nodes);
                anchored.add(hotelNode(constraints, false));
                nodes = List.copyOf(anchored);
            }
            days.add(new RoutePlan.RouteDay(d.day(), null, pois, nodes, d.commuteMinutesTotal(),
                    fingerprintOf(pois)));
        }
        return new RoutePlan(days, toSolverMeta(result));
    }

    private static RoutePlan.RouteNode hotelNode(RouteConstraints constraints, boolean checkin) {
        return new RoutePlan.RouteNode(constraints.hotelName(),
                checkin ? RoutePlan.NodeType.HOTEL_CHECKIN : RoutePlan.NodeType.HOTEL_CHECKOUT,
                true, constraints.hotelLng(), constraints.hotelLat(),
                checkin ? constraints.dailyStart() : constraints.dailyEnd(),
                checkin ? constraints.dailyStart() : constraints.dailyEnd(),
                null, null, checkin ? "每日从主酒店出发" : "每日返回主酒店");
    }

    private static RoutePlan.SolverMeta toSolverMeta(RouteSolveResult result) {
        return new RoutePlan.SolverMeta(
                result.solverMeta().algorithm(), result.solverMeta().matrixSource(),
                result.solverMeta().durationMs(),
                result.unplaced().stream()
                        .map(u -> new RoutePlan.UnplacedPoi(u.name(), u.reason().name()))
                        .toList());
    }

    private static List<RoutePlan.RoutePoi> toRoutePois(RouteSolveResult.RouteDay d) {
        return d.pois().stream()
                .map(sp -> new RoutePlan.RoutePoi(sp.candidate().name(), sp.candidate().lng(),
                        sp.candidate().lat(), sp.startTime(), sp.endTime(), sp.transitFromPrev(),
                        sp.commuteMinutesFromPrev(), sp.candidate().scheduleNote()))
                .toList();
    }

    /** R2 天级输入指纹：当日 POI 排序后的 name@坐标 SHA-256 前 16 位（与矩阵 hash 同风格） */
    static String fingerprintOf(List<RoutePlan.RoutePoi> pois) {
        String material = pois.stream()
                .map(p -> p.name() + "@" + p.lng() + "," + p.lat())
                .sorted()
                .collect(Collectors.joining("|"));
        return sha256Hex(material).substring(0, 16);
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 单日：2-opt 改进 → 时段顺排 → RouteDay（纯函数，ForkJoin 任务体内线程安全） */
    private RouteSolveResult.RouteDay planOneDay(DaySplitter.DayAcc day, TimeMatrix matrix,
                                                 RouteConstraints constraints) {
        RouteConstraints dayConstraints = constraints.withDailyWindow(
                constraints.startForDay(day.dayNumber()), constraints.endForDay(day.dayNumber()));
        TwoOptImprover.Improvement improvement =
                twoOptImprover.improve(day.sequence(), matrix, dayConstraints);
        TimelineScheduler.DayTimeline timeline =
                timelineScheduler.schedule(improvement.sequence(), matrix, dayConstraints);
        log.info("day {} 完成: items={}, 2opt改进={}s, 通勤={}min",
                day.dayNumber(), timeline.pois().size(), improvement.improvementSec(),
                timeline.commuteMinutesTotal());
        return new RouteSolveResult.RouteDay(day.dayNumber(), timeline.pois(),
                timeline.commuteMinutesTotal());
    }
}
