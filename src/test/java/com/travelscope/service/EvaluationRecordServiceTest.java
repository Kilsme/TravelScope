package com.travelscope.service;

import com.alibaba.fastjson2.JSON;
import com.travelscope.entity.EvaluationRecord;
import com.travelscope.repository.EvaluationRecordRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * EvaluationRecordService 单测（改造计划 A3：评估记录表 + 记录服务）
 * <p>
 * 两部分（对应项目两种既有先例）：
 * <ul>
 *   <li>服务逻辑：Mockito（同 ReviewerRetryMiddlewareTest 风格），验证 save 委托与
 *       recent 的 createdAt 倒序分页构造</li>
 *   <li>真库 save→query 往返：@EnabledIf 端口探测门控（同 TaskResultCacheTest.RedisRoundTrip /
 *       TripRequirementStoreRedisTest）——application-test.yml 排除了 DataSource 自动配置，
 *       手工构造 DataSource + EntityManagerFactory，不走 Spring 上下文。PG 未启动时优雅跳过</li>
 * </ul>
 * </p>
 */
class EvaluationRecordServiceTest {

    // ==================== 服务逻辑（纯 mock，无外部依赖） ====================

    @Test
    @DisplayName("save 委托仓库落库并返回")
    void save_delegatesToRepository() {
        EvaluationRecordRepository repo = mock(EvaluationRecordRepository.class);
        EvaluationRecord record = new EvaluationRecord();
        when(repo.save(record)).thenReturn(record);

        EvaluationRecord saved = new EvaluationRecordService(repo).save(record);

        assertSame(record, saved);
        verify(repo).save(record);
    }

    @Test
    @DisplayName("recent(N) 构造 createdAt 倒序分页查询并返回内容")
    void recent_buildsDescPageRequest() {
        EvaluationRecordRepository repo = mock(EvaluationRecordRepository.class);
        List<EvaluationRecord> content = List.of(new EvaluationRecord(), new EvaluationRecord());
        when(repo.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(content, PageRequest.of(0, 5), 2));

        List<EvaluationRecord> result = new EvaluationRecordService(repo).recent(5);

        assertEquals(content, result);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repo).findAll(captor.capture());
        Pageable pageable = captor.getValue();
        assertEquals(0, pageable.getPageNumber(), "应取第 0 页");
        assertEquals(5, pageable.getPageSize(), "页大小应为 N");
        assertEquals(Sort.by(Sort.Direction.DESC, "createdAt"), pageable.getSort(),
                "应按 createdAt 倒序");
    }

    @Test
    @DisplayName("recent(≤0) 返回空列表且不触库")
    void recent_nonPositiveLimitReturnsEmpty() {
        EvaluationRecordRepository repo = mock(EvaluationRecordRepository.class);
        EvaluationRecordService service = new EvaluationRecordService(repo);

        assertTrue(service.recent(0).isEmpty());
        assertTrue(service.recent(-3).isEmpty());
        verifyNoInteractions(repo);
    }

    // ==================== 真库 save→query 往返（端口探测门控） ====================

    static boolean pgReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5432), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 建表 DDL（与 schema.sql 第 6 节保持一致；幂等） */
    private static final String CREATE_TABLE_DDL = """
            CREATE TABLE IF NOT EXISTS evaluation_records (
                id              BIGSERIAL PRIMARY KEY,
                trace_id        VARCHAR(64),
                case_id         VARCHAR(64),
                rule_results    JSONB,
                rubric_scores   JSONB,
                total_score     NUMERIC(5,2),
                is_bad_case     BOOLEAN       NOT NULL DEFAULT FALSE,
                created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
            )""";

    @Nested
    @EnabledIf(value = "com.travelscope.service.EvaluationRecordServiceTest#pgReachable",
            disabledReason = "本机 5432 无 PostgreSQL 监听（启动本地 PG 后重跑）")
    class PgRoundTrip {

        @Test
        @DisplayName("真库 save→query 往返：JSONB/可空 case_id/BigDecimal/时间戳全字段往返")
        void saveQueryRoundTrip() throws Exception {
            // —— 手工构造 JPA 环境（连接参数与 application.yml 一致）——
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:postgresql://localhost:5432/travelscope?stringtype=unspecified",
                    "root", "root");
            LocalContainerEntityManagerFactoryBean emfBean = new LocalContainerEntityManagerFactoryBean();
            emfBean.setDataSource(dataSource);
            emfBean.setPackagesToScan("com.travelscope.entity");
            emfBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            emfBean.afterPropertiesSet();
            EntityManagerFactory emf = emfBean.getObject();
            assertNotNull(emf, "EntityManagerFactory 构建失败");

            // 建表（幂等，存量库由 schema.sql 手动升级，这里保证测试自包含）
            try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
                st.execute(CREATE_TABLE_DDL);
            }

            String traceId = "roundtrip-" + System.currentTimeMillis();
            String ruleResults = "[{\"type\":\"FIELD_COMPLETE\",\"pass\":true},"
                    + "{\"type\":\"CONSTRAINT_COVERED\",\"pass\":false}]";
            String rubricScores = "{\"coherence\":9,\"density\":8,\"preference\":7}";
            EntityManager em = emf.createEntityManager();
            try {
                em.getTransaction().begin();
                EvaluationRecord record = new EvaluationRecord();
                record.setTraceId(traceId);
                record.setCaseId(null);   // 线上真实对话评估场景
                record.setRuleResults(ruleResults);
                record.setRubricScores(rubricScores);
                record.setTotalScore(new BigDecimal("82.50"));
                record.setIsBadCase(false);
                em.persist(record);
                em.flush();
                assertNotNull(record.getId(), "落库后应生成主键");
                em.clear();   // 脱离一级缓存，确保查库

                List<EvaluationRecord> found = em.createQuery(
                                "select r from EvaluationRecord r where r.traceId = :tid",
                                EvaluationRecord.class)
                        .setParameter("tid", traceId)
                        .getResultList();

                assertEquals(1, found.size());
                EvaluationRecord back = found.get(0);
                assertEquals(traceId, back.getTraceId());
                assertNull(back.getCaseId(), "线上场景 caseId 应保持 NULL");
                // JSONB 读取会规范化（对象键重排），按语义比较而非字符串
                assertEquals(JSON.parse(ruleResults), JSON.parse(back.getRuleResults()),
                        "rule_results JSONB 应语义往返");
                assertEquals(JSON.parse(rubricScores), JSON.parse(back.getRubricScores()),
                        "rubric_scores JSONB 应语义往返");
                assertEquals(new BigDecimal("82.50"), back.getTotalScore(), "totalScore 应精确往返");
                assertEquals(Boolean.FALSE, back.getIsBadCase());
                assertNotNull(back.getCreatedAt(), "createdAt 应由 @PrePersist 填充");

                // 清理测试行
                em.remove(back);
                em.getTransaction().commit();
            } finally {
                em.close();
                emf.close();
            }
        }
    }
}
