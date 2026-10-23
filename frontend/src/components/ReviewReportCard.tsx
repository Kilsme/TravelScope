import { usePreferences } from '../preferences/PreferencesProvider'
import { t, ui } from '../preferences/i18n'
/**
 * 质检明细折叠区（FR-S08 review_report 事件 + R1 质检分级 + R4 降级交付）。
 * 事件载荷为 JSON 字符串：结构化渲染结论徽标 + 评分 + 分段 pass/fail + SOFT 软提示清单；
 * R4：DEGRADED 交付（回炉上限 1 超限）渲染可展开的未解决问题清单。
 * 解析失败（旧格式/异常载荷）回退原文展示，与历史行为一致。
 */
interface ReviewWarningPayload {
    rule: string;
    detail: string;
    suggestion?: string | null;
}
interface ReviewReportPayload {
    passed: boolean;
    total: number;
    segments?: {
        segment: number;
        pass: boolean;
    }[];
    warnings?: ReviewWarningPayload[];
    /** R4：NORMAL | DEGRADED（缺省视为 NORMAL，旧载荷无此键） */
    deliveryMode?: string;
    /** R4 DEGRADED：未解决问题清单（可展开渲染，「失败时向用户交回控制权」） */
    issues?: string[];
}
export default function ReviewReportCard({ raw }: {
    raw: string;
}) {
    usePreferences();
    let payload: ReviewReportPayload | null = null;
    try {
        const parsed = JSON.parse(raw) as ReviewReportPayload;
        if (parsed && typeof parsed.total === 'number') {
            payload = parsed;
        }
    }
    catch {
        payload = null;
    }
    if (!payload) {
        return (<details className="review-report">
        <summary>{t("\uD83D\uDCCB 质检评分明细")}</summary>
        <div className="review-report-body">{raw}</div>
      </details>);
    }
    const warnings = payload.warnings ?? [];
    const issues = payload.issues ?? [];
    const degraded = payload.deliveryMode === 'DEGRADED';
    return (<details className="review-report">
      <summary>{t("\uD83D\uDCCB 质检评分明细")}{ui(degraded && issues.length > 0
            ? t("\uFF08{0} 项未解决\uFF09", { "0": issues.length }) : warnings.length > 0
            ? t("\uFF08{0} 项提示\uFF09", { "0": warnings.length }) : '')}
      </summary>
      <div className="review-report-body">
        <div className={payload.passed ? 'review-verdict pass' : 'review-verdict fail'}>
          {ui(payload.passed
            ? t("\u2705 质检通过") : degraded
            ? t("\u26A0\uFE0F 当前最佳版本\uFF08已尽力\uFF09") : t("\u26A0\uFE0F 质检未通过"))}{t("\uFF08评分")}{ui(payload.total)}/100）
        </div>
        {ui((payload.segments?.length ?? 0) > 0 && (<ul className="review-segments">
            {ui(payload.segments!.map((s) => (<li key={s.segment}>{t("段")}{ui(s.segment)}（{ui([t("基本要素"), t("时间线"), t("预算偏好")][s.segment - 1] ?? t("检查"))}）{ui(s.pass ? '✅' : '❌')}
              </li>)))}
          </ul>))}
        {ui(issues.length > 0 && (<ul className="review-issues">
            {ui(issues.map((issue, i) => (<li key={`issue-${i}`}>❌ {ui(issue)}</li>)))}
          </ul>))}
        {ui(warnings.length > 0 && (<ul className="review-warnings">
            {ui(warnings.map((w, i) => (<li key={`${w.rule}-${i}`}>
                ⚠️ {ui(w.detail)}
                {ui(w.suggestion ? (<span className="review-warning-suggestion">{t("\uFF08建议\uFF1A")}{ui(w.suggestion)}）</span>) : null)}
              </li>)))}
          </ul>))}
      </div>
    </details>);
}
