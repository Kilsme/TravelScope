package com.travelscope.service;

import com.travelscope.dto.RetrievedFragment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）融合工具（FR-S11：pgvector 语义路 + ES BM25 路融合排序）
 * <p>
 * 框架核验结论：AgentScope 2.0.3 全库无任何 fusion/rerank API——本类为自写实现。
 * 公式：{@code score(d) = Σ_i w_i / (rrfK + rank_i(d))}（rrfK 默认 60，来自 RagConfig）；
 * 按 chunkId 去重合并，双路均命中标 RAG+ES；输出按融合得分降序截取 topK。
 * 纯静态无状态，可单测。
 * </p>
 */
public final class RrfFusion {

    private RrfFusion() {
    }

    /**
     * 单路输入条目（rank 由列表顺序隐含：index 0 = rank 1）
     *
     * @param chunkId 去重键
     * @param content 片段原文
     */
    public record Entry(String chunkId, String content) {
    }

    /**
     * RRF 融合两路排名结果
     *
     * @param vectorRanked  语义路（pgvector）按相似度降序
     * @param keywordRanked 关键词路（ES BM25）按 _score 降序
     * @param rrfK          RRF 平滑常数（RagConfig.rrfK，默认 60）
     * @param topK          融合后截取条数
     * @return 融合得分降序的片段（带来源标记）
     */
    public static List<RetrievedFragment> fuse(List<Entry> vectorRanked, List<Entry> keywordRanked,
                                               int rrfK, int topK) {
        // chunkId → 聚合状态（保持首次出现顺序，最终再按分数排序）
        Map<String, Accumulator> byChunk = new LinkedHashMap<>();
        accumulate(byChunk, vectorRanked, "RAG", rrfK);
        accumulate(byChunk, keywordRanked, "ES", rrfK);

        return byChunk.values().stream()
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(Math.max(0, topK))
                .map(a -> new RetrievedFragment(a.content, a.source, a.score, a.chunkId))
                .toList();
    }

    private static void accumulate(Map<String, Accumulator> byChunk, List<Entry> ranked,
                                   String laneLabel, int rrfK) {
        if (ranked == null) {
            return;
        }
        for (int i = 0; i < ranked.size(); i++) {
            Entry e = ranked.get(i);
            if (e == null || e.chunkId() == null) {
                continue;
            }
            int rank = i + 1;
            double contribution = 1.0 / (rrfK + rank);
            byChunk.compute(e.chunkId(), (id, acc) -> {
                if (acc == null) {
                    return new Accumulator(e.chunkId(), e.content(), laneLabel, contribution);
                }
                acc.score += contribution;
                acc.source = "RAG+ES";   // 双路命中
                return acc;
            });
        }
    }

    private static final class Accumulator {
        final String chunkId;
        final String content;
        String source;
        double score;

        Accumulator(String chunkId, String content, String source, double score) {
            this.chunkId = chunkId;
            this.content = content;
            this.source = source;
            this.score = score;
        }
    }
}
