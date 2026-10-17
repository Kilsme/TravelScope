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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ForkJoinTask;

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
    /** 纯函数组件：无状态线程安全，直接实例化（同包风格） */
    private final DaySplitter daySplitter = new DaySplitter();
    private final TwoOptImprover twoOptImprover = new TwoOptImprover();
    private final TimelineScheduler timelineScheduler = new TimelineScheduler();

    public RoutePlannerServiceImpl(CommuteMatrixBuilder commuteMatrixBuilder,
                                   TaskWorkspace taskWorkspace) {
        this.commuteMatrixBuilder = commuteMatrixBuilder;
        this.taskWorkspace = taskWorkspace;
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
        List<PoiCandidate> buildInput = new ArrayList<>();
        if (hotelAnchor != null) {
            buildInput.add(hotelAnchor);
        }
        buildInput.addAll(pois);
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
     * CollabFileWriter 强制）。
     *
     * @return 落盘的 RoutePlan（与磁盘内容一致）
     */
    public RoutePlan planAndWrite(String userId, String sessionId, List<PoiCandidate> pois,
                                  RouteConstraints constraints, String city) {
        RouteSolveResult result = solve(pois, constraints, city);
        // RouteDay.date 置 null：日历日期在需求侧（startDate），S2 接线时由上游补
        List<RoutePlan.RouteDay> days = result.days().stream()
                .map(d -> new RoutePlan.RouteDay(d.day(), null,
                        d.pois().stream()
                                .map(sp -> new RoutePlan.RoutePoi(sp.candidate().name(),
                                        sp.candidate().lng(), sp.candidate().lat(),
                                        sp.startTime(), sp.endTime(), sp.transitFromPrev(),
                                        sp.commuteMinutesFromPrev()))
                                .toList(),
                        d.commuteMinutesTotal()))
                .toList();
        RoutePlan plan = new RoutePlan(days, new RoutePlan.SolverMeta(
                result.solverMeta().algorithm(), result.solverMeta().matrixSource(),
                result.solverMeta().durationMs(),
                result.unplaced().stream()
                        .map(u -> new RoutePlan.UnplacedPoi(u.name(), u.reason().name()))
                        .toList()));
        taskWorkspace.writeRoutePlan(userId, sessionId, plan);
        log.info("route_plan.json 已落盘: userId={}, sessionId={}, days={}, unplaced={}",
                userId, sessionId, days.size(), plan.solverMeta().unplaced().size());
        return plan;
    }

    /** 单日：2-opt 改进 → 时段顺排 → RouteDay（纯函数，ForkJoin 任务体内线程安全） */
    private RouteSolveResult.RouteDay planOneDay(DaySplitter.DayAcc day, TimeMatrix matrix,
                                                 RouteConstraints constraints) {
        TwoOptImprover.Improvement improvement =
                twoOptImprover.improve(day.sequence(), matrix, constraints);
        TimelineScheduler.DayTimeline timeline =
                timelineScheduler.schedule(improvement.sequence(), matrix, constraints);
        log.info("day {} 完成: items={}, 2opt改进={}s, 通勤={}min",
                day.dayNumber(), timeline.pois().size(), improvement.improvementSec(),
                timeline.commuteMinutesTotal());
        return new RouteSolveResult.RouteDay(day.dayNumber(), timeline.pois(),
                timeline.commuteMinutesTotal());
    }
}
