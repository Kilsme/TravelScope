import { usePreferences } from '../../../preferences/PreferencesProvider'
import { t, ui } from '../../../preferences/i18n'
import { useRef, useState } from 'react';
import BatchMetricsView from './BatchMetrics';
import { Badge, Empty, Id, Overlay, Panel, RawData, ResourceState } from './common';
import { activeStatus, categories, date, endedStatus, modeName, parse, request, rulesState, useResource } from './model';
import type { Batch, BatchDetail, Scenario, ScoreResult, ToolMode } from './model';
const pollBatches = (data: Batch[]) => data.some(batch => activeStatus(batch.status));
const pollBatch = (data: BatchDetail) => activeStatus(data.batch.status);
export default function Batches({ params, update, revision, onCreate }: {
    params: URLSearchParams;
    update: (patch: Record<string, string | null>) => void;
    revision: number;
    onCreate: () => void;
}) {
    usePreferences();
    const list = useResource<Batch[]>('/batches', revision, pollBatches);
    const batchId = params.get('batchId');
    return <>
    <div className="as-section-head as-view-head"><div><h2>{t("测试任务")}</h2><p>{t("用相同场景重复验证\uFF0C分别查看运行与质量结果\u3002")}</p></div><span className="as-muted">{t("最近 100 个任务")}</span></div>
    <ResourceState {...list} hasData={Boolean(list.data)}/>
    {ui(list.data && (list.data.length ? <div className="as-table-wrap"><table className="as-table"><thead><tr><th>{t("创建时间 / 编号")}</th><th>{t("运行方式")}</th><th>{t("状态")}</th><th className="as-num">{t("每场景次数")}</th><th>{t("操作")}</th></tr></thead><tbody>{ui(list.data.map(batch => <tr key={batch.id} className={batchId === batch.id ? 'as-selected-row' : ''}><td>{ui(date(batch.created_at))}<small><Id value={batch.id}/></small></td><td>{ui(modeName(batch.tool_mode))}</td><td><Badge status={batch.status}/></td><td className="as-num">{ui(batch.repetitions ?? t("未记录"))}</td><td><button className="as-link" aria-label={ui(t("查看测试 {0}", { "0": batch.id }))} onClick={() => update({ batchId: batch.id, sampleId: null, runId: null })}>{t("查看任务 \u2192")}</button></td></tr>))}</tbody></table></div> : <Empty title={t("还没有测试任务")}>{t("选择已有场景\uFF0C创建第一组测试\u3002")}<br /><button className="as-primary" onClick={onCreate}>{t("新建测试")}</button></Empty>))}
    {ui(batchId && <BatchResults key={batchId} id={batchId} revision={revision} onChanged={list.reload} onClose={() => update({ batchId: null, sampleId: null, runId: null })} onSample={sampleId => update({ sampleId, runId: null })}/>)}
  </>;
}
function BatchResults({ id, revision, onChanged, onClose, onSample }: {
    id: string;
    revision: number;
    onChanged: () => void;
    onClose: () => void;
    onSample: (id: string) => void;
}) {
    usePreferences();
    const resource = useResource<BatchDetail>(`/batches/${encodeURIComponent(id)}`, revision, pollBatch);
    const [busy, setBusy] = useState(false), [error, setError] = useState(''), lock = useRef(false);
    const data = resource.data;
    async function act(path: string) {
        if (lock.current)
            return;
        lock.current = true;
        setBusy(true);
        setError('');
        try {
            await request(`/batches/${encodeURIComponent(id)}/${path}`, {});
            resource.reload();
            onChanged();
        }
        catch (e) {
            setError(e instanceof Error ? e.message : '操作失败，请重试。');
        }
        finally {
            lock.current = false;
            setBusy(false);
        }
    }
    const completed = data?.samples.filter(sample => !['QUEUED', 'RUNNING'].includes(sample.status)).length ?? 0;
    const total = data?.samples.length ?? 0;
    const retryable = data?.samples.some(sample => ['INTERRUPTED', 'CANCELLED', 'QUEUED'].includes(sample.status));
    return <Panel title={t("测试详情")} subtitle={ui(t("任务 {0}", { "0": id }))} action={<button onClick={onClose}>{t("收起详情")}</button>}>
    <ResourceState {...resource} hasData={Boolean(data)}/>
    {ui(error && <p role="alert" className="as-notice as-notice-error">{ui(error)}</p>)}
    {ui(data && <>
      <div className="as-batch-summary"><div><Badge status={data.batch.status}/><span>{ui(modeName(data.batch.tool_mode))}</span><span>{ui(activeStatus(data.batch.status) ? t("每 3 秒自动更新") : t("任务已结束\uFF0C自动更新已停止"))}</span></div><div className="as-button-row">
        {ui(['QUEUED', 'RUNNING', 'CANCELLING'].includes(data.batch.status) && <button className="as-danger" disabled={busy || data.batch.status === 'CANCELLING'} onClick={() => { void act('cancel'); }}>{ui(data.batch.status === 'CANCELLING' ? t("正在取消\u2026") : t("取消测试"))}</button>)}
        {ui(endedStatus(data.batch.status) && retryable && <button disabled={busy} onClick={() => { void act('retry-incomplete'); }}>{t("重跑未完成项")}</button>)}
      </div></div>
      <div className="as-progress-head"><strong>{t("已处理")}{ui(completed)} / {ui(total)}</strong><span>{t("运行失败")}{ui(data.samples.filter(sample => sample.status === 'FAILED').length)}{t("\u00B7 评分失败")}{ui(data.samples.filter(sample => sample.score_status === 'ERROR').length)}</span></div>
      <progress className="as-progress" value={completed} max={Math.max(1, total)} aria-label={t("测试处理进度")}/><p className="as-footnote">{t("已处理包含失败\u3001取消和中断\uFF0C不等同于通过\u3002重跑仅处理中断\u3001取消或仍在排队的样本\uFF0C不会重跑已失败的样本\u3002")}</p>
      <BatchMetricsView metrics={data.metrics}/>
      {ui(total ? <div className="as-table-wrap"><table className="as-table"><thead><tr><th>{t("场景")}</th><th>{t("次数")}</th><th>{t("运行结果")}</th><th>{t("规则检查")}</th><th>{t("行程评分 / 分数")}</th><th>{t("操作")}</th></tr></thead><tbody>{ui(data.samples.map(sample => {
                    const score = parse<ScoreResult>(sample.score_json);
                    return <tr key={sample.id}><td>{ui(sample.case_id)}</td><td>{t("第")}{ui(sample.repetition)}{t("次")}</td><td><Badge status={sample.status}/></td><td><Badge status={rulesState(sample.rule_json)}/></td><td><Badge status={sample.score_status === 'SUCCEEDED' ? score?.passed === true ? 'PASS' : score?.passed === false ? 'FAIL' : 'UNKNOWN' : sample.score_status} label={ui(sample.score_status === 'SUCCEEDED' ? score?.passed === true ? t("评分达标") : score?.passed === false ? t("评分未达标") : t("评分结果未记录") : sample.score_status === 'RUNNING' ? t("评分中") : undefined)}/>{ui(sample.score_status === 'SUCCEEDED' && score?.total != null && <small>{ui(score.total.toFixed(1))} / 100</small>)}</td><td><button className="as-link" aria-label={ui(t("查看场景 {0} 第 {1} 次", { "0": sample.case_id, "1": sample.repetition }))} onClick={() => onSample(sample.id)}>{t("查看结果 \u2192")}</button></td></tr>;
                }))}</tbody></table></div> : <Empty title={t("暂无测试样本")}/>)}
      <RawData value={data.batch} title={t("任务配置与原始数据")}/>
    </>)}
  </Panel>;
}
export function CreateTest({ onClose, onCreated }: {
    onClose: () => void;
    onCreated: (id: string) => void;
}) {
    usePreferences();
    const cases = useResource<Scenario[]>('/cases'), batches = useResource<Batch[]>('/batches');
    const [step, setStep] = useState(0), [chosen, setChosen] = useState<string[]>([]), [category, setCategory] = useState('');
    const [mode, setMode] = useState<ToolMode>('FIXTURE'), [repeat, setRepeat] = useState(3);
    const [busy, setBusy] = useState(false), [error, setError] = useState('');
    const [attemptSignature, setAttemptSignature] = useState('');
    const submission = useRef({ signature: '', key: crypto.randomUUID() }), lock = useRef(false);
    const rows = cases.data ?? [], filtered = rows.filter(item => !category || item.category === category);
    const allSelected = filtered.length > 0 && filtered.every(item => chosen.includes(item.caseId));
    const validRepeat = Number.isInteger(repeat) && repeat >= 1 && repeat <= 10;
    const active = batches.data?.some(batch => activeStatus(batch.status));
    const signature = JSON.stringify({ caseIds: [...chosen].sort(), toolMode: mode, repetitions: repeat });
    const confirmingAttempt = attemptSignature === signature;
    function toggle(ids: string[], checked: boolean) { setChosen(previous => checked ? [...new Set([...previous, ...ids])] : previous.filter(id => !ids.includes(id))); }
    async function submit() {
        if (lock.current || !chosen.length || !validRepeat || active && !confirmingAttempt)
            return;
        lock.current = true;
        setBusy(true);
        setError('');
        if (submission.current.signature !== signature)
            submission.current = { signature, key: crypto.randomUUID() };
        setAttemptSignature(signature);
        try {
            const result = await request<{
                id: string;
            }>('/batches', { caseIds: chosen, toolMode: mode, repetitions: repeat, requestKey: submission.current.key });
            onCreated(result.id);
        }
        catch (e) {
            setError(e instanceof Error ? e.message : '启动失败，请重试。');
            batches.reload();
        }
        finally {
            lock.current = false;
            setBusy(false);
        }
    }
    return <Overlay title={t("新建测试")} onClose={() => { if (!lock.current)
        onClose(); }} modal wide>
    <ol className="as-steps">{ui([t("选择场景"), t("设置运行方式"), t("确认并启动")].map((title, index) => <li key={title} aria-current={step === index ? 'step' : undefined} className={index < step ? 'as-step-done' : ''}><span>{ui(index + 1)}</span>{ui(title)}</li>))}</ol>
    {ui(error && <p role="alert" className="as-notice as-notice-error">{ui(error)}</p>)}
    {ui(active && <p className="as-notice as-notice-warning">{t("已有测试正在运行\uFF0C可以先设置场景\uFF0C待当前任务结束后再启动\u3002")}</p>)}
    {ui(step === 0 && <>
      <ResourceState {...cases} hasData={Boolean(cases.data)}/>
      <div className="as-selection-bar"><label>{t("场景分类")}<select value={category} onChange={e => setCategory(e.target.value)}><option value="">{t("全部分类")}</option>{ui([...new Set(rows.map(item => item.category))].map(id => <option key={id} value={id}>{ui(categories[id] ?? t("其他场景"))}</option>))}</select></label><label className="as-check"><input type="checkbox" checked={allSelected} disabled={!filtered.length} onChange={e => toggle(filtered.map(item => item.caseId), e.target.checked)}/>{ui(category ? t("选择此分类") : t("全选场景"))}</label><span className="as-muted">{t("已选")}{ui(chosen.length)} / {ui(rows.length)}{t("个场景")}</span></div>
      <div className="as-case-list">{ui(filtered.map(item => <div key={item.caseId} className={chosen.includes(item.caseId) ? 'as-case as-case-chosen' : 'as-case'}><label className="as-check"><input type="checkbox" checked={chosen.includes(item.caseId)} onChange={e => toggle([item.caseId], e.target.checked)}/><span><strong>{ui(categories[item.category] ?? t("其他场景"))}</strong><small>{ui(item.caseId)}</small></span></label><p className="as-clamp" title={item.userMessages?.[0]}>{ui(item.userMessages?.[0] ?? t("场景内容未记录"))}</p><details className="as-disclosure"><summary>{t("查看完整场景")}</summary><ol className="as-messages">{ui(item.userMessages?.map((message, index) => <li key={index}>{ui(message)}</li>))}</ol>{ui(item.expectations?.rules.map((rule, index) => <p className="as-footnote" key={index}>{ui(rule.desc ?? t("检查规则"))}</p>))}</details></div>))}</div>
      {ui(cases.data && !rows.length && <Empty title={t("暂无可选场景")}/>)}
    </>)}
    {ui(step === 1 && <div className="as-settings"><h3>{t("选择工具运行方式")}</h3>{ui((['FIXTURE', 'LIVE'] as const).map(value => <label key={value} className={`as-mode-option ${mode === value ? 'as-mode-selected' : ''}`}><input type="radio" name="toolMode" value={value} checked={mode === value} onChange={() => setMode(value)}/><span><strong>{ui(modeName(value))}</strong><small>{ui(value === 'FIXTURE' ? t("外部工具返回固定测试数据\uFF0C便于重复验证\uFF1B模型仍实际调用\u3002") : t("实际调用外部工具\uFF0C结果会受到实时数据和接口状态影响\u3002"))}</small></span></label>))}<label className="as-repeat">{t("每场景重复次数")}<input type="number" min={1} max={10} step={1} value={Number.isNaN(repeat) ? '' : repeat} onChange={e => setRepeat(e.target.value === '' ? NaN : Number(e.target.value))}/></label><p className="as-footnote">{t("可选 1\uFF5E10 次\uFF0C重复运行用于观察结果波动\u3002")}</p>{ui(!validRepeat && <p role="alert" className="as-error-text">{t("请输入 1\uFF5E10 之间的整数\u3002")}</p>)}<p className="as-notice">{t("两种方式均实际调用模型\uFF0C可能产生费用\u3002固定工具数据不代表真实营业\u3001天气或接口状态\u3002")}</p></div>)}
    {ui(step === 2 && <div className="as-confirm"><h3>{t("确认测试设置")}</h3><div className="as-confirm-total"><strong>{ui(chosen.length * repeat)}</strong><span>{t("次测试执行")}</span></div><dl className="as-metric-rows"><div><dt>{t("所选场景")}</dt><dd>{ui(chosen.length)}{t("个")}</dd></div><div><dt>{t("每场景重复")}</dt><dd>{ui(repeat)}{t("次")}</dd></div><div><dt>{t("运行方式")}</dt><dd>{ui(modeName(mode))}</dd></div></dl><p className="as-notice as-notice-warning">{t("启动后将实际调用模型\uFF0C可能产生费用\u3002测试不扣用户积分\uFF0C也不写入个人聊天记录\u3002")}</p><p className="as-footnote">{t("测试一次运行一个任务\uFF0C结束后可比较相同工具模式下的共同场景\u3002")}</p><ResourceState {...batches} hasData={Boolean(batches.data)}/></div>)}
    <footer className="as-modal-foot"><span className="as-muted">{ui(step === 0 ? t("已选择 {0} 个场景", { "0": chosen.length }) : t("{0} 个场景 \u00D7 {1} 次", { "0": chosen.length, "1": validRepeat ? repeat : '—' }))}</span><div className="as-button-row"><button disabled={busy} onClick={step ? () => setStep(step - 1) : onClose}>{ui(step ? t("上一步") : t("取消"))}</button>{ui(step < 2 ? <button className="as-primary" disabled={step === 0 ? !chosen.length : !validRepeat} onClick={() => setStep(step + 1)}>{t("下一步 \u2192")}</button> : <button className="as-primary" disabled={busy || active && !confirmingAttempt || batches.loading || !batches.data} onClick={() => { void submit(); }}>{ui(busy ? t("正在启动\u2026") : confirmingAttempt ? t("重试确认任务") : t("启动测试"))}</button>)}</div></footer>
  </Overlay>;
}
