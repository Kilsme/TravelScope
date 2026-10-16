package com.travelscope.service.route;

import com.travelscope.service.route.dto.PoiCandidate;
import com.travelscope.service.route.dto.RouteConstraints;
import com.travelscope.service.route.dto.RouteSolveResult;

import java.util.List;

/**
 * 路线规划服务（v4 计算代码化：原 route-optimizer Agent 的确定性替代，零 LLM）。
 * <p>
 * 问题建模为小规模 VRPTW（带时间窗车辆路径问题），语义映射：
 * </p>
 * <ul>
 *   <li><b>车辆 = 天</b>：constraints.days 辆车，每日一辆，起点/终点为酒店（提供时）；
 *       对应 OR-Tools 的 numVehicles 与 Start/End</li>
 *   <li><b>时间矩阵 = transit callback</b>：{@code TimeMatrix} 提供节点间实测通勤时长
 *       （高德路径规划 API，由 CommuteMatrixBuilder 构建），目标函数为总通勤最小</li>
 *   <li><b>时间窗 = 开放时间</b>：PoiCandidate.openStart/openClose，对应 CumulVar.SetRange；
 *       预计到达须落在窗内</li>
 *   <li><b>slack = 转场缓冲</b>：transferBufferMin 允许等待/缓冲，对应 AddDimension 的 slack</li>
 *   <li><b>容量 = 单日通勤上限</b>：maxDailyCommuteMin，对应维度容量约束</li>
 * </ul>
 * <p>
 * 规模 6~16 POI、2~5 天下，默认实现（最近邻构造 + 2-opt 局部优化，见
 * RoutePlannerServiceImpl）毫秒级可解且质量足够。<b>升级路径</b>：POI 规模上去后，
 * 可将实现替换为 OR-Tools {@code RoutingModel}（py/ortools 同款约束求解），
 * 本接口与其 DTO 契约不变——接口即升级点（docs/TravelScope重构计划-v4.md §5.2）。
 * </p>
 */
public interface RoutePlannerService {

    /**
     * 求解分日路线。
     *
     * @param pois       POI 候选（坐标须已补齐；缺失坐标的由构建矩阵前 geocode 处理）
     * @param constraints 求解约束（天数/日间窗/通勤上限/缓冲/酒店/通勤方式偏好）
     * @return 分日有序结果 + 未排入项（带原因）+ 求解元信息；不抛业务异常
     *         （装不下的 POI 进 unplaced 而非失败）
     */
    RouteSolveResult solve(List<PoiCandidate> pois, RouteConstraints constraints);
}
