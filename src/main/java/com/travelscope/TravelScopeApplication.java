package com.travelscope;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TravelScope - 智能旅游助手
 * <p>
 * 基于 AgentScope Java 2.0 框架构建的智能旅游助手，提供：
 * - 酒店查询（高德地图 API）
 * - 天气查询（和风天气 API）
 * - 文档知识库（RAG 向量检索）
 * - 多轮对话（通义千问）
 * </p>
 */
@SpringBootApplication
@EnableJpaAuditing
@EnableScheduling   // 定时任务：过载降级采样/Redis 状态键清理/信号量清理（2026-09-23 并发改造）
public class TravelScopeApplication {

    public static void main(String[] args) {
        SpringApplication.run(TravelScopeApplication.class, args);
    }
}
