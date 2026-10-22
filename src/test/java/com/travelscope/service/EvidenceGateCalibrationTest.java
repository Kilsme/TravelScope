package com.travelscope.service;

import com.travelscope.config.AppProperties;
import com.travelscope.dto.RetrievedFragment;
import com.travelscope.llm.ProviderProbeScheduler;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 证据门控阈值校准载具（清单 4.4 步骤 6；env 门控，常态跳过）
 * <p>
 * 前置：API_KEY（DashScope embedding）+ PG 5432（含 pgvector 与种子/摄入数据）+ ES 9200。
 * 用途：对代表性 query（强相关/弱相关/不相关）打印各片段的 vectorScore 分布，
 * 供人工校准 {@code rag.evidence-gate-min-score}（目标：不相关 query 全灭、强相关全保留）。
 * 无断言——输出即产物（校准依据请回写 yml 注释与优化清单 4.4）。
 * </p>
 */
@EnabledIfEnvironmentVariable(named = "API_KEY", matches = ".+")
class EvidenceGateCalibrationTest {

    private static boolean reachable(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @DisplayName("打印代表性 query 的相似度分分布（校准阈值用，非断言测试）")
    void printScoreDistribution() {
        if (!reachable(5432) || !reachable(9200)) {
            return;   // 环境未就绪：跳过（E2E 检测时再跑）
        }
        AppProperties props = new AppProperties();
        props.getElasticsearch().setUrl("http://localhost:9200");
        props.getRag().setEvidenceGateEnabled(false);   // 校准看原始分布（不门控）
        EsBm25Client es = new EsBm25Client(props);
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:postgresql://localhost:5432/travelscope");
        ds.setUsername("root");
        ds.setPassword("root");
        try {
            RagServiceImpl rag = new RagServiceImpl(props, es, ds, "root", "root", new ProviderProbeScheduler());
            try {
                // 强相关（种子库覆盖）/ 弱相关（沾边城市）/ 不相关（无关领域）
                String[] queries = {"杭州 必去景点", "杭州 亲子游 推荐", "西安 兵马俑 攻略",
                        "哈尔滨 冰雪大世界 门票", "量子力学 入门", "怎么做红烧肉"};
                for (String q : queries) {
                    var result = rag.dualRetrieveGated(q, 10);
                    System.out.println("==== query=" + q + " kept=" + result.fragments().size());
                    for (RetrievedFragment f : result.fragments()) {
                        System.out.println(String.format("  vectorScore=%s source=%s chunk=%s %s",
                                f.vectorScore(), f.source(), f.chunkId(),
                                f.content().substring(0, Math.min(20, f.content().length()))));
                    }
                }
            } finally {
                rag.shutdownRetrievalExecutor();
            }
        } finally {
            ds.close();
            es.close();
        }
    }
}
