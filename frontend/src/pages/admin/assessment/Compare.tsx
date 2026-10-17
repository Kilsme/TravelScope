import { Badge, Empty, Hint, RawData, ResourceState } from './common'
import { cost, date, duration, endedStatus, finite, json, modeName, parse, percent, shortId, useResource } from './model'
import type { Batch, Comparison, ComparisonRow } from './model'

const configLabels: Record<string, string> = { dateBase: '测试日期基准', datePolicy: '日期转换规则', scorer: '评分版本', rules: '检查规则版本', priceVersion: '价格版本', codeVersion: '代码版本', codeFingerprint: '构建指纹', models: '模型设置', promptVersion: '提示词版本', skillVersion: '技能版本', caseVersion: '场景版本', cases: '场景内容', toolMode: '工具运行方式' }
function Delta({ a, b, kind, complete = true }: { a: unknown; b: unknown; kind: 'rate' | 'duration' | 'cost'; complete?: boolean }) {
  if (!finite(a) || !finite(b)) return <span className="as-muted">无法计算</span>
  const delta = b - a, sign = delta > 0 ? '+' : delta < 0 ? '−' : ''
  const value = kind === 'rate' ? `${(Math.abs(delta) * 100).toFixed(1)} 个百分点` : kind === 'duration' ? duration(Math.abs(delta)) : cost(Math.abs(delta))
  const better = kind === 'rate' ? delta > 0 : delta < 0
  return <span className={complete && delta ? better ? 'as-change-positive' : 'as-change-negative' : 'as-muted'}>{sign}{value}{!complete && <small>记录不完整，仅比较已记录部分</small>}</span>
}
export default function Compare({ params, update, revision }: { params: URLSearchParams; update: (patch: Record<string, string | null>) => void; revision: number }) {
  const batches = useResource<Batch[]>('/batches', revision)
  const baselineId = params.get('baselineId') ?? '', candidateId = params.get('candidateId') ?? ''
  const ended = batches.data?.filter(batch => endedStatus(batch.status)) ?? []
  const baseline = ended.find(batch => batch.id === baselineId), candidate = ended.find(batch => batch.id === candidateId)
  const valid = Boolean(baseline && candidate && baselineId !== candidateId && baseline.tool_mode === candidate.tool_mode)
  const result = useResource<Comparison>(valid ? `/comparisons?${new URLSearchParams({ baselineId, candidateId })}` : null, revision)
  const rows = result.data?.scenarios ?? []
  const caseIds = [...new Set(rows.map(row => row.case_id))]
  const configA = parse<object>(result.data?.baseline.config_json), configB = parse<object>(result.data?.candidate.config_json)
  const a = configA as Record<string, unknown> | null, b = configB as Record<string, unknown> | null
  const diffs = [...new Set([...Object.keys(a ?? {}), ...Object.keys(b ?? {})])].filter(key => json(a?.[key]) !== json(b?.[key]))
  const choice = (batch: Batch) => `${date(batch.created_at)} · ${modeName(batch.tool_mode)} · ${shortId(batch.id)}`
  return <>
    <div className="as-section-head as-view-head"><div><h2>结果对比</h2><p>在相同工具模式下，对比两个已结束任务的共同场景。</p></div></div>
    <ResourceState {...batches} hasData={Boolean(batches.data)} />
    <div className="as-compare-select"><label><span className="as-label-index">01</span>原测试<select aria-label="原测试" value={baselineId} onChange={e => update({ baselineId: e.target.value || null, candidateId: null })}><option value="">选择已结束的任务</option>{ended.map(batch => <option key={batch.id} value={batch.id}>{choice(batch)}</option>)}</select></label><span aria-hidden="true" className="as-compare-arrow">→</span><label><span className="as-label-index">02</span>新测试<select aria-label="新测试" value={candidateId} disabled={!baseline} onChange={e => update({ candidateId: e.target.value || null })}><option value="">选择相同运行方式的另一任务</option>{ended.filter(batch => batch.id !== baselineId && (!baseline || batch.tool_mode === baseline.tool_mode)).map(batch => <option key={batch.id} value={batch.id}>{choice(batch)}</option>)}</select></label></div>
    {baselineId && candidateId && !valid && batches.data && <p className="as-notice as-notice-warning">请选择两个已结束、编号不同且工具模式相同的测试任务。</p>}
    <ResourceState {...result} hasData={Boolean(result.data)} />
    {!baselineId && batches.data && <Empty title={ended.length < 2 ? '需要两个已结束的测试任务' : '选择两个任务开始对比'}>固定工具数据与真实工具的测试结果不能混合比较。</Empty>}
    {baseline && !candidateId && <p className="as-footnote">已选择 {modeName(baseline.tool_mode)}；新测试仅列出相同运行方式的其他已结束任务。</p>}
    {result.data && <>
      <div className="as-comparison-summary"><strong>{caseIds.length} 个共同场景</strong><Badge status={result.data.configurationDiff ? 'UNKNOWN' : 'PASS'} label={result.data.configurationDiff ? '配置存在差异' : '配置相同'} /><span className="as-muted">变化 = 新测试 − 原测试</span></div>
      {result.data.configurationDiff && <div className="as-notice as-notice-warning"><div>以下配置存在差异，请结合变化判断结果：<div className="as-diff-labels">{diffs.length ? diffs.map(key => <span key={key}>{configLabels[key] ?? '其他配置'}</span>) : <span>快照内容或序列化格式不同，请查看完整快照。</span>}</div></div></div>}
      {caseIds.length ? <div className="as-table-wrap"><table className="as-table as-comparison-table"><thead><tr><th>场景 / 指标</th><th className="as-num">原测试</th><th className="as-num">新测试</th><th className="as-num">变化</th></tr></thead><tbody>{caseIds.map(id => <ScenarioRows key={id} id={id} a={rows.find(row => row.case_id === id && row.batch_id === baselineId)} b={rows.find(row => row.case_id === id && row.batch_id === candidateId)} />)}</tbody></table></div> : <Empty title="没有可以比较的共同场景">两组测试的场景没有交集，或尚未形成可比较的记录。</Empty>}
      <details className="as-disclosure"><summary>查看配置差异明细</summary>{diffs.length ? <div className="as-table-wrap"><table className="as-table"><thead><tr><th>配置项</th><th>原测试</th><th>新测试</th></tr></thead><tbody>{diffs.map(key => <tr key={key}><td>{configLabels[key] ?? key}</td><td><pre>{json(a?.[key])}</pre></td><td><pre>{json(b?.[key])}</pre></td></tr>)}</tbody></table></div> : <p className="as-footnote">没有可解析的字段差异。</p>}</details>
      <RawData value={result.data} title="完整配置快照与对比指标" />
    </>}
  </>
}

function ScenarioRows({ id, a, b }: { id: string; a?: ComparisonRow; b?: ComparisonRow }) {
  const items: { title: string; key: 'overall_pass_rate' | 'execution_success_rate' | 'p50_ms' | 'known_cost'; kind: 'rate' | 'duration' | 'cost'; hint?: string }[] = [
    { title: '整体达标率', key: 'overall_pass_rate', kind: 'rate' }, { title: '正常执行率', key: 'execution_success_rate', kind: 'rate' },
    { title: '典型规划用时', key: 'p50_ms', kind: 'duration', hint: '仅统计完整规划的中位耗时（P50）；缺少完整行程时不计算。' }, { title: '已记录费用', key: 'known_cost', kind: 'cost' },
  ]
  return <><tr className="as-case-heading"><th colSpan={4}>{id}<span>原测试 {a?.samples ?? '—'} 个样本 · 新测试 {b?.samples ?? '—'} 个样本 · 完整规划 {a?.completed_plans ?? '—'} / {b?.completed_plans ?? '—'} · 评分失败 {a?.score_errors ?? '—'} / {b?.score_errors ?? '—'}</span></th></tr>{items.map(item => {
    const format = item.kind === 'rate' ? percent : item.kind === 'duration' ? duration : cost
    return <tr key={item.key}><td>{item.title}{item.hint && <Hint>{item.hint}</Hint>}</td><td className="as-num">{format(a?.[item.key])}{item.kind === 'cost' && a?.cost_complete !== true && <small>仅已记录部分</small>}</td><td className="as-num">{format(b?.[item.key])}{item.kind === 'cost' && b?.cost_complete !== true && <small>仅已记录部分</small>}</td><td className="as-num"><Delta a={a?.[item.key]} b={b?.[item.key]} kind={item.kind} complete={item.kind !== 'cost' || a?.cost_complete === true && b?.cost_complete === true} /></td></tr>
  })}<tr><td>其他指标</td><td colSpan={3}><RawData value={{ original: a, current: b }} title="查看该场景全部指标" /></td></tr></>
}
