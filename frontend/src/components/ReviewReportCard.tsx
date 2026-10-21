/**
 * 质检明细折叠区（FR-S08 review_report 事件 + R1 质检分级）。
 * 事件载荷为 JSON 字符串：结构化渲染结论徽标 + 评分 + 分段 pass/fail + SOFT 软提示清单；
 * 解析失败（旧格式/异常载荷）回退原文展示，与历史行为一致。
 */

interface ReviewWarningPayload {
  rule: string
  detail: string
  suggestion?: string | null
}

interface ReviewReportPayload {
  passed: boolean
  total: number
  segments?: { segment: number; pass: boolean }[]
  warnings?: ReviewWarningPayload[]
}

export default function ReviewReportCard({ raw }: { raw: string }) {
  let payload: ReviewReportPayload | null = null
  try {
    const parsed = JSON.parse(raw) as ReviewReportPayload
    if (parsed && typeof parsed.total === 'number') {
      payload = parsed
    }
  } catch {
    payload = null
  }
  if (!payload) {
    return (
      <details className="review-report">
        <summary>📋 质检评分明细</summary>
        <div className="review-report-body">{raw}</div>
      </details>
    )
  }
  const warnings = payload.warnings ?? []
  return (
    <details className="review-report">
      <summary>
        📋 质检评分明细{warnings.length > 0 ? `（${warnings.length} 项提示）` : ''}
      </summary>
      <div className="review-report-body">
        <div className={payload.passed ? 'review-verdict pass' : 'review-verdict fail'}>
          {payload.passed ? '✅ 质检通过' : '⚠️ 质检未通过'}（评分 {payload.total}/100）
        </div>
        {(payload.segments?.length ?? 0) > 0 && (
          <ul className="review-segments">
            {payload.segments!.map((s) => (
              <li key={s.segment}>
                段{s.segment}（{['基本要素', '时间线', '预算偏好'][s.segment - 1] ?? '检查'}）{s.pass ? '✅' : '❌'}
              </li>
            ))}
          </ul>
        )}
        {warnings.length > 0 && (
          <ul className="review-warnings">
            {warnings.map((w, i) => (
              <li key={`${w.rule}-${i}`}>
                ⚠️ {w.detail}
                {w.suggestion ? (
                  <span className="review-warning-suggestion">（建议：{w.suggestion}）</span>
                ) : null}
              </li>
            ))}
          </ul>
        )}
      </div>
    </details>
  )
}
