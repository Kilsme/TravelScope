package com.travelscope.dto;

/**
 * 双路检索单条结果片段（RagServiceImpl → PoiRagTools 的载体）
 *
 * @param content     片段原文（知识库 chunk）
 * @param source      来源标记：RAG（pgvector 语义路命中）/ ES（BM25 关键词路命中）/ RAG+ES（双路命中）
 * @param score       RRF 融合得分（Σ w/(rrfK+rank)，衡量双路共识度——非相关性）
 * @param chunkId     知识库 chunk 标识（去重键 + poi_shortlist 的来源引用）
 * @param vectorScore 语义路余弦相似度分（2026-10-11 证据门控完整形态：可空——ES-only 片段无此分；
 *                    绝对相关性信号，与共识融合分并存、不混用；量纲前提：PgVectorStore COSINE，
 *                    score = 1 - 余弦距离，实际值域约 [0,1]，越大越相关）
 */
public record RetrievedFragment(String content, String source, double score, String chunkId,
                                Double vectorScore) {
    /** 兼容旧构造（无 vectorScore，等价 ES-only 片段） */
    public RetrievedFragment(String content, String source, double score, String chunkId) {
        this(content, source, score, chunkId, null);
    }
}
