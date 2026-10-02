package com.travelscope.repository;

import com.travelscope.entity.EvaluationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 评估记录仓库（evaluation_records 表）
 * <p>
 * 「按时间倒序查最近 N 条」由服务层经继承的 {@code findAll(Pageable)} +
 * {@code PageRequest.of(0, n, Sort.desc("createdAt"))} 实现（N 参数化，方法名派生
 * 只能写死 TopN）；表已在 created_at 上建 DESC 索引。
 * </p>
 */
@Repository
public interface EvaluationRecordRepository extends JpaRepository<EvaluationRecord, Long> {
}
