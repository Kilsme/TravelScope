import { useState } from 'react'
import { ComponentName } from './Components'
import { comparisonMetrics, formatMetric } from './comparisonModel'
import type { Format } from './comparisonModel'
import { Badge, Empty, Hint, RawData, ResourceState } from './common'
import { cost, date, duration, endedStatus, finite, json, modeName, number, parse, shortId, useResource } from './model'
import type { Batch, Comparison, ComparisonRow, ComponentMetric } from './model'

const configLabels: Record<string, string> = { dateBase: '测试日期基准', datePolicy: '日期转换规则', scorer: '评分版本', rules: '检查规则版本', priceVersion: '价格版本', codeVersion: '代码版本', codeFingerprint: '构建指纹', models: '模型设置', promptVersion: '提示词版本', skillVersion: '技能版本', caseVersion: '场景版本', cases: '场景内容', toolMode: '工具运行方式' }
function Delta({ a, b, kind, complete = true }: { a: unknown; b: unknown; kind: Format; complete?: boolean }) {
  if (!finite(a) || !finite(b)) return <span className="as-muted">无法计算</span>
  const delta = b - a, sign = delta > 0 ? '+' : delta < 0 ? '−' : ''
  const value = kind === 'rate' ? `${(Math.abs(delta) * 100).toFixed(1)} 个百分点` : kind === 'duration' ? duration(Math.abs(delta)) : kind === 'cost' ? cost(Math.abs(delta)) : number(Math.abs(delta))
  const better = kind === 'rate' ? delta > 0 : delta < 0
  return <span className={complete && delta && kind !== 'number' ? better ? 'as-change-positive' : 'as-change-negative' : 'as-muted'}>{sign}{value}{!complete && <small>记录不完整，仅比较已记录部分</small>}</span>
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
    <div className="as-section-head as-view-head"><div><h2>批次对比</h2><p>在相同工具模式下，对比两个已结束任务的共同场景。</p></div></div>
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
      <ComponentComparison rows={result.data.components} baselineId={baselineId} candidateId={candidateId} />
      <details className="as-disclosure"><summary>查看配置差异明细</summary>{diffs.length ? <div className="as-table-wrap"><table className="as-table"><thead><tr><th>配置项</th><th>原测试</th><th>新测试</th></tr></thead><tbody>{diffs.map(key => <tr key={key}><td>{configLabels[key] ?? key}</td><td><pre>{json(a?.[key])}</pre></td><td><pre>{json(b?.[key])}</pre></td></tr>)}</tbody></table></div> : <p className="as-footnote">没有可解析的字段差异。</p>}</details>
      <RawData value={result.data} title="完整配置快照与对比指标" />
    </>}
  </>
}

function ScenarioRows({ id, a, b }: { id: string; a?: ComparisonRow; b?: ComparisonRow }) {
  return <><tr className="as-case-heading"><th colSpan={4}>{id}<span>样本 {a?.samples ?? '—'} / {b?.samples ?? '—'} · 完整规划 {a?.completed_plans ?? '—'} / {b?.completed_plans ?? '—'} · 评分成功 {a?.score_count ?? '—'} / {b?.score_count ?? '—'}</span></th></tr>{comparisonMetrics.map(item => {
    const completeA = item.partial === 'tokens' ? a?.usage_complete === true : item.partial === 'cost' ? a?.cost_complete === true : true
    const completeB = item.partial === 'tokens' ? b?.usage_complete === true : item.partial === 'cost' ? b?.cost_complete === true : true
    return <tr key={item.key}><td>{item.label}{item.key === 'p50_ms' && <Hint>仅统计最终完整规划轮次的中位耗时，不是各轮耗时之和。</Hint>}</td><td className="as-num">{formatMetric(item.format, a?.[item.key])}{!completeA && <small>仅已记录部分</small>}</td><td className="as-num">{formatMetric(item.format, b?.[item.key])}{!completeB && <small>仅已记录部分</small>}</td><td className="as-num"><Delta a={a?.[item.key]} b={b?.[item.key]} kind={item.format} complete={completeA && completeB} /></td></tr>
  })}</>
}

function ComponentComparison({ rows, baselineId, candidateId }: { rows?: ComponentMetric[]; baselineId: string; candidateId: string }) {
  const [kind, setKind] = useState('AGENT'), [phase, setPhase] = useState('PLANNING'), [chosenCase, setChosenCase] = useState('')
  const cases = [...new Set(rows?.map(row => row.case_id).filter((id): id is string => Boolean(id)) ?? [])]
  const caseId = cases.includes(chosenCase) ? chosenCase : cases[0]
  const selected = rows?.filter(row => row.kind === kind && row.phase === phase && row.case_id === caseId) ?? []
  const names = [...new Set(selected.map(row => row.component_name))]
  const metrics: { key: keyof ComponentMetric; label: string; format: Format }[] = [
    { key: 'call_count', label: '调用次数', format: 'number' }, { key: 'failed', label: '失败次数', format: 'number' },
    { key: 'duration_samples', label: '成功耗时样本数', format: 'number' }, { key: 'mean_ms', label: '平均耗时', format: 'duration' },
    { key: 'p50_ms', label: 'P50 调用耗时', format: 'duration' }, { key: 'p95_ms', label: 'P95 调用耗时', format: 'duration' },
    { key: 'failed_mean_ms', label: '失败平均耗时', format: 'duration' },
    { key: 'input_tokens', label: '输入 Token', format: 'number' }, { key: 'output_tokens', label: '输出 Token', format: 'number' }, { key: 'known_cost', label: '已记录费用', format: 'cost' },
  ]
  return <section className="as-panel as-component-comparison"><header className="as-section-head"><div><h2>组件性能对比</h2><p>在共同场景内比较实际组件；一侧未观测到的节点保留未知。</p></div></header>
    <div className="as-component-controls"><label className="as-inline-label">共同场景<select aria-label="组件对比场景" value={caseId ?? ''} onChange={e => setChosenCase(e.target.value)}>{cases.map(id => <option key={id} value={id}>{id}</option>)}</select></label><div className="as-segment" role="group" aria-label="对比组件类型">{[['AGENT', 'Agent'], ['MODEL', '模型'], ['TOOL', '工具']].map(([id, label]) => <button key={id} aria-pressed={kind === id} onClick={() => setKind(id)}>{label}</button>)}</div><label className="as-inline-label">阶段<select aria-label="组件对比阶段" value={phase} onChange={e => setPhase(e.target.value)}><option value="PLANNING">规划</option><option value="SCORING">评分</option></select></label></div>
    {!rows ? <p className="as-notice">暂无组件对比统计，请确认后端已更新。不会用零替代缺失数据。</p> : !names.length ? <Empty title="当前选择暂无组件对比记录" /> : <div className="as-table-wrap"><table className="as-table as-comparison-table"><thead><tr><th>组件 / 指标</th><th className="as-num">原测试</th><th className="as-num">新测试</th><th className="as-num">变化</th></tr></thead><tbody>{names.map(name => {
      const a = selected.find(row => row.component_name === name && row.batch_id === baselineId), b = selected.find(row => row.component_name === name && row.batch_id === candidateId)
      return <ComponentRows key={name} name={name} kind={kind} a={a} b={b} metrics={metrics} />
    })}</tbody></table></div>}
    <p className="as-footnote">组件 P50/P95 基于成功调用，失败另列；Agent 的 Token 与费用是子调用汇总，不与子节点重复相加。缺少节点或采集不完整时，无法据此确认组件未调用。</p>
  </section>
}
function ComponentRows({ name, kind, a, b, metrics }: { name: string; kind: string; a?: ComponentMetric; b?: ComponentMetric; metrics: { key: keyof ComponentMetric; label: string; format: Format }[] }) {
  return <><tr className="as-case-heading"><th colSpan={4}><ComponentName kind={kind} name={name} />{(a?.data_complete === false || b?.data_complete === false) && <span>存在采集不完整的记录</span>}</th></tr>{metrics.map(item => {
    const token = item.key === 'input_tokens' || item.key === 'output_tokens'
    if (token && kind === 'TOOL') return <tr key={item.key}><td>{item.label}</td><td className="as-num">不适用</td><td className="as-num">不适用</td><td className="as-num">—</td></tr>
    const completeA = token ? a?.usage_complete === true : item.key === 'known_cost' ? a?.cost_complete === true : true
    const completeB = token ? b?.usage_complete === true : item.key === 'known_cost' ? b?.cost_complete === true : true
    return <tr key={item.key}><td>{item.label}</td><td className="as-num">{a ? formatMetric(item.format, a[item.key]) : '未观测到'}{a && !completeA && <small>仅已记录部分</small>}</td><td className="as-num">{b ? formatMetric(item.format, b[item.key]) : '未观测到'}{b && !completeB && <small>仅已记录部分</small>}</td><td className="as-num"><Delta a={a?.[item.key]} b={b?.[item.key]} kind={item.format} complete={completeA && completeB} /></td></tr>
  })}</>
}
