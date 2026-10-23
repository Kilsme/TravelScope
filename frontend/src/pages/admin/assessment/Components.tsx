import { usePreferences } from '../../../preferences/PreferencesProvider'
import { t, ui } from '../../../preferences/i18n'
import { useState } from 'react';
import { Empty, Panel, ResourceState } from './common';
import { agentNames, cost, duration, number, phaseName, useResource } from './model';
import type { ComponentMetric, Source, ToolMode } from './model';
export function ComponentName({ kind, name }: {
    kind: string;
    name: string;
}) {
    usePreferences();
    return <span className="as-component-name">{ui(kind === 'AGENT' && agentNames[name] && <span>{ui(agentNames[name])}</span>)}<code>{name || t("名称未记录")}</code></span>;
}
export default function Components({ source, mode, revision, batchId, onInspect }: {
    source: Source;
    mode: ToolMode;
    revision: number;
    batchId?: string;
    onInspect: (component: ComponentMetric) => void;
}) {
    usePreferences();
    const [kind, setKind] = useState('AGENT'), [phase, setPhase] = useState('PLANNING');
    const query = new URLSearchParams({ source, kind, phase });
    if (source === 'ASSESSMENT')
        query.set('toolMode', mode);
    if (batchId)
        query.set('batchId', batchId);
    const resource = useResource<ComponentMetric[]>(`/components?${query}`, revision);
    return <Panel title={t("组件性能")} subtitle={t("按实际 Agent\u3001模型与工具统计\uFF0C点击明细可追溯到运行记录\u3002")}>
    <div className="as-component-controls"><div className="as-segment" role="group" aria-label={t("组件类型")}>{ui([['AGENT', 'Agent'], ['MODEL', t("模型")], ['TOOL', t("工具")]].map(([id, label]) => <button key={id} aria-pressed={kind === id} onClick={() => setKind(id)}>{ui(label)}</button>))}</div><label className="as-inline-label">{t("统计阶段")}<select aria-label={t("组件统计阶段")} value={phase} onChange={e => setPhase(e.target.value)}><option value="PLANNING">{t("规划阶段")}</option><option value="SCORING">{t("评分阶段")}</option></select></label></div>
    <ResourceState {...resource} hasData={Boolean(resource.data)}/>
    {ui(resource.data && (resource.data.length ? <div className="as-table-wrap"><table className="as-table as-component-table"><thead><tr><th>{t("实际名称")}</th><th className="as-num">{t("调用 / 成功 / 失败")}</th><th className="as-num">{t("平均耗时")}</th><th className="as-num">P50</th><th className="as-num">P95</th><th className="as-num">{t("失败平均耗时")}</th><th className="as-num">{t("输入 Token")}</th><th className="as-num">{t("输出 Token")}</th><th className="as-num">{t("已记录费用")}</th><th>{t("操作")}</th></tr></thead><tbody>{ui(resource.data.map(row => <tr key={`${row.kind}:${row.component_name}:${row.phase}`}><td><ComponentName kind={row.kind} name={row.component_name}/><small>{ui(phaseName(row.phase))}{t("\u00B7 耗时样本")}{ui(number(row.duration_samples))}{ui(row.data_complete === false ? t(" \u00B7 采集不完整") : '')}</small></td><td className="as-num">{ui(number(row.call_count))} / {ui(number(row.succeeded))} / {ui(number(row.failed))}{ui(Boolean(row.cancelled) && <small>{t("取消")}{ui(row.cancelled)}</small>)}</td><td className="as-num">{ui(duration(row.mean_ms))}</td><td className="as-num">{ui(duration(row.p50_ms))}</td><td className="as-num">{ui(duration(row.p95_ms))}</td><td className="as-num">{ui(duration(row.failed_mean_ms))}</td><td className="as-num">{ui(row.kind === 'TOOL' ? t("不适用") : number(row.input_tokens))}{ui(row.kind !== 'TOOL' && row.usage_complete !== true && <small>{t("仅已记录部分")}</small>)}</td><td className="as-num">{ui(row.kind === 'TOOL' ? t("不适用") : number(row.output_tokens))}</td><td className="as-num">{ui(cost(row.known_cost))}{ui(row.cost_complete !== true && <small>{t("费用不完整")}</small>)}</td><td><button className="as-link" aria-label={ui(t("查看组件 {0} 的运行记录", { "0": row.component_name }))} onClick={() => onInspect(row)}>{t("运行明细 \u2192")}</button></td></tr>))}</tbody></table></div> : <Empty title={t("当前范围暂无组件记录")}>{t("切换组件类型\u3001统计阶段\uFF0C或检查运行记录采集情况\u3002")}</Empty>))}
    <p className="as-footnote">{t("平均耗时\u3001P50\u3001P95 仅基于成功且已结束的调用\u3002失败耗时另列\u3002Agent 耗时与 Token 包含子调用\uFF0C不能将父子汇总相加\uFF1B模型请求组不重复计入实际模型调用\u3002")}</p>
  </Panel>;
}
