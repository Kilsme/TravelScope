package com.travelscope.dto;

/**
 * 双路检索单条结果片段（RagServiceImpl → PoiRagTools 的载体）
 *
 * @param content  片段原文（知识库 chunk）
 * @param source   来源标记：RAG（pgvector 语义路命中）/ ES（BM25 关键词路命中）/ RAG+ES（双路命中）
 * @param score    RRF 融合得分（Σ w/(rrfK+rank)）
 * @param chunkId  知识库 chunk 标识（去重键 + poi_shortlist 的来源引用）
 */
public record RetrievedFragment(String content, String source, double score, String chunkId) {
}
