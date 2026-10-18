package com.travelscope.service;

import com.alibaba.fastjson2.JSON;
import com.travelscope.entity.EvaluationRecord;
import com.travelscope.repository.EvaluationRecordRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

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
 *   <li>真库 save→query 往返：仅显式配置 ASSESSMENT_TEST_DATABASE_URL 时运行，
 *       在测试库中创建独立 schema；未配置时跳过，绝不自动连接应用数据库。</li>
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

    // ==================== 评测看板（2026-10-14 P2-M） ====================

    @Test
    @DisplayName("summary：总数/通过率/坏例数 + 两聚合；category NULL 归「未分类」")
    void summary_aggregatesWithUncategorizedBucket() {
        EvaluationRecordRepository repo = mock(EvaluationRecordRepository.class);
        when(repo.count()).thenReturn(5L);
        when(repo.countByIsBadCaseTrue()).thenReturn(1L);
        when(repo.summarizeByCategory()).thenReturn(java.util.List.of(
                new Object[]{"MULTI_CITY", 3L, 3L},
                new Object[]{null, 2L, 1L}));        // 存量行（补列前落库）归「未分类」
        when(repo.summarizeByCase()).thenReturn(java.util.List.of(
                new Object[]{"EC-001", 3L, 3L},
                new Object[]{"EC-002", 2L, 1L}));

        com.travelscope.dto.EvaluationSummaryVO s = new EvaluationRecordService(repo).summary();

        assertEquals(5L, s.total());
        assertEquals(4L, s.passed());
        assertEquals(80.0, s.passRate());
        assertEquals(1L, s.badCases());
        assertEquals(2, s.byCategory().size());
        assertEquals("MULTI_CITY", s.byCategory().get(0).key());
        assertEquals(100.0, s.byCategory().get(0).passRate());
        assertEquals("未分类", s.byCategory().get(1).key());
        assertEquals(50.0, s.byCategory().get(1).passRate());
        assertEquals("EC-002", s.byCase().get(1).key());
        assertEquals(50.0, s.byCase().get(1).passRate());
    }

    @Test
    @DisplayName("summary：空库时通过率 0 不除零")
    void summary_emptyTableNoDivideByZero() {
        EvaluationRecordRepository repo = mock(EvaluationRecordRepository.class);
        when(repo.count()).thenReturn(0L);
        when(repo.countByIsBadCaseTrue()).thenReturn(0L);
        when(repo.summarizeByCategory()).thenReturn(java.util.List.of());
        when(repo.summarizeByCase()).thenReturn(java.util.List.of());

        com.travelscope.dto.EvaluationSummaryVO s = new EvaluationRecordService(repo).summary();

        assertEquals(0.0, s.passRate());
        assertTrue(s.byCategory().isEmpty());
    }

    @Test
    @DisplayName("records：过滤参数空白归 null 透传，limit≤0 不触库")
    void records_filterNormalizationAndLimitGuard() {
        EvaluationRecordRepository repo = mock(EvaluationRecordRepository.class);
        EvaluationRecordService service = new EvaluationRecordService(repo);

        assertTrue(service.records("  ", "", false, 0).isEmpty());
        verifyNoInteractions(repo);

        when(repo.findByFilters(any(), any(), org.mockito.ArgumentMatchers.eq(true),
                any(Pageable.class))).thenReturn(java.util.List.of());
        service.records("  ", "", true, 10);
        verify(repo).findByFilters(org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(true), any(Pageable.class));
    }

    // ==================== 真库 save→query 往返（显式测试库 + 隔离 schema） ====================

    /** 建表 DDL（与 schema.sql 第 6 节保持一致；幂等） */
    private static final String CREATE_TABLE_DDL = """
            CREATE TABLE IF NOT EXISTS evaluation_records (
                id              BIGSERIAL PRIMARY KEY,
                trace_id        VARCHAR(64),
                case_id         VARCHAR(64),
                category        VARCHAR(32),
                rule_results    JSONB,
                rubric_scores   JSONB,
                total_score     NUMERIC(5,2),
                is_bad_case     BOOLEAN       NOT NULL DEFAULT FALSE,
                created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
            )""";

    @Nested
    @EnabledIfEnvironmentVariable(named = "ASSESSMENT_TEST_DATABASE_URL", matches = ".+")
    class PgRoundTrip {

        @Test
        @DisplayName("真库 save→query 往返：JSONB/可空 case_id/BigDecimal/时间戳全字段往返")
        void saveQueryRoundTrip() throws Exception {
            Properties credentials = new Properties();
            for (String key : List.of("USER", "PASSWORD")) {
                String value = System.getenv("ASSESSMENT_TEST_DATABASE_" + key);
                if (value != null) credentials.setProperty(key.toLowerCase(Locale.ROOT), value);
            }
            String url = System.getenv("ASSESSMENT_TEST_DATABASE_URL");
            String schema = "evaluation_it_" + UUID.randomUUID().toString().replace("-", "");
            try (Connection owner = DriverManager.getConnection(url, credentials)) {
                try (Statement statement = owner.createStatement()) {
                    statement.execute("CREATE SCHEMA " + schema);
                }
                try {
                    Properties isolated = new Properties();
                    isolated.putAll(credentials);
                    isolated.setProperty("currentSchema", schema);
                    isolated.setProperty("stringtype", "unspecified");
                    DriverManagerDataSource dataSource = new DriverManagerDataSource();
                    dataSource.setUrl(url);
                    dataSource.setConnectionProperties(isolated);
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
                        record.setCategory("REWORK_RECOVERY");   // 2026-10-14 P2-M 补列：类别往返
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
                        assertEquals("REWORK_RECOVERY", back.getCategory(), "category 应往返（P2-M 补列）");
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
                        if (em.getTransaction().isActive()) em.getTransaction().rollback();
                        em.close();
                        emf.close();
                    }
                } finally {
                    try (Statement statement = owner.createStatement()) {
                        statement.execute("DROP SCHEMA " + schema + " CASCADE");
                    }
                }
            }
        }
    }
}
