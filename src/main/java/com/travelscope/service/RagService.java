package com.travelscope.service;

import java.util.List;

/**
 * RAG 知识库服务（预留接口）
 * <p>
 * 当前为桩实现：知识库尚未接入文档与检索（后续接 SimpleKnowledge + PgVectorStore +
 * DashScopeTextEmbedding），{@link #isAvailable()} 固定返回 false，
 * ChatService 的 RAG 分支据此回退为主 Agent 直接回答。
 * </p>
 * <p>
 * 后续接入时只需替换本实现：{@code retrieve()} 返回命中的知识文本片段，
 * 对话链路与前端零改造。
 * </p>
 */
public interface RagService {

    /**
     * 知识库是否可用（有已入库的文档且检索服务正常）
     */
    boolean isAvailable();

    /**
     * 检索与问题相关的知识片段
     *
     * @param question 用户问题
     * @param topK     返回片段数上限
     * @return 命中的知识文本片段（按相关度排序）
     */
    List<String> retrieve(String question, int topK);
}
