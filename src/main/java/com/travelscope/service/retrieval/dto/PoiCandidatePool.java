package com.travelscope.service.retrieval.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * POI 候选池（S1.8，PoiRetrievalService 的产出；planning-agent 的筛选输入）。
 * <p>
 * 候选是<b>知识库片段级</b>的（非解析后的 POI）——每条带来源与证据标注；
 * 筛选排序（偏好/开放时间/评分门槛）不在本契约内，那是 planning-agent 的
 * LLM 职责（v4 重构计划 §5.4 职责边界）。
 * </p>
 */
public record PoiCandidatePool(List<Candidate> candidates, PoolStats stats) {

    public PoiCandidatePool {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        Objects.requireNonNull(stats, "stats");
    }

    /**
     * P2 模型视角瘦身视图（v6）：候选条目只带 {content(截断), source, score}——
     * 裁掉筛选决策无关的 chunkId/vectorScore/hitVariants（对齐官方 "smallest set of
     * high-signal tokens"；池候选无结构化 rating/openHours 字段可白名单，它们在
     * chunk 文本内，故瘦身形态=裁字段+截断而非字段筛选）。
     * 全量 JSON 仍由工具自登记进 TaskResultCache 供代码消费（just-in-time）。
     *
     * @param maxContentLen content 截断长度（超出附 …）
     */
    public String toSlimView(int maxContentLen) {
        com.alibaba.fastjson2.JSONArray arr = new com.alibaba.fastjson2.JSONArray();
        for (Candidate c : candidates) {
            com.alibaba.fastjson2.JSONObject o = new com.alibaba.fastjson2.JSONObject();
            String content = c.content();
            o.put("content", content.length() <= maxContentLen
                    ? content : content.substring(0, maxContentLen) + "…");
            o.put("source", c.source());
            o.put("score", c.score());
            arr.add(o);
        }
        com.alibaba.fastjson2.JSONObject root = new com.alibaba.fastjson2.JSONObject();
        root.put("candidates", arr);
        root.put("stats", stats);
        return root.toJSONString();
    }

    /**
     * 候选条目（按 chunkId 去重合并后；补全条目走 hard:{来源}:{名称} 合成键）。
     *
     * @param chunkId      知识库 chunk 标识（去重键）或硬补全合成键
     * @param content      片段原文（知识库 chunk）或补全条目格式化文本
     * @param source       来源标记：RAG / ES / RAG+ES（检索条目）或 HARD_COMPLETE（硬补全）
     * @param score        RRF 融合得分（共识度，非相关性）；补全条目恒 0
     * @param vectorScore  pgvector 余弦相似度（可空——ES-only 或补全条目无此分）
     * @param hitVariants  命中本候选的检索变体（有序去重；多变体命中时累计）
     * @param imageUrl     图片 URL（v5-F2，可空——仅硬补全条目来自 searchPois 实时图，知识库
     *                     chunk 无图；select 继承进 poi_shortlist 供组装渲染图集。
     *                     注意 toSlimView 不含它——模型筛选决策不需要 URL，省 token）
     */
    public record Candidate(String chunkId, String content, String source, double score,
                            Double vectorScore, List<String> hitVariants, String imageUrl,
                            String sourceId, String poiName, Double lng, Double lat,
                            List<String> mergedFrom, String mergeReason) {

        public Candidate {
            Objects.requireNonNull(chunkId, "chunkId");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(source, "source");
            hitVariants = List.copyOf(Objects.requireNonNull(hitVariants, "hitVariants"));
            mergedFrom = mergedFrom == null ? List.of(chunkId) : List.copyOf(mergedFrom);
        }

        /** 旧调用便捷构造：无实体解析元数据。 */
        public Candidate(String chunkId, String content, String source, double score,
                         Double vectorScore, List<String> hitVariants, String imageUrl) {
            this(chunkId, content, source, score, vectorScore, hitVariants, imageUrl,
                    null, null, null, null, List.of(chunkId), "NONE");
        }

        public Candidate(String chunkId, String content, String source, double score,
                         Double vectorScore, List<String> hitVariants) {
            this(chunkId, content, source, score, vectorScore, hitVariants, null);
        }
    }

    /**
     * 池统计（一次成型观察指标，日志锚点）。
     *
     * @param variantCount    检索变体数（QueryExpander 产出，≤5）
     * @param hitsByVariant   各变体命中数（门控后、去重前；含 0 命中的变体）
     * @param totalAfterDedup chunkId 去重合并后的候选数（不含补全）
     * @param completedCount  硬补全条数（候选 &lt; 2×days 时经 AttractionTool 补入）
     */
    public record PoolStats(int variantCount, Map<String, Integer> hitsByVariant,
                            int totalAfterDedup, int completedCount) {

        public PoolStats {
            // 保序（变体顺序即优先级）：Map.copyOf 会按哈希序重排，不能用
            hitsByVariant = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(hitsByVariant, "hitsByVariant")));
        }
    }
}
