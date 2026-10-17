import { buildTrace } from './traceModel'
import type { TraceRow } from './traceModel'
import { useEffect, useMemo, useState } from 'react'
import { Badge, CopyId, Empty, MetricRows, RawData, ResourceState } from './common'
import { agentNames, cost, date, duration, failedSpan, json, kindNames, number, parse, phaseName, useResource } from './model'
import type { Payload, Span } from './model'

export default function Trace({ id, revision, spanId, focus, onSelect }: { id: string; revision: number; spanId: string | null; focus?: string | null; onSelect: (id: string) => void }) {
  const resource = useResource<Span[]>(`/runs/${encodeURIComponent(id)}/spans`, revision)
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set()), [onlyErrors, setOnlyErrors] = useState(focus === 'errors'), [view, setView] = useState('tree')
  const spans = resource.data
  const rows = useMemo(() => buildTrace(spans ?? []), [spans])
  const failures = rows.filter(row => failedSpan(row.span)).sort((a, b) => (Date.parse(a.span.ended_at ?? a.span.started_at) || 0) - (Date.parse(b.span.ended_at ?? b.span.started_at) || 0))
  const selected = rows.find(row => row.span.id === spanId) ?? (focus === 'errors' ? failures[0] : undefined) ?? rows[0]
  const visible = rows.filter(row => {
    if (onlyErrors && !failedSpan(row.span) && !failures.some(failure => failure.ancestors.includes(row.span.id))) return false
    return !row.ancestors.some(parent => collapsed.has(parent) && !selected?.ancestors.includes(parent))
  })
  const starts = rows.map(row => Date.parse(row.span.started_at)).filter(Number.isFinite)
  const start = Math.min(...starts), end = Math.max(...rows.map(row => { const s = Date.parse(row.span.started_at); return Number.isFinite(s) ? s + Math.max(0, row.span.duration_ms ?? 0) : 0 }))
  const scale = Math.max(1, end - start)
  useEffect(() => { if (spanId) document.getElementById(`as-span-${spanId}`)?.scrollIntoView({ block: 'nearest', inline: 'nearest' }) }, [spanId, spans])
  function jump(row?: TraceRow) { if (!row) return; setOnlyErrors(false); setCollapsed(previous => new Set([...previous].filter(id => !row.ancestors.includes(id)))); onSelect(row.span.id) }
  function toggle(row: TraceRow) { if (selected?.ancestors.includes(row.span.id)) onSelect(row.span.id); setCollapsed(previous => { const next = new Set(previous); if (next.has(row.span.id)) next.delete(row.span.id); else next.add(row.span.id); return next }) }
  return <>
    <div className="as-trace-toolbar"><div className="as-segment" role="group" aria-label="调用链视图"><button aria-pressed={view === 'tree'} onClick={() => setView('tree')}>父子调用树</button><button aria-pressed={view === 'timeline'} onClick={() => setView('timeline')}>耗时时间轴</button></div><label className="as-check"><input type="checkbox" checked={onlyErrors} onChange={e => setOnlyErrors(e.target.checked)} />仅看异常及其父节点</label><button disabled={!failures.length} onClick={() => jump(failures[0])}>定位最早失败</button><button disabled={!rows.some(row => row.span.duration_ms != null)} onClick={() => jump([...rows].filter(row => row.span.duration_ms != null).sort((a, b) => b.span.duration_ms! - a.span.duration_ms!)[0])}>定位耗时最长</button><button onClick={() => setCollapsed(new Set())}>全部展开</button></div>
    <p className="as-footnote">{rows.length} 个调用节点 · {failures.length} 个异常节点。模型请求组用于组织请求与重试，不重复累计 Token。Agent 耗时包含子调用和等待，父子耗时不能相加。</p>
    <ResourceState {...resource} hasData={Boolean(spans)} />
    {spans && !rows.length && <Empty title="暂无调用链记录">此记录可能没有采集调用过程；历史缺失数据无法恢复。</Empty>}
    {spans && rows.length > 0 && <div className="as-trace-layout"><div className="as-trace-main"><div className="as-table-wrap as-trace-scroll"><table className="as-table as-trace-table"><thead><tr><th>类型 / 实际名称与父子关系</th><th>阶段 / 状态</th><th className="as-num">耗时</th>{view === 'timeline' && <th>实际时间轴</th>}<th className="as-num">输入 Token</th><th className="as-num">输出 Token</th><th className="as-num">已记录费用</th><th>重试</th></tr></thead><tbody>{visible.map(row => {
      const span = row.span, aggregate = span.kind === 'AGENT' || span.kind === 'MODEL_CALL'
      const name = span.kind === 'MODEL' ? span.model || span.name : span.name
      const offset = (Date.parse(span.started_at) - start) / scale * 100
      return <tr id={`as-span-${span.id}`} key={span.id} className={`${selected?.span.id === span.id ? 'as-selected-row ' : ''}${failedSpan(span) ? 'as-trace-failed' : ''}`}><td className="as-trace-name"><div style={{ paddingLeft: row.depth * 18 }}><button className="as-tree-toggle" aria-label={`${collapsed.has(span.id) ? '展开' : '折叠'} ${name}`} disabled={!row.children} aria-expanded={row.children ? !collapsed.has(span.id) : undefined} onClick={() => toggle(row)}>{row.children ? collapsed.has(span.id) ? '▸' : '▾' : '·'}</button><button className="as-node-link" aria-label={`查看调用节点 ${span.id}`} aria-pressed={selected?.span.id === span.id} onClick={() => onSelect(span.id)}><span>{kindNames[span.kind] ?? span.kind}{agentNames[span.name] ? ` · ${agentNames[span.name]}` : ''}</span><code>{name || '名称未记录'}</code></button></div>{row.malformed && <small>父节点缺失或关系异常，已保留本节点</small>}</td><td><small>{phaseName(span.phase)}</small><Badge status={span.status} label={span.status === 'ERROR' ? '调用失败' : undefined} /></td><td className="as-num">{duration(span.duration_ms)}</td>{view === 'timeline' && <td><div className="as-trace-axis">{Number.isFinite(offset) && span.duration_ms != null && <span style={{ left: `${Math.max(0, offset)}%`, width: `${Math.min(100 - offset, Math.max(.5, span.duration_ms / scale * 100))}%` }} />}</div><small>{date(span.started_at)}</small></td>}<td className="as-num">{span.kind === 'TOOL' ? '不适用' : number(aggregate ? span.aggregate_input_tokens : span.input_tokens)}{aggregate && <small>子调用汇总</small>}</td><td className="as-num">{span.kind === 'TOOL' ? '不适用' : number(aggregate ? span.aggregate_output_tokens : span.output_tokens)}</td><td className="as-num">{cost(aggregate ? span.aggregate_cost : span.cost)}<small>{aggregate ? '子调用汇总' : '本次调用'}{(aggregate ? span.aggregate_cost_complete : span.cost_complete) !== true ? ' · 不完整' : ''}</small></td><td>{span.attempt_index != null ? `第 ${span.attempt_index} 次` : '—'}{span.attempt_group && <small>同组重试</small>}</td></tr>
    })}</tbody></table></div>{!visible.length && <Empty title="未观测到异常调用" />}</div>{selected && <NodeDetails key={selected.span.id} row={selected} revision={revision} />}</div>}
  </>
}

function NodeDetails({ row, revision }: { row: TraceRow; revision: number }) {
  const span = row.span, aggregate = span.kind === 'AGENT' || span.kind === 'MODEL_CALL'
  const resource = useResource<Payload[]>(`/spans/${encodeURIComponent(span.id)}/payload`, revision)
  const payloads = resource.data ?? []
  const errors = payloads.filter(payload => payload.kind === 'ERROR')
  const business = payloads.filter(payload => payload.kind?.startsWith('OUTPUT')).map(payload => parse<unknown>(payload.body)).filter((body): body is { error: string } => Boolean(body && typeof body === 'object' && 'error' in body && typeof body.error === 'string' && body.error))
  const name = span.kind === 'MODEL' ? span.model || span.name : span.name
  return <aside className="as-node-details" aria-label="所选调用节点详情"><h3>节点详情</h3><div className="as-node-title"><span>{kindNames[span.kind] ?? span.kind} · {phaseName(span.phase)}</span><code>{name}</code><Badge status={span.status} label={span.status === 'ERROR' ? '调用失败' : undefined} /></div>
    <MetricRows items={[{ label: '本次耗时', value: duration(span.duration_ms) }, { label: '开始时间', value: date(span.started_at) }, { label: '输入 Token', value: span.kind === 'TOOL' ? '不适用' : number(aggregate ? span.aggregate_input_tokens : span.input_tokens) }, { label: '输出 Token', value: span.kind === 'TOOL' ? '不适用' : number(aggregate ? span.aggregate_output_tokens : span.output_tokens) }, { label: 'Token 完整情况', value: span.kind === 'TOOL' ? '不适用' : (aggregate ? span.aggregate_usage_complete : span.usage_kind === 'ACTUAL') ? '完整' : '未完整记录' }, { label: aggregate ? '子调用费用' : '本次费用', value: cost(aggregate ? span.aggregate_cost : span.cost) }]} />
    {(failedSpan(span) || errors.length > 0) && <section className="as-node-error" aria-label="调用错误详情"><h4>调用错误</h4><strong>{span.error_type || '调用未正常完成'}</strong>{errors.map(error => { const body = parse<{ message?: string; type?: string }>(error.body); return <p key={error.id}>{body?.message || error.body}</p> })}{!errors.length && !resource.loading && <p>已记录失败状态，详细错误正文未保存或已超过保留期。</p>}</section>}
    {business.map((body, i) => <section className="as-node-error" key={i}><h4>工具返回业务错误</h4><p>{body.error}</p><small>原始调用状态保持不变，请结合返回内容判断。</small></section>)}
    {span.attempt_group && <CopyId value={span.attempt_group} label="重试组" />}
    {span.request_id && <CopyId value={span.request_id} label="请求编号" />}
    <CopyId value={span.id} label="节点编号" />{span.parent_id && <CopyId value={span.parent_id} label="父节点" />}
    <ResourceState {...resource} hasData={Boolean(resource.data)} />
    {resource.data && !payloads.length && <p className="as-footnote">调用正文未记录或已超过 30 天保留期。耗时、状态和已记录用量仍可查看。</p>}
    {payloads.filter(payload => payload.kind !== 'ERROR').map(payload => <section className="as-node-payload" key={payload.id}><h4>{payload.kind === 'INPUT' ? '输入内容' : payload.kind === 'OUTPUT_DATA' ? '结构化返回' : '返回内容'}（已脱敏）</h4><pre>{formatBody(payload.body)}</pre></section>)}
    <RawData value={span} title="节点原始字段" />
  </aside>
}
function formatBody(body: string) { const parsed = parse<unknown>(body); return parsed == null ? body : typeof parsed === 'string' ? parsed : json(parsed) }
