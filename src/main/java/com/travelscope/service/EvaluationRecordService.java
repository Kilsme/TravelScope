package com.travelscope.service;

import com.travelscope.dto.EvaluationRecordVO;
import com.travelscope.dto.EvaluationSummaryVO;
import com.travelscope.entity.EvaluationRecord;
import com.travelscope.repository.EvaluationRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 评估记录服务（FR-S13 评估体系 / FR-S15 EvalCase 结果落库，改造计划 A3）
 * <p>
 * 评估结果（规则各项 + Rubric 各项 + 总分 + 坏例标记）落 evaluation_records，
 * trace_id 关联 Jaeger 链路。调用方为评估 runner（EvalCase 回归 / 线上真实对话
 * 评估）。
 * </p>
 * <p>
 * 2026-10-14 P2-M 评测看板查询：{@link #summary()}（总数/通过率/坏例数 + 按类别/
 * 按用例聚合）与 {@link #records(String, String, boolean, int)}（过滤列表）——
 * 评估闭环「看趋势」一端的落地。
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

    // ==================== 评测看板（2026-10-14 P2-M） ====================

    /**
     * 看板汇总：总数/通过数/通过率/坏例数 + 按类别、按用例两个聚合分组。
     * 通过率保留 1 位小数百分比（前端直显）；category 为空的存量行归「未分类」。
     */
    public EvaluationSummaryVO summary() {
        long total = repository.count();
        long badCases = repository.countByIsBadCaseTrue();
        long passed = total - badCases;
        return new EvaluationSummaryVO(total, passed, passRate(passed, total), badCases,
                toGroups(repository.summarizeByCategory(), "未分类"),
                toGroups(repository.summarizeByCase(), null));
    }

    /**
     * 看板记录列表：按 caseId/category/坏例过滤，createdAt 倒序
     *
     * @param badCaseOnly true 只看坏例
     * @param limit       条数上限（≤0 返回空列表）
     */
    public List<EvaluationRecordVO> records(String caseId, String category, boolean badCaseOnly, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String caseIdFilter = (caseId == null || caseId.isBlank()) ? null : caseId;
        String categoryFilter = (category == null || category.isBlank()) ? null : category;
        return repository.findByFilters(caseIdFilter, categoryFilter, badCaseOnly,
                        PageRequest.of(0, limit))
                .stream().map(EvaluationRecordService::toVO).toList();
    }

    private static EvaluationRecordVO toVO(EvaluationRecord e) {
        return new EvaluationRecordVO(e.getId(), e.getTraceId(), e.getCaseId(), e.getCategory(),
                e.getRuleResults(), e.getRubricScores(), e.getTotalScore(), e.getIsBadCase(),
                e.getCreatedAt());
    }

    /** 聚合行 Object[]{分组键, 总数, 通过数} → Group VO；nullKey 为空时跳过 NULL 键行 */
    private static List<EvaluationSummaryVO.Group> toGroups(List<Object[]> rows, String nullKey) {
        List<EvaluationSummaryVO.Group> groups = new ArrayList<>();
        for (Object[] row : rows) {
            Object keyObj = row[0];
            String key = keyObj != null ? String.valueOf(keyObj) : null;
            if (key == null) {
                if (nullKey == null) {
                    continue;
                }
                key = nullKey;
            }
            long groupTotal = ((Number) row[1]).longValue();
            long groupPassed = ((Number) row[2]).longValue();
            groups.add(new EvaluationSummaryVO.Group(key, groupTotal, groupPassed,
                    passRate(groupPassed, groupTotal)));
        }
        return groups;
    }

    private static double passRate(long passed, long total) {
        return total == 0 ? 0.0 : Math.round(passed * 1000.0 / total) / 10.0;
    }
}
