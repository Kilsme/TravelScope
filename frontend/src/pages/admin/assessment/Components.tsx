import { useState } from 'react'
import { Empty, Panel, ResourceState } from './common'
import { agentNames, cost, duration, number, phaseName, useResource } from './model'
import type { ComponentMetric, Source, ToolMode } from './model'

export function ComponentName({ kind, name }: { kind: string; name: string }) {
  return <span className="as-component-name">{kind === 'AGENT' && agentNames[name] && <span>{agentNames[name]}</span>}<code>{name || '名称未记录'}</code></span>
}

export default function Components({ source, mode, revision, batchId, onInspect }: { source: Source; mode: ToolMode; revision: number; batchId?: string; onInspect: (component: ComponentMetric) => void }) {
  const [kind, setKind] = useState('AGENT'), [phase, setPhase] = useState('PLANNING')
  const query = new URLSearchParams({ source, kind, phase })
  if (source === 'ASSESSMENT') query.set('toolMode', mode)
  if (batchId) query.set('batchId', batchId)
  const resource = useResource<ComponentMetric[]>(`/components?${query}`, revision)
  return <Panel title="组件性能" subtitle="按实际 Agent、模型与工具统计，点击明细可追溯到运行记录。">
    <div className="as-component-controls"><div className="as-segment" role="group" aria-label="组件类型">{[['AGENT', 'Agent'], ['MODEL', '模型'], ['TOOL', '工具']].map(([id, label]) => <button key={id} aria-pressed={kind === id} onClick={() => setKind(id)}>{label}</button>)}</div><label className="as-inline-label">统计阶段<select aria-label="组件统计阶段" value={phase} onChange={e => setPhase(e.target.value)}><option value="PLANNING">规划阶段</option><option value="SCORING">评分阶段</option></select></label></div>
    <ResourceState {...resource} hasData={Boolean(resource.data)} />
    {resource.data && (resource.data.length ? <div className="as-table-wrap"><table className="as-table as-component-table"><thead><tr><th>实际名称</th><th className="as-num">调用 / 成功 / 失败</th><th className="as-num">平均耗时</th><th className="as-num">P50</th><th className="as-num">P95</th><th className="as-num">失败平均耗时</th><th className="as-num">输入 Token</th><th className="as-num">输出 Token</th><th className="as-num">已记录费用</th><th>操作</th></tr></thead><tbody>{resource.data.map(row => <tr key={`${row.kind}:${row.component_name}:${row.phase}`}><td><ComponentName kind={row.kind} name={row.component_name} /><small>{phaseName(row.phase)} · 耗时样本 {number(row.duration_samples)}{row.data_complete === false ? ' · 采集不完整' : ''}</small></td><td className="as-num">{number(row.call_count)} / {number(row.succeeded)} / {number(row.failed)}{Boolean(row.cancelled) && <small>取消 {row.cancelled}</small>}</td><td className="as-num">{duration(row.mean_ms)}</td><td className="as-num">{duration(row.p50_ms)}</td><td className="as-num">{duration(row.p95_ms)}</td><td className="as-num">{duration(row.failed_mean_ms)}</td><td className="as-num">{row.kind === 'TOOL' ? '不适用' : number(row.input_tokens)}{row.kind !== 'TOOL' && row.usage_complete !== true && <small>仅已记录部分</small>}</td><td className="as-num">{row.kind === 'TOOL' ? '不适用' : number(row.output_tokens)}</td><td className="as-num">{cost(row.known_cost)}{row.cost_complete !== true && <small>费用不完整</small>}</td><td><button className="as-link" aria-label={`查看组件 ${row.component_name} 的运行记录`} onClick={() => onInspect(row)}>运行明细 →</button></td></tr>)}</tbody></table></div> : <Empty title="当前范围暂无组件记录">切换组件类型、统计阶段，或检查运行记录采集情况。</Empty>)}
    <p className="as-footnote">平均耗时、P50、P95 仅基于成功且已结束的调用。失败耗时另列。Agent 耗时与 Token 包含子调用，不能将父子汇总相加；模型请求组不重复计入实际模型调用。</p>
  </Panel>
}
