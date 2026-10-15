import { useCallback, useEffect, useState } from 'react'
import {
  getEvaluationRecords,
  getEvaluationSummary,
  type EvaluationRecord,
  type EvaluationSummary,
} from '../../api/admin'

/**
 * 评测看板页（2026-10-14 P2-M：评估闭环「看趋势」一端）
 * <p>
 * 汇总卡片（总数/通过率/坏例数）+ 按类别通过率表 + 按用例通过率表 + 记录列表
 * （坏例过滤）。零图表依赖，复用 admin-* CSS 类（同 TokenUsagePage 模式）。
 * </p>
 */
export default function EvalDashboardPage() {
  const [summary, setSummary] = useState<EvaluationSummary | null>(null)
  const [records, setRecords] = useState<EvaluationRecord[]>([])
  const [caseId, setCaseId] = useState('')
  const [category, setCategory] = useState('')
  const [badCaseOnly, setBadCaseOnly] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const [s, r] = await Promise.all([
        getEvaluationSummary(),
        getEvaluationRecords({
          caseId: caseId || undefined,
          category: category || undefined,
          badCaseOnly,
          limit: 50,
        }),
      ])
      setSummary(s)
      setRecords(r)
    } catch (err) {
      setError(err instanceof Error ? err.message : '查询失败')
    } finally {
      setLoading(false)
    }
  }, [caseId, category, badCaseOnly])

  useEffect(() => {
    load()
  }, [load])

  const categories = summary?.byCategory.map((g) => g.key) ?? []

  return (
    <div className="admin-page">
      <h2>评测看板</h2>

      {summary && (
        <div className="admin-filters" style={{ gap: 24, fontSize: 14 }}>
          <span>总用例 <strong>{summary.total}</strong></span>
          <span>通过率 <strong>{summary.passRate}%</strong>（{summary.passed}/{summary.total}）</span>
          <span>坏例 <strong style={{ color: summary.badCases > 0 ? '#d4380d' : undefined }}>{summary.badCases}</strong></span>
        </div>
      )}

      <div className="admin-filters">
        <label>
          用例
          <input placeholder="如 EC-011" value={caseId} onChange={(e) => setCaseId(e.target.value)} />
        </label>
        <label>
          类别
          <select value={category} onChange={(e) => setCategory(e.target.value)}>
            <option value="">全部</option>
            {categories.map((c) => (
              <option key={c} value={c === '未分类' ? '' : c}>{c}</option>
            ))}
          </select>
        </label>
        <label>
          <input type="checkbox" checked={badCaseOnly} onChange={(e) => setBadCaseOnly(e.target.checked)} />
          只看坏例
        </label>
        <button onClick={load} disabled={loading}>{loading ? '查询中…' : '查询'}</button>
      </div>

      {error && <div className="admin-error">{error}</div>}

      {summary && summary.byCategory.length > 0 && (
        <>
          <h3>按类别通过率</h3>
          <table className="admin-table">
            <thead>
              <tr><th>类别</th><th>总数</th><th>通过</th><th>通过率</th></tr>
            </thead>
            <tbody>
              {summary.byCategory.map((g) => (
                <tr key={g.key}>
                  <td>{g.key}</td>
                  <td>{g.total}</td>
                  <td>{g.passed}</td>
                  <td>{g.passRate}%</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}

      {summary && summary.byCase.length > 0 && (
        <>
          <h3>按用例通过率</h3>
          <table className="admin-table">
            <thead>
              <tr><th>用例</th><th>总数</th><th>通过</th><th>通过率</th></tr>
            </thead>
            <tbody>
              {summary.byCase.map((g) => (
                <tr key={g.key}>
                  <td>{g.key}</td>
                  <td>{g.total}</td>
                  <td>{g.passed}</td>
                  <td>{g.passRate}%</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}

      <h3>评测记录{badCaseOnly ? '（仅坏例）' : ''}</h3>
      <table className="admin-table">
        <thead>
          <tr>
            <th>时间</th>
            <th>用例</th>
            <th>类别</th>
            <th>总分</th>
            <th>结果</th>
            <th>Trace</th>
          </tr>
        </thead>
        <tbody>
          {records.length === 0 ? (
            <tr><td colSpan={6} className="admin-empty">暂无评测记录（跑一轮 EvalCase 回归后再看）</td></tr>
          ) : (
            records.map((r) => (
              <tr key={r.id}>
                <td>{r.createdAt?.replace('T', ' ').slice(0, 19)}</td>
                <td>{r.caseId ?? '（线上评估）'}</td>
                <td>{r.category ?? '未分类'}</td>
                <td>{r.totalScore ?? '-'}</td>
                <td style={{ color: r.isBadCase ? '#d4380d' : '#389e0d' }}>
                  {r.isBadCase ? '✗ 坏例' : '✓ 通过'}
                </td>
                <td>{r.traceId ? <code>{r.traceId.slice(0, 12)}…</code> : '-'}</td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  )
}
