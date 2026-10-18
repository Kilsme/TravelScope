package com.travelscope.service.route;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.travelscope.agent.tools.TransportTool;
import com.travelscope.service.TaskResultCache;
import com.travelscope.service.route.dto.PoiCandidate;
import com.travelscope.service.route.dto.TimeMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

/**
 * 通勤矩阵构建器（S1.2）：调高德路径规划实测两两通勤时长，构建 {@link TimeMatrix}。
 * <p>
 * 关键设计：
 * <ul>
 *   <li><b>逐段交通方式</b>：haversine 直线距离 &lt; 2km 走驾车档（西湖→雷峰塔 1.5km 该打车），
 *       ≥ 2km 走公交（酒店→灵隐寺 12km 该公交）；preferTransit=false 时全程驾车档</li>
 *   <li><b>并发与限流</b>：两两组合用虚拟线程并行（IO 密集约定），信号量上限 8；
 *       高德个人 key QPS 约 3/s（S1.9 冒烟实测 8 并发 5/8 被 CUQPS 限流）——调用
 *       起步全局节流 ~350ms/次，偶发限流整对重试一次（600ms）；双模式均失败置 -1
 *       并记入 missingPairs（不中断）</li>
 *   <li><b>缓存</b>：矩阵整体存 TaskResultCache（MATRIX 类型，TTL 30min），
 *       key = taskresult:global:routematrix:{sha256前16位}:{mode}:matrix——全局共享、
 *       按 POI 集合分槽；geocode 结果缓存 GEO 类型（TTL 24h，坐标极少变化）</li>
 *   <li>全程零 LLM；API 调用 ≤ n(n-1)/2（单向查询 + 对称填充）</li>
 * </ul>
 * </p>
 */
@Service
public class CommuteMatrixBuilder {

    private static final Logger log = LoggerFactory.getLogger(CommuteMatrixBuilder.class);

    /** 并发上限（虚拟线程任务数） */
    private static final int MAX_PARALLEL = 8;

    /** 高德方向 API QPS 节流（2026-10-17 S1.9 冒烟实测：个人 key 约 3/s——8 并发
     *  5/8 被 CUQPS_HAS_EXCEEDED_THE_LIMIT 限流，21 对矩阵静默缺 5 对）：调用起步
     *  最小间隔 ~350ms（≈2.9/s），全局限流，与任务并发数无关 */
    private static final long CALL_MIN_INTERVAL_NANOS = 350_000_000L;

    /** 整对重试延迟（兜残余偶发限流；真不可达的负结果最终仍置 -1，行为不变） */
    private static final long RETRY_DELAY_NANOS = 600_000_000L;

    /** QPS 节流门闩与最近一次调用起步时刻（实例级——构建器为单例 bean） */
    private final Object rateGate = new Object();
    private long lastCallAtNanos = 0;

    /** 直线距离阈值（km）：低于此值走驾车档（近似打车/短驳） */
    private static final double DRIVING_THRESHOLD_KM = 2.0;

    /** 全局缓存作用域（矩阵与坐标跨会话共享） */
    private static final String GLOBAL_SCOPE = "global";

    /** 调用起步最小间隔（纳秒；mock 传输层的测试可经三参构造传 0 免等待） */
    private final long callMinIntervalNanos;

    private final TransportTool transportTool;
    private final TaskResultCache taskResultCache;

    /** Spring 装配入口（类内另有包私有三参构造，须显式标注，否则容器找不到默认构造器） */
    @org.springframework.beans.factory.annotation.Autowired
    public CommuteMatrixBuilder(TransportTool transportTool, TaskResultCache taskResultCache) {
        this(transportTool, taskResultCache, CALL_MIN_INTERVAL_NANOS);
    }

    /** 测试/定制注入：节流间隔可覆盖（mock 传输层时传 0，免无谓等待） */
    public CommuteMatrixBuilder(TransportTool transportTool, TaskResultCache taskResultCache,
                         long callMinIntervalNanos) {
        this.transportTool = transportTool;
        this.taskResultCache = taskResultCache;
        this.callMinIntervalNanos = callMinIntervalNanos;
    }

    /**
     * 构建通勤时间矩阵。
     *
     * @param pois          节点列表（酒店由调用方作为节点加入；坐标缺失的先 geocode 补齐）
     * @param city          公交查询所在城市（transit 必填，由调用方从目的地传入）
     * @param preferTransit true=逐段按距离选方式；false=全程驾车档
     * @return 矩阵 + 缺失对 + 调用统计
     */
    public MatrixBuildOutcome build(List<PoiCandidate> pois, String city, boolean preferTransit) {
        long start = System.nanoTime();
        List<PoiCandidate> nodes = resolveCoordinates(pois);
        String mode = preferTransit ? "transit" : "driving";
        String scope = "routematrix:" + matrixHash(nodes) + ":" + mode;

        // 矩阵级缓存：命中直接返回（零 API 调用）；nodes 用本次已补齐坐标的节点表
        String cached = taskResultCache.get(GLOBAL_SCOPE, scope, TaskResultCache.TaskType.MATRIX);
        if (cached != null) {
            TimeMatrix hit = matrixFromJson(cached);
            if (hit != null) {
                log.info("通勤矩阵缓存命中: nodes={}, hash={}, apiCalls=0", nodes.size(), scope);
                return new MatrixBuildOutcome(hit, nodes, List.of(), 0, true);
            }
        }

        int n = nodes.size();
        long[][] durations = new long[n][n];
        List<String> missing = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger apiCalls = new AtomicInteger();
        AtomicInteger retriedPairs = new AtomicInteger();
        Semaphore permits = new Semaphore(MAX_PARALLEL);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    final int a = i;
                    final int b = j;
                    futures.add(CompletableFuture.runAsync(() -> {
                        try {
                            permits.acquire();
                            try {
                                long sec = queryPairSeconds(nodes.get(a), nodes.get(b), city,
                                        preferTransit, missing, retriedPairs);
                                apiCalls.incrementAndGet();
                                durations[a][b] = sec;
                                durations[b][a] = sec;
                            } finally {
                                permits.release();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            durations[a][b] = -1;
                            durations[b][a] = -1;
                            missing.add(nodes.get(a).name() + "→" + nodes.get(b).name());
                        }
                    }, executor));
                }
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        }

        List<String> nodeNames = nodes.stream().map(PoiCandidate::name).toList();
        TimeMatrix matrix = new TimeMatrix(nodeNames, durations);
        taskResultCache.register(GLOBAL_SCOPE, scope, TaskResultCache.TaskType.MATRIX,
                null, matrixToJson(matrix));
        log.info("通勤矩阵构建完成: nodes={}, apiCalls={}, retriedPairs={}, missingPairs={}, duration={}ms",
                n, apiCalls.get(), retriedPairs.get(), missing.size(),
                (System.nanoTime() - start) / 1_000_000);
        return new MatrixBuildOutcome(matrix, nodes, List.copyOf(missing), apiCalls.get(), false);
    }

    /**
     * 逐段交通方式标签（S1.5）：与 {@link #build} 的分档规则同源——直线距离
     * &lt; {@link #DRIVING_THRESHOLD_KM} 或全驾车档返回「驾车」，否则「公交」。
     * 供 TimelineScheduler 填 transitFromPrev 展示标签。降级链换方式成功的罕见
     * 场景按首选方式标注（矩阵只存秒数，不记录实际成功方式），仅作展示用途。
     */
    static String segmentModeLabel(PoiCandidate a, PoiCandidate b, boolean preferTransit) {
        boolean drivingFirst = !preferTransit
                || haversineKm(a.lng(), a.lat(), b.lng(), b.lat()) < DRIVING_THRESHOLD_KM;
        return drivingFirst ? "驾车" : "公交";
    }

    // ==================== 两两查询（逐段方式 + 降级链） ====================

    private long queryPairSeconds(PoiCandidate a, PoiCandidate b, String city,
                                  boolean preferTransit, List<String> missing,
                                  AtomicInteger retriedPairs) {
        if (a.lng() == null || a.lat() == null || b.lng() == null || b.lat() == null) {
            missing.add(a.name() + "→" + b.name());
            return -1;
        }
        Long sec = attemptPair(a, b, city, preferTransit);
        if (sec == null) {
            // 偶发 QPS 限流：间隔后整对重试一次（真不可达的负结果行为不变，仅多一轮查询）
            LockSupport.parkNanos(RETRY_DELAY_NANOS);
            retriedPairs.incrementAndGet();
            sec = attemptPair(a, b, city, preferTransit);
        }
        if (sec == null) {
            missing.add(a.name() + "→" + b.name());
            return -1;
        }
        return sec;
    }

    /** 单轮双模式尝试：首选方式失败即换另一方式（逐段交通方式分档不变） */
    private Long attemptPair(PoiCandidate a, PoiCandidate b, String city, boolean preferTransit) {
        double straightKm = haversineKm(a.lng(), a.lat(), b.lng(), b.lat());
        boolean drivingFirst = !preferTransit || straightKm < DRIVING_THRESHOLD_KM;
        Long sec = drivingFirst ? tryDriving(a, b) : tryTransit(a, b, city);
        if (sec == null) {
            sec = drivingFirst ? tryTransit(a, b, city) : tryDriving(a, b);
        }
        return sec;
    }

    /** 调用起步节流：全局限流（虚拟线程并发下保证 ≤ ~2.9/s 起步，匹配高德个人 key QPS） */
    private void paceCall() {
        if (callMinIntervalNanos <= 0) {
            return;
        }
        synchronized (rateGate) {
            long wait = callMinIntervalNanos - (System.nanoTime() - lastCallAtNanos);
            if (wait > 0) {
                LockSupport.parkNanos(wait);
            }
            lastCallAtNanos = System.nanoTime();
        }
    }

    private Long tryDriving(PoiCandidate a, PoiCandidate b) {
        try {
            paceCall();
            String json = transportTool.getDrivingRoute(coord(a), coord(b));
            return parseDurationSeconds(json, false);
        } catch (Exception e) {
            log.debug("驾车查询异常（走降级）: {}→{}: {}", a.name(), b.name(), e.getMessage());
            return null;
        }
    }

    private Long tryTransit(PoiCandidate a, PoiCandidate b, String city) {
        try {
            paceCall();
            String json = transportTool.getTransitRoute(coord(a), coord(b), city);
            return parseDurationSeconds(json, true);
        } catch (Exception e) {
            log.debug("公交查询异常（走降级）: {}→{}: {}", a.name(), b.name(), e.getMessage());
            return null;
        }
    }

    /** 解析时长（秒）：driving 取顶层 duration；transit 取 routes[0].duration；error 或无值返回 null */
    private Long parseDurationSeconds(String json, boolean transit) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JSONObject object = JSON.parseObject(json);
            if (object == null || object.containsKey("error")) {
                return null;
            }
            String duration = transit
                    ? firstTransitDuration(object)
                    : object.getString("duration");
            if (duration == null || duration.isBlank()) {
                return null;
            }
            return Long.parseLong(duration.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstTransitDuration(JSONObject object) {
        JSONArray routes = object.getJSONArray("routes");
        if (routes == null || routes.isEmpty()) {
            return null;
        }
        JSONObject first = routes.getJSONObject(0);
        return first == null ? null : first.getString("duration");
    }

    // ==================== 坐标补齐（geocode + GEO 缓存） ====================

    private List<PoiCandidate> resolveCoordinates(List<PoiCandidate> pois) {
        return pois.stream().map(p -> {
            if (p.lng() != null && p.lat() != null) {
                return p;
            }
            String scope = "geo:" + p.name();
            String location = taskResultCache.get(GLOBAL_SCOPE, scope, TaskResultCache.TaskType.GEO);
            if (location == null) {
                location = geocodeLocation(p.name());
                if (location != null) {
                    taskResultCache.register(GLOBAL_SCOPE, scope, TaskResultCache.TaskType.GEO,
                            null, location);
                }
            }
            if (location == null) {
                log.warn("geocode 补齐失败（该点相关通勤对将置 -1）: {}", p.name());
                return p;
            }
            String[] parts = location.split(",");
            return new PoiCandidate(p.name(), Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()), p.openStart(), p.openClose(),
                    p.suggestedDurationMin(), p.rating(), p.source());
        }).toList();
    }

    private String geocodeLocation(String name) {
        try {
            String json = transportTool.geocode(name);
            JSONObject object = JSON.parseObject(json);
            if (object == null || object.containsKey("error")) {
                return null;
            }
            String location = object.getString("location");
            return (location == null || location.isBlank()) ? null : location;
        } catch (Exception e) {
            log.debug("geocode 异常: {}: {}", name, e.getMessage());
            return null;
        }
    }

    // ==================== 工具方法 ====================

    private static String coord(PoiCandidate p) {
        return p.lng() + "," + p.lat();
    }

    /** haversine 直线距离（km，仅用于交通方式分档，不进矩阵） */
    private static double haversineKm(double lng1, double lat1, double lng2, double lat2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** 矩阵指纹：排序后的 节点名@坐标 拼接取 SHA-256 前 16 位（与项目 L2 缓存键风格一致） */
    private static String matrixHash(List<PoiCandidate> nodes) {
        String material = nodes.stream()
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

    private static String matrixToJson(TimeMatrix matrix) {
        return JSON.toJSONString(matrix);
    }

    private static TimeMatrix matrixFromJson(String json) {
        try {
            return JSON.parseObject(json, TimeMatrix.class);
        } catch (Exception e) {
            log.warn("矩阵缓存反序列化失败（按未命中处理）: {}", e.getMessage());
            return null;
        }
    }
}
