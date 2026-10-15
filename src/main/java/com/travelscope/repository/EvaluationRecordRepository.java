package com.travelscope.repository;

import com.travelscope.entity.EvaluationRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 评估记录仓库（evaluation_records 表）
 * <p>
 * 「按时间倒序查最近 N 条」由服务层经继承的 {@code findAll(Pageable)} +
 * {@code PageRequest.of(0, n, Sort.desc("createdAt"))} 实现（N 参数化，方法名派生
 * 只能写死 TopN）；表已在 created_at 上建 DESC 索引。
 * </p>
 * <p>
 * 2026-10-14 P2-M 评测看板：补过滤查询与聚合查询。聚合用 JPQL 分组（CASE WHEN
 * 计数通过数），行 Object[]{分组键, 总数, 通过数}；category 为 NULL 的存量行归
 * 「未分类」由服务层转译。
 * </p>
 */
@Repository
public interface EvaluationRecordRepository extends JpaRepository<EvaluationRecord, Long> {

    long countByIsBadCaseTrue();

    /** 按类别聚合通过率（category 为 NULL 的行归入 NULL 键，服务层转译为「未分类」） */
    @Query("SELECT e.category, COUNT(e), SUM(CASE WHEN e.isBadCase = true THEN 0 ELSE 1 END) "
            + "FROM EvaluationRecord e GROUP BY e.category ORDER BY COUNT(e) DESC")
    List<Object[]> summarizeByCategory();

    /** 按用例聚合通过率（只统计 EvalCase 记录，线上评估 case_id 为空排除） */
    @Query("SELECT e.caseId, COUNT(e), SUM(CASE WHEN e.isBadCase = true THEN 0 ELSE 1 END) "
            + "FROM EvaluationRecord e WHERE e.caseId IS NOT NULL "
            + "GROUP BY e.caseId ORDER BY e.caseId")
    List<Object[]> summarizeByCase();

    /**
     * 过滤查询（看板列表）：条件为 null/false 时不过滤；按 createdAt 倒序 + limit。
     * 参数显式 cast——PG 无法从 {@code :caseId IS NULL} 语境推断参数类型
     * （2026-10-14 实测：「无法确定参数 $1 的数据类型」500），cast 后类型确定。
     */
    @Query("SELECT e FROM EvaluationRecord e "
            + "WHERE (CAST(:caseId AS string) IS NULL OR e.caseId = CAST(:caseId AS string)) "
            + "AND (CAST(:category AS string) IS NULL OR e.category = CAST(:category AS string)) "
            + "AND (:badCaseOnly = false OR e.isBadCase = true) "
            + "ORDER BY e.createdAt DESC")
    List<EvaluationRecord> findByFilters(@Param("caseId") String caseId,
                                         @Param("category") String category,
                                         @Param("badCaseOnly") boolean badCaseOnly,
                                         Pageable pageable);
}
