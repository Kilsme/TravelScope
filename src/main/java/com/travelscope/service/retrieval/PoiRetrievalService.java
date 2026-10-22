package com.travelscope.service.retrieval;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.travelscope.agent.tools.AttractionTool;
import com.travelscope.dto.RetrievedFragment;
import com.travelscope.service.RagServiceImpl;
import com.travelscope.service.retrieval.dto.PoiCandidatePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * POI 批量检索编排（S1.8，v4 计划 §5.3 第 2 步）：变体并行双路检索 + 按 chunkId
 * 去重合并，一次成型候选池。零 LLM——偏好筛选/开放时间适配/评分门槛是
 * itinerary-agent 的职责（边界见重构计划 §5.4）。
 * <p>
 * 流程（retrieve）：
 * <ol>
 *   <li>{@link QueryExpander} 生成 ≤5 个检索变体；</li>
 *   <li>每个变体并行调 {@link RagServiceImpl#dualRetrieveGated}（pgvector+ES 双路 +
 *       RRF 融合 + 证据门控，现有逻辑零改动复用）——IO 密集走虚拟线程池
 *       （用户线程池约定）；单变体异常/门控全灭按空结果降级，不拖垮全池；</li>
 *   <li>各变体结果按 chunkId 去重合并：同 chunkId 多命中时融合分取最大者为准，
 *       hitVariants 保序累计；池内按融合分降序（证据强度序，非偏好排序）；</li>
 *   <li>候选数 &lt; 2×days 时触发硬字段补全（v3 poi-research 第③步的代码平移）：
 *       按变体顺序逐个 {@link AttractionTool#searchPois}，够数即停；</li>
 *   <li>产出 {@link PoiCandidatePool}（candidates + stats 四字段）并落日志。</li>
 * </ol>
 * </p>
 */
@Service
public class PoiRetrievalService {
    @FunctionalInterface
    public interface PoiSearch {
        String search(String destination, String keyword, int limit);
    }

    private static final Logger log = LoggerFactory.getLogger(PoiRetrievalService.class);

    /** 每变体召回条数：覆盖 2×days 池目标的召回余量（去重与门控均有损耗） */
    static final int PER_VARIANT_TOP_K = 8;

    private final RagServiceImpl ragService;
    private final AttractionTool attractionTool;
    private final QueryExpander queryExpander;
    /** L3 变体缓存（可空——测试构造直连不缓存；生产由 Spring 注入） */
    private final com.travelscope.service.TaskResultCache taskResultCache;

    /** Spring 装配入口（两个公有构造函数时须显式标注，否则容器找不到默认构造器） */
    @org.springframework.beans.factory.annotation.Autowired
    public PoiRetrievalService(RagServiceImpl ragService, AttractionTool attractionTool,
                               com.travelscope.service.TaskResultCache taskResultCache) {
        this(ragService, attractionTool, new QueryExpander(), taskResultCache);
    }

    /** 兼容构造（无缓存直连；评测 fixture 等历史调用方） */
    public PoiRetrievalService(RagServiceImpl ragService, AttractionTool attractionTool) {
        this(ragService, attractionTool, new QueryExpander(), null);
    }

    /** 测试/词典定制注入（QueryExpander 可换词典；无缓存直连） */
    public PoiRetrievalService(RagServiceImpl ragService, AttractionTool attractionTool,
                               QueryExpander queryExpander) {
        this(ragService, attractionTool, queryExpander, null);
    }

    /** 全参构造（L3 变体缓存可注入） */
    public PoiRetrievalService(RagServiceImpl ragService, AttractionTool attractionTool,
                               QueryExpander queryExpander,
                               com.travelscope.service.TaskResultCache taskResultCache) {
        this.ragService = ragService;
        this.attractionTool = attractionTool;
        this.queryExpander = queryExpander;
        this.taskResultCache = taskResultCache;
    }

    /**
     * 批量检索一次成型候选池。
     *
     * @param destination 目的地（如「杭州」）
     * @param preferences 偏好关键词（可空）
     * @param days        行程天数（池目标 ≥ 2×days）
     * @return 候选池（检索条目按融合分降序，补全条目按变体顺序追加在后）+ 池统计
     */
    public PoiCandidatePool retrieve(String destination, List<String> preferences, int days) {
        return retrieve(destination, preferences, days, this::queryFragments, attractionTool::searchPois);
    }

    /** 数据源可替换，检索变体、并行编排、去重和硬字段补全始终执行同一实现。 */
    public PoiCandidatePool retrieve(String destination, List<String> preferences, int days,
            java.util.function.Function<String, List<RetrievedFragment>> retrieval, PoiSearch search) {
        Objects.requireNonNull(destination, "destination");
        if (days < 1) {
            throw new IllegalArgumentException("days 必须 ≥1，实际: " + days);
        }

        List<String> variants = queryExpander.expand(destination, preferences);

        // ① 变体并行双路检索（IO 密集→虚拟线程，单变体失败独立降级）
        List<VariantResult> results;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<VariantResult>> futures = variants.stream()
                    .map(v -> CompletableFuture.supplyAsync(() -> queryVariant(v, retrieval), executor))
                    .toList();
            results = futures.stream().map(CompletableFuture::join).toList();
        }

        // ② 按 chunkId 去重合并（保留证据标注），融合分降序
        Map<String, PoiCandidatePool.Candidate> byChunk = new LinkedHashMap<>();
        Map<String, Integer> hitsByVariant = new LinkedHashMap<>();
        for (VariantResult result : results) {
            hitsByVariant.put(result.variant(), result.fragments().size());
            for (RetrievedFragment fragment : result.fragments()) {
                byChunk.merge(fragment.chunkId(),
                        toCandidate(fragment, result.variant()),
                        PoiRetrievalService::mergeCandidate);
            }
        }
        List<PoiCandidatePool.Candidate> candidates = new ArrayList<>(byChunk.values());
        candidates.sort(Comparator.comparingDouble(PoiCandidatePool.Candidate::score).reversed());

        // ③ 候选不足 → 硬字段补全（v3 第③步平移：searchPois 实时检索）
        int target = 2 * days;
        int completed = 0;
        if (candidates.size() < target) {
            for (String variant : variants) {
                if (candidates.size() >= target) {
                    break;
                }
                List<PoiCandidatePool.Candidate> adds = hardComplete(destination, variant, target, search);
                completed += adds.size();
                candidates.addAll(adds);
            }
        }

        PoiCandidatePool.PoolStats stats = new PoiCandidatePool.PoolStats(
                variants.size(), hitsByVariant, byChunk.size(), completed);
        log.info("poi_retrieval 候选池: destination={}, variants={}, hitsByVariant={}, "
                        + "afterDedup={}, completed={}, total={}",
                destination, stats.variantCount(), stats.hitsByVariant(),
                stats.totalAfterDedup(), stats.completedCount(), candidates.size());
        return new PoiCandidatePool(candidates, stats);
    }

    // ==================== 内部 ====================

    /** 单变体结果：变体词 + 门控后片段（异常/全灭已降级为空） */
    private record VariantResult(String variant, List<RetrievedFragment> fragments) {
    }

    private List<RetrievedFragment> queryFragments(String variant) {
        // L3 变体级缓存（v6）：命中直接返回（跳过 embedding 远程 + PG + ES）；
        // 缓存读写任何异常都降级直连，绝不影响主链路
        String scope = taskResultCache == null ? null : "ragvariant:" + variantKey(variant);
        if (scope != null) {
            try {
                String cached = taskResultCache.get(GLOBAL_SCOPE, scope,
                        com.travelscope.service.TaskResultCache.TaskType.RAG_VARIANT);
                if (cached != null) {
                    List<RetrievedFragment> hit = com.alibaba.fastjson2.JSON.parseArray(
                            cached, RetrievedFragment.class);
                    log.info("poi_retrieval 变体缓存命中: variant='{}', fragments={}", variant, hit.size());
                    return hit;
                }
            } catch (Exception e) {
                log.debug("变体缓存读取失败（直连检索）: {}", e.getMessage());
            }
        }
        RagServiceImpl.GatedResult gated = ragService.dualRetrieveGated(variant, PER_VARIANT_TOP_K);
        if (gated.gateEmpty()) {
            log.info("poi_retrieval 变体门控全灭: variant='{}', dropped={}", variant, gated.dropped());
        }
        if (scope != null) {
            try {
                taskResultCache.register(GLOBAL_SCOPE, scope,
                        com.travelscope.service.TaskResultCache.TaskType.RAG_VARIANT, null,
                        com.alibaba.fastjson2.JSON.toJSONString(gated.fragments()));
            } catch (Exception e) {
                log.debug("变体缓存写入失败（不影响返回）: {}", e.getMessage());
            }
        }
        return gated.fragments();
    }

    /** 全局缓存 scope（与 CommuteMatrixBuilder 的 MATRIX/GEO 同一惯例） */
    private static final String GLOBAL_SCOPE = "global";

    /** 变体缓存键：变体文本 SHA-256 前 16 位（防 key 带中文/空格） */
    private static String variantKey(String variant) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(variant.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private VariantResult queryVariant(String variant,
            java.util.function.Function<String, List<RetrievedFragment>> retrieval) {
        try {
            return new VariantResult(variant, retrieval.apply(variant));
        } catch (Exception e) {
            log.warn("poi_retrieval 变体检索异常（按空结果降级）: variant='{}': {}",
                    variant, e.getMessage());
            return new VariantResult(variant, List.of());
        }
    }

    private static PoiCandidatePool.Candidate toCandidate(RetrievedFragment fragment, String variant) {
        return new PoiCandidatePool.Candidate(fragment.chunkId(), fragment.content(),
                fragment.source(), fragment.score(), fragment.vectorScore(), List.of(variant));
    }

    /** 同 chunkId 再命中：融合分取最大者为准（平分取先到），hitVariants 保序累计 */
    private static PoiCandidatePool.Candidate mergeCandidate(PoiCandidatePool.Candidate old,
                                                             PoiCandidatePool.Candidate cur) {
        PoiCandidatePool.Candidate winner = cur.score() > old.score() ? cur : old;
        List<String> hitVariants = new ArrayList<>(old.hitVariants());
        for (String variant : cur.hitVariants()) {
            if (!hitVariants.contains(variant)) {
                hitVariants.add(variant);
            }
        }
        return new PoiCandidatePool.Candidate(winner.chunkId(), winner.content(), winner.source(),
                winner.score(), winner.vectorScore(), List.copyOf(hitVariants));
    }

    /** 硬字段补全：searchPois 实时检索（v3 第③步平移），错误/异常按空结果降级 */
    private List<PoiCandidatePool.Candidate> hardComplete(String destination, String keyword,
                                                          int pageSize, PoiSearch search) {
        try {
            String json = search.search(destination, keyword, pageSize);
            JSONObject object = JSON.parseObject(json);
            if (object == null || object.containsKey("error")) {
                log.warn("poi_retrieval 硬补全无结果或失败: keyword='{}': {}", keyword,
                        object == null ? "非 JSON" : object.getString("error"));
                return List.of();
            }
            JSONArray attractions = object.getJSONArray("attractions");
            if (attractions == null || attractions.isEmpty()) {
                return List.of();
            }
            List<PoiCandidatePool.Candidate> adds = new ArrayList<>();
            for (int i = 0; i < attractions.size(); i++) {
                JSONObject poi = attractions.getJSONObject(i);
                String name = poi.getString("name");
                if (name == null || name.isBlank()) {
                    continue;
                }
                String source = String.valueOf(poi.get("source"));
                adds.add(new PoiCandidatePool.Candidate(
                        "hard:" + source + ":" + name,
                        formatCompletion(name, poi, source),
                        "HARD_COMPLETE", 0, null, List.of(keyword),
                        poi.getString("imageUrl")));
            }
            return adds;
        } catch (Exception e) {
            log.warn("poi_retrieval 硬补全异常（按空结果降级）: keyword='{}': {}",
                    keyword, e.getMessage());
            return List.of();
        }
    }

    /** 补全条目文本化：跳过缺失字段 */
    private static String formatCompletion(String name, JSONObject poi, String source) {
        StringBuilder sb = new StringBuilder(name);
        appendField(sb, "地址", poi.getString("address"));
        appendField(sb, "坐标", poi.getString("location"));
        appendField(sb, "评分", poi.getString("rating"));
        appendField(sb, "来源", source);
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank() && !"null".equals(value)) {
            sb.append("｜").append(label).append("=").append(value);
        }
    }
}
