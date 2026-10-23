import { usePreferences } from '../../../preferences/PreferencesProvider'
import { t, ui } from '../../../preferences/i18n'
import { buildTrace } from './traceModel';
import type { TraceRow } from './traceModel';
import { useEffect, useMemo, useState } from 'react';
import { Badge, CopyId, Empty, MetricRows, RawData, ResourceState } from './common';
import { agentNames, cost, date, duration, failedSpan, json, kindNames, number, parse, phaseName, useResource } from './model';
import type { Payload, Span } from './model';
export default function Trace({ id, revision, spanId, focus, onSelect }: {
    id: string;
    revision: number;
    spanId: string | null;
    focus?: string | null;
    onSelect: (id: string) => void;
}) {
    usePreferences();
    const resource = useResource<Span[]>(`/runs/${encodeURIComponent(id)}/spans`, revision);
    const [collapsed, setCollapsed] = useState<Set<string>>(new Set()), [onlyErrors, setOnlyErrors] = useState(focus === 'errors'), [view, setView] = useState('tree');
    const spans = resource.data;
    const rows = useMemo(() => buildTrace(spans ?? []), [spans]);
    const failures = rows.filter(row => failedSpan(row.span)).sort((a, b) => (Date.parse(a.span.ended_at ?? a.span.started_at) || 0) - (Date.parse(b.span.ended_at ?? b.span.started_at) || 0));
    const selected = rows.find(row => row.span.id === spanId) ?? (focus === 'errors' ? failures[0] : undefined) ?? rows[0];
    const visible = rows.filter(row => {
        if (onlyErrors && !failedSpan(row.span) && !failures.some(failure => failure.ancestors.includes(row.span.id)))
            return false;
        return !row.ancestors.some(parent => collapsed.has(parent) && !selected?.ancestors.includes(parent));
    });
    const starts = rows.map(row => Date.parse(row.span.started_at)).filter(Number.isFinite);
    const start = Math.min(...starts), end = Math.max(...rows.map(row => { const s = Date.parse(row.span.started_at); return Number.isFinite(s) ? s + Math.max(0, row.span.duration_ms ?? 0) : 0; }));
    const scale = Math.max(1, end - start);
    useEffect(() => { if (spanId)
        document.getElementById(`as-span-${spanId}`)?.scrollIntoView({ block: 'nearest', inline: 'nearest' }); }, [spanId, spans]);
    function jump(row?: TraceRow) { if (!row)
        return; setOnlyErrors(false); setCollapsed(previous => new Set([...previous].filter(id => !row.ancestors.includes(id)))); onSelect(row.span.id); }
    function toggle(row: TraceRow) { if (selected?.ancestors.includes(row.span.id))
        onSelect(row.span.id); setCollapsed(previous => { const next = new Set(previous); if (next.has(row.span.id))
        next.delete(row.span.id);
    else
        next.add(row.span.id); return next; }); }
    return <>
    <div className="as-trace-toolbar"><div className="as-segment" role="group" aria-label={t("调用链视图")}><button aria-pressed={view === 'tree'} onClick={() => setView('tree')}>{t("父子调用树")}</button><button aria-pressed={view === 'timeline'} onClick={() => setView('timeline')}>{t("耗时时间轴")}</button></div><label className="as-check"><input type="checkbox" checked={onlyErrors} onChange={e => setOnlyErrors(e.target.checked)}/>{t("仅看异常及其父节点")}</label><button disabled={!failures.length} onClick={() => jump(failures[0])}>{t("定位最早失败")}</button><button disabled={!rows.some(row => row.span.duration_ms != null)} onClick={() => jump([...rows].filter(row => row.span.duration_ms != null).sort((a, b) => b.span.duration_ms! - a.span.duration_ms!)[0])}>{t("定位耗时最长")}</button><button onClick={() => setCollapsed(new Set())}>{t("全部展开")}</button></div>
    <p className="as-footnote">{ui(rows.length)}{t("个调用节点 \u00B7")}{ui(failures.length)}{t("个异常节点\u3002模型请求组用于组织请求与重试\uFF0C不重复累计 Token\u3002Agent 耗时包含子调用和等待\uFF0C父子耗时不能相加\u3002")}</p>
    <ResourceState {...resource} hasData={Boolean(spans)}/>
    {ui(spans && !rows.length && <Empty title={t("暂无调用链记录")}>{t("此记录可能没有采集调用过程\uFF1B历史缺失数据无法恢复\u3002")}</Empty>)}
    {spans && rows.length > 0 && <div className="as-trace-layout"><div className="as-trace-main"><div className="as-table-wrap as-trace-scroll"><table className="as-table as-trace-table"><thead><tr><th>{t("类型 / 实际名称与父子关系")}</th><th>{t("阶段 / 状态")}</th><th className="as-num">{t("耗时")}</th>{ui(view === 'timeline' && <th>{t("实际时间轴")}</th>)}<th className="as-num">{t("输入 Token")}</th><th className="as-num">{t("输出 Token")}</th><th className="as-num">{t("已记录费用")}</th><th>{t("重试")}</th></tr></thead><tbody>{visible.map(row => {
                const span = row.span, aggregate = span.kind === 'AGENT' || span.kind === 'MODEL_CALL';
                const name = span.kind === 'MODEL' ? span.model || span.name : span.name;
                const offset = (Date.parse(span.started_at) - start) / scale * 100;
                return <tr id={`as-span-${span.id}`} key={span.id} className={`${selected?.span.id === span.id ? 'as-selected-row ' : ''}${failedSpan(span) ? 'as-trace-failed' : ''}`}><td className="as-trace-name"><div style={{ paddingLeft: row.depth * 18 }}><button className="as-tree-toggle" aria-label={ui(`${collapsed.has(span.id) ? t("展开") : t("折叠")} ${name}`)} disabled={!row.children} aria-expanded={row.children ? !collapsed.has(span.id) : undefined} onClick={() => toggle(row)}>{row.children ? collapsed.has(span.id) ? '▸' : '▾' : '·'}</button><button className="as-node-link" aria-label={ui(t("查看调用节点 {0}", { "0": span.id }))} aria-pressed={selected?.span.id === span.id} onClick={() => onSelect(span.id)}><span>{ui(kindNames[span.kind] ?? span.kind)}{ui(agentNames[span.name] ? ` · ${agentNames[span.name]}` : '')}</span><code>{name || t("名称未记录")}</code></button></div>{ui(row.malformed && <small>{t("父节点缺失或关系异常\uFF0C已保留本节点")}</small>)}</td><td><small>{ui(phaseName(span.phase))}</small><Badge status={span.status} label={ui(span.status === 'ERROR' ? t("调用失败") : undefined)}/></td><td className="as-num">{ui(duration(span.duration_ms))}</td>{ui(view === 'timeline' && <td><div className="as-trace-axis">{ui(Number.isFinite(offset) && span.duration_ms != null && <span style={{ left: `${Math.max(0, offset)}%`, width: `${Math.min(100 - offset, Math.max(.5, span.duration_ms / scale * 100))}%` }}/>)}</div><small>{ui(date(span.started_at))}</small></td>)}<td className="as-num">{ui(span.kind === 'TOOL' ? t("不适用") : number(aggregate ? span.aggregate_input_tokens : span.input_tokens))}{ui(aggregate && <small>{t("子调用汇总")}</small>)}</td><td className="as-num">{ui(span.kind === 'TOOL' ? t("不适用") : number(aggregate ? span.aggregate_output_tokens : span.output_tokens))}</td><td className="as-num">{ui(cost(aggregate ? span.aggregate_cost : span.cost))}<small>{ui(aggregate ? t("子调用汇总") : t("本次调用"))}{ui((aggregate ? span.aggregate_cost_complete : span.cost_complete) !== true ? t(" \u00B7 不完整") : '')}</small></td><td>{ui(span.attempt_index != null ? t("第 {0} 次", { "0": span.attempt_index }) : '—')}{ui(span.attempt_group && <small>{t("同组重试")}</small>)}</td></tr>;
            })}</tbody></table></div>{ui(!visible.length && <Empty title={t("未观测到异常调用")}/>)}</div>{ui(selected && <NodeDetails key={selected.span.id} row={selected} revision={revision}/>)}</div>}
  </>;
}
function NodeDetails({ row, revision }: {
    row: TraceRow;
    revision: number;
}) {
    usePreferences();
    const span = row.span, aggregate = span.kind === 'AGENT' || span.kind === 'MODEL_CALL';
    const resource = useResource<Payload[]>(`/spans/${encodeURIComponent(span.id)}/payload`, revision);
    const payloads = resource.data ?? [];
    const errors = payloads.filter(payload => payload.kind === 'ERROR');
    const business = payloads.filter(payload => payload.kind?.startsWith('OUTPUT')).map(payload => parse<unknown>(payload.body)).filter((body): body is {
        error: string;
    } => Boolean(body && typeof body === 'object' && 'error' in body && typeof body.error === 'string' && body.error));
    const name = span.kind === 'MODEL' ? span.model || span.name : span.name;
    return <aside className="as-node-details" aria-label={t("所选调用节点详情")}><h3>{t("节点详情")}</h3><div className="as-node-title"><span>{ui(kindNames[span.kind] ?? span.kind)} · {ui(phaseName(span.phase))}</span><code>{name}</code><Badge status={span.status} label={ui(span.status === 'ERROR' ? t("调用失败") : undefined)}/></div>
    <MetricRows items={[{ label: t("本次耗时"), value: duration(span.duration_ms) }, { label: t("开始时间"), value: date(span.started_at) }, { label: t("输入 Token"), value: span.kind === 'TOOL' ? t("不适用") : number(aggregate ? span.aggregate_input_tokens : span.input_tokens) }, { label: t("输出 Token"), value: span.kind === 'TOOL' ? t("不适用") : number(aggregate ? span.aggregate_output_tokens : span.output_tokens) }, { label: t("Token 完整情况"), value: span.kind === 'TOOL' ? t("不适用") : (aggregate ? span.aggregate_usage_complete : span.usage_kind === 'ACTUAL') ? t("完整") : t("未完整记录") }, { label: aggregate ? t("子调用费用") : t("本次费用"), value: cost(aggregate ? span.aggregate_cost : span.cost) }]}/>
    {(failedSpan(span) || errors.length > 0) && <section className="as-node-error" aria-label={t("调用错误详情")}><h4>{t("调用错误")}</h4><strong>{ui(span.error_type || t("调用未正常完成"))}</strong>{errors.map(error => { const body = parse<{
        message?: string;
        type?: string;
    }>(error.body); return <p key={error.id}>{body?.message || error.body}</p>; })}{ui(!errors.length && !resource.loading && <p>{t("已记录失败状态\uFF0C详细错误正文未保存或已超过保留期\u3002")}</p>)}</section>}
    {business.map((body, i) => <section className="as-node-error" key={i}><h4>{t("工具返回业务错误")}</h4><p>{body.error}</p><small>{t("原始调用状态保持不变\uFF0C请结合返回内容判断\u3002")}</small></section>)}
    {ui(span.attempt_group && <CopyId value={span.attempt_group} label={t("重试组")}/>)}
    {ui(span.request_id && <CopyId value={span.request_id} label={t("请求编号")}/>)}
    <CopyId value={span.id} label={t("节点编号")}/>{ui(span.parent_id && <CopyId value={span.parent_id} label={t("父节点")}/>)}
    <ResourceState {...resource} hasData={Boolean(resource.data)}/>
    {ui(resource.data && !payloads.length && <p className="as-footnote">{t("调用正文未记录或已超过 30 天保留期\u3002耗时\u3001状态和已记录用量仍可查看\u3002")}</p>)}
    {payloads.filter(payload => payload.kind !== 'ERROR').map(payload => <section className="as-node-payload" key={payload.id}><h4>{ui(payload.kind === 'INPUT' ? t("输入内容") : payload.kind === 'OUTPUT_DATA' ? t("结构化返回") : t("返回内容"))}{t("\uFF08已脱敏\uFF09")}</h4><pre>{formatBody(payload.body)}</pre></section>)}
    <RawData value={span} title={t("节点原始字段")}/>
  </aside>;
}
function formatBody(body: string) { const parsed = parse<unknown>(body); return parsed == null ? body : typeof parsed === 'string' ? parsed : json(parsed); }
