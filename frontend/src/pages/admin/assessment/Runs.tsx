import { usePreferences } from '../../../preferences/PreferencesProvider'
import { t, ui } from '../../../preferences/i18n'
import { useState } from 'react';
import { Badge, Empty, Id, ResourceState } from './common';
import { date, duration, modeName, number, taskNames, statusNames, useResource } from './model';
import type { Run } from './model';
export default function Runs({ params, update, revision }: {
    params: URLSearchParams;
    update: (patch: Record<string, string | null>) => void;
    revision: number;
}) {
    usePreferences();
    const source = params.get('source') === 'ASSESSMENT' ? 'ASSESSMENT' : 'ONLINE';
    const task = params.get('task') ?? '', status = params.get('status') ?? '', session = params.get('session') ?? '';
    const pageValue = Number(params.get('page'));
    const page = Number.isFinite(pageValue) ? Math.max(0, Math.floor(pageValue)) : 0;
    const [draftState, setDraftState] = useState({ session, value: session });
    if (draftState.session !== session)
        setDraftState({ session, value: session });
    const draft = draftState.session === session ? draftState.value : session;
    const setDraft = (value: string) => setDraftState({ session, value });
    const query = new URLSearchParams({ source, page: String(page), size: '25', taskType: task, status, sessionId: session });
    if (source === 'ASSESSMENT' && params.get('mode'))
        query.set('toolMode', params.get('mode')!);
    for (const key of ['componentKind', 'componentName', 'phase', 'batchId'])
        if (params.get(key))
            query.set(key, params.get(key)!);
    const resource = useResource<Run[]>(`/runs?${query}`, revision), rows = resource.data;
    function filter(patch: Record<string, string | null>) { update({ ...patch, page: null, runId: null }); }
    return <>
    <div className="as-section-head as-view-head"><div><h2>{t("运行记录")}</h2><p>{t("找到一次执行\uFF0C查看结果与调用过程\u3002")}</p></div><span className="as-muted">{t("每页 25 条")}</span></div>
    <form className="as-filters" onSubmit={e => { e.preventDefault(); filter({ session: draft.trim() || null }); }}>
      <label>{t("来源")}<select aria-label={t("来源")} value={source} onChange={e => filter({ source: e.target.value })}><option value="ONLINE">{t("日常使用")}</option><option value="ASSESSMENT">{t("测试运行")}</option></select></label>
      {ui(source === 'ASSESSMENT' && <label>{t("工具模式")}<select aria-label={t("记录工具模式")} value={params.get('mode') ?? ''} onChange={e => filter({ mode: e.target.value || null })}><option value="">{t("全部工具模式")}</option><option value="FIXTURE">{t("固定工具数据")}</option><option value="LIVE">{t("真实工具")}</option></select></label>)}
      <label>{t("任务类型")}<select aria-label={t("任务类型")} value={task} onChange={e => filter({ task: e.target.value || null })}><option value="">{t("全部类型")}</option>{ui(Object.entries(taskNames).map(([id, title]) => <option key={id} value={id}>{ui(title)}</option>))}</select></label>
      <label>{t("运行结果")}<select aria-label={t("运行结果")} value={status} onChange={e => filter({ status: e.target.value || null })}><option value="">{t("全部结果")}</option>{ui(['RUNNING', 'PLANNING_COMPLETED', 'WAITING_INPUT', 'REPLIED', 'FAILED', 'TIMED_OUT', 'CANCELLED', 'INTERRUPTED', 'INCOMPLETE'].map(id => <option key={id} value={id}>{ui(statusNames[id])}</option>))}</select></label>
      <label className="as-search-field">{t("会话编号")}<input placeholder={t("输入完整会话编号")} value={draft} onChange={e => setDraft(e.target.value)}/></label><button className="as-primary" type="submit">{t("查询")}</button>
      {ui((task || status || session) && <button type="button" onClick={() => { setDraft(''); filter({ task: null, status: null, session: null }); }}>{t("重置")}</button>)}
    </form>
    {ui(params.get('componentName') && <div className="as-notice">{t("当前组件\uFF1A")}{ui(params.get('componentName'))} · {ui(params.get('phase') === 'SCORING' ? t("评分") : t("规划"))}<button onClick={() => filter({ componentKind: null, componentName: null, phase: null, batchId: null })}>{t("清除组件筛选")}</button></div>)}
    <ResourceState {...resource} hasData={Boolean(rows)}/>
    {ui(rows && (rows.length ? <div className="as-table-wrap"><table className="as-table"><thead><tr><th>{t("开始时间")}</th><th>{t("任务类型")}</th><th>{t("运行结果")}</th><th className="as-num">{t("耗时")}</th><th className="as-num">{t("模型 Token")}</th><th>{t("行程评分")}</th><th>{t("调用异常")}</th><th>{t("记录完整情况")}</th><th>{t("操作")}</th></tr></thead><tbody>{ui(rows.map(row => <tr key={row.id}>
      <td>{ui(date(row.started_at))}<small><Id value={row.id}/></small></td><td>{ui(taskNames[row.task_type] ?? t("未分类"))}{ui(row.tool_mode && <small>{ui(modeName(row.tool_mode))}</small>)}</td><td><Badge status={row.status}/></td><td className="as-num">{ui(duration(row.duration_ms))}</td>
      <td className="as-num">{ui(number(row.tokens))}<small>{ui(row.usage_complete === false ? t("仅已记录部分") : row.usage_complete === true ? t("用量完整") : t("用量完整性未记录"))}</small></td>
      <td>{ui(row.latest_score_status === 'SUCCEEDED' ? <><Badge status={row.latest_score_passed === true ? 'PASS' : row.latest_score_passed === false ? 'FAIL' : 'UNKNOWN'} label={ui(row.latest_score_passed === true ? t("评分达标") : row.latest_score_passed === false ? t("评分未达标") : t("已评分"))}/><small>{ui(number(row.latest_score_total))}{t("分")}</small></> : <Badge status={row.latest_score_status ?? 'SKIPPED'} label={ui(row.latest_score_status === 'RUNNING' ? t("评分中") : undefined)}/>)}</td><td>{ui(row.failed_span_count ? <button className="as-link as-error-text" aria-label={ui(t("定位运行 {0} 的异常", { "0": row.id }))} onClick={() => update({ runId: row.id, sampleId: row.sample_id ?? null, detailTab: 'trace', traceFocus: 'errors', spanId: null })}>{ui(row.failed_span_count)}{t("处异常")}</button> : row.span_count != null ? <span className="as-muted">{t("未观测到")}</span> : t("未记录"))}</td>
      <td><Badge status={row.data_complete === true ? 'PASS' : 'UNKNOWN'} label={ui(row.data_complete === true ? t("完整") : row.data_complete === false ? t("不完整") : t("未记录"))}/></td><td><div className="as-button-row"><button className="as-link" aria-label={ui(t("查看运行 {0}", { "0": row.id }))} onClick={() => update({ runId: row.id, sampleId: row.sample_id ?? null, detailTab: 'summary', spanId: null, traceFocus: null })}>{t("详情")}</button><button className="as-link" aria-label={ui(t("查看运行 {0} 的调用链", { "0": row.id }))} onClick={() => update({ runId: row.id, sampleId: row.sample_id ?? null, detailTab: 'trace', spanId: null, traceFocus: null })}>{t("调用链")}</button><button className="as-link" aria-label={ui(t("查看运行 {0} 的评分", { "0": row.id }))} onClick={() => update({ runId: row.id, sampleId: row.sample_id ?? null, detailTab: 'summary', spanId: null, traceFocus: null })}>{t("评分")}</button></div></td>
    </tr>))}</tbody></table></div> : <Empty title={t("没有符合条件的运行记录")}>{t("尝试调整筛选条件\uFF0C或在日常使用后刷新查看\u3002")}</Empty>))}
    <div className="as-pagination"><span>{ui(rows?.length ? t("本页 {0} 条记录", { "0": rows.length }) : t("当前无记录"))}</span><div><button disabled={!page || resource.loading} onClick={() => update({ page: String(page - 1) })}>{t("上一页")}</button><span>{t("第 {0} 页", {"0":page+1})}</span><button disabled={!rows || rows.length < 25 || resource.loading} onClick={() => update({ page: String(page + 1) })}>{t("下一页")}</button></div></div>
  </>;
}
