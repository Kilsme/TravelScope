package com.travelscope.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 知识库服务桩实现
 * <p>
 * 知识库尚未接入文档与检索（配置体系已就绪：pgvector + text-embedding-v3 + ES 双路），
 * 本实现 {@link #isAvailable()} 固定返回 false，ChatService 的 RAG 分支据此回退为
 * 主 Agent 直接回答。后续接入 SimpleKnowledge + PgVectorStore 时替换本实现即可，
 * 对话链路与前端零改造。
 * </p>
 */
@Service
public class RagServiceStub implements RagService {

    private static final Logger log = LoggerFactory.getLogger(RagServiceStub.class);

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public List<String> retrieve(String question, int topK) {
        log.debug("RAG 检索桩被调用（知识库未接入），question={}, topK={}", question, topK);
        return List.of();
    }
}
