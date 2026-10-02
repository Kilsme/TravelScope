package com.travelscope.service;

import com.travelscope.entity.EvaluationRecord;
import com.travelscope.repository.EvaluationRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 评估记录服务（FR-S13 评估体系 / FR-S15 EvalCase 结果落库，改造计划 A3）
 * <p>
 * 评估结果（规则各项 + Rubric 各项 + 总分 + 坏例标记）落 evaluation_records，
 * trace_id 关联 Jaeger 链路。调用方为评估 runner（EvalCase 回归 / 线上真实对话
 * 评估）；管理端查询接口 P1 再做（克制原则）。
 * </p>
 */
@Service
public class EvaluationRecordService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRecordService.class);

    private final EvaluationRecordRepository repository;

    public EvaluationRecordService(EvaluationRecordRepository repository) {
        this.repository = repository;
    }

    /**
     * 保存一条评估记录
     */
    public EvaluationRecord save(EvaluationRecord record) {
        EvaluationRecord saved = repository.save(record);
        log.info("评估记录已落库: id={}, caseId={}, traceId={}, totalScore={}, isBadCase={}",
                saved.getId(), saved.getCaseId(), saved.getTraceId(),
                saved.getTotalScore(), saved.getIsBadCase());
        return saved;
    }

    /**
     * 按创建时间倒序查最近 limit 条评估记录
     *
     * @param limit 条数上限（≤0 返回空列表）
     */
    public List<EvaluationRecord> recent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return repository.findAll(PageRequest.of(0, limit,
                        Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent();
    }
}
