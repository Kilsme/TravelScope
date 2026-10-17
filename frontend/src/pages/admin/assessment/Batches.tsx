import { useRef, useState } from 'react'
import BatchMetricsView from './BatchMetrics'
import { Badge, Empty, Id, Overlay, Panel, RawData, ResourceState } from './common'
import { activeStatus, categories, date, endedStatus, modeName, parse, request, rulesState, useResource } from './model'
import type { Batch, BatchDetail, Scenario, ScoreResult, ToolMode } from './model'

const pollBatches = (data: Batch[]) => data.some(batch => activeStatus(batch.status))
const pollBatch = (data: BatchDetail) => activeStatus(data.batch.status)

export default function Batches({ params, update, revision, onCreate }: { params: URLSearchParams; update: (patch: Record<string, string | null>) => void; revision: number; onCreate: () => void }) {
  const list = useResource<Batch[]>('/batches', revision, pollBatches)
  const batchId = params.get('batchId')
  return <>
    <div className="as-section-head as-view-head"><div><h2>测试任务</h2><p>用相同场景重复验证，分别查看运行与质量结果。</p></div><span className="as-muted">最近 100 个任务</span></div>
    <ResourceState {...list} hasData={Boolean(list.data)} />
    {list.data && (list.data.length ? <div className="as-table-wrap"><table className="as-table"><thead><tr><th>创建时间 / 编号</th><th>运行方式</th><th>状态</th><th className="as-num">每场景次数</th><th>操作</th></tr></thead><tbody>{list.data.map(batch => <tr key={batch.id} className={batchId === batch.id ? 'as-selected-row' : ''}><td>{date(batch.created_at)}<small><Id value={batch.id} /></small></td><td>{modeName(batch.tool_mode)}</td><td><Badge status={batch.status} /></td><td className="as-num">{batch.repetitions ?? '未记录'}</td><td><button className="as-link" aria-label={`查看测试 ${batch.id}`} onClick={() => update({ batchId: batch.id, sampleId: null, runId: null })}>查看任务 →</button></td></tr>)}</tbody></table></div> : <Empty title="还没有测试任务">选择已有场景，创建第一组测试。<br /><button className="as-primary" onClick={onCreate}>新建测试</button></Empty>)}
    {batchId && <BatchResults key={batchId} id={batchId} revision={revision} onChanged={list.reload} onClose={() => update({ batchId: null, sampleId: null, runId: null })} onSample={sampleId => update({ sampleId, runId: null })} />}
  </>
}

function BatchResults({ id, revision, onChanged, onClose, onSample }: { id: string; revision: number; onChanged: () => void; onClose: () => void; onSample: (id: string) => void }) {
  const resource = useResource<BatchDetail>(`/batches/${encodeURIComponent(id)}`, revision, pollBatch)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), lock = useRef(false)
  const data = resource.data
  async function act(path: string) {
    if (lock.current) return
    lock.current = true; setBusy(true); setError('')
    try { await request(`/batches/${encodeURIComponent(id)}/${path}`, {}); resource.reload(); onChanged() }
    catch (e) { setError(e instanceof Error ? e.message : '操作失败，请重试。') }
    finally { lock.current = false; setBusy(false) }
  }
  const completed = data?.samples.filter(sample => !['QUEUED', 'RUNNING'].includes(sample.status)).length ?? 0
  const total = data?.samples.length ?? 0
  const retryable = data?.samples.some(sample => ['INTERRUPTED', 'CANCELLED', 'QUEUED'].includes(sample.status))
  return <Panel title="测试详情" subtitle={`任务 ${id}`} action={<button onClick={onClose}>收起详情</button>}>
    <ResourceState {...resource} hasData={Boolean(data)} />
    {error && <p role="alert" className="as-notice as-notice-error">{error}</p>}
    {data && <>
      <div className="as-batch-summary"><div><Badge status={data.batch.status} /><span>{modeName(data.batch.tool_mode)}</span><span>{activeStatus(data.batch.status) ? '每 3 秒自动更新' : '任务已结束，自动更新已停止'}</span></div><div className="as-button-row">
        {['QUEUED', 'RUNNING', 'CANCELLING'].includes(data.batch.status) && <button className="as-danger" disabled={busy || data.batch.status === 'CANCELLING'} onClick={() => { void act('cancel') }}>{data.batch.status === 'CANCELLING' ? '正在取消…' : '取消测试'}</button>}
        {endedStatus(data.batch.status) && retryable && <button disabled={busy} onClick={() => { void act('retry-incomplete') }}>重跑未完成项</button>}
      </div></div>
      <div className="as-progress-head"><strong>已处理 {completed} / {total}</strong><span>运行失败 {data.samples.filter(sample => sample.status === 'FAILED').length} · 评分失败 {data.samples.filter(sample => sample.score_status === 'ERROR').length}</span></div>
      <progress className="as-progress" value={completed} max={Math.max(1, total)} aria-label="测试处理进度" /><p className="as-footnote">已处理包含失败、取消和中断，不等同于通过。重跑仅处理中断、取消或仍在排队的样本，不会重跑已失败的样本。</p>
      <BatchMetricsView metrics={data.metrics} />
      {total ? <div className="as-table-wrap"><table className="as-table"><thead><tr><th>场景</th><th>次数</th><th>运行结果</th><th>规则检查</th><th>行程评分 / 分数</th><th>操作</th></tr></thead><tbody>{data.samples.map(sample => {
        const score = parse<ScoreResult>(sample.score_json)
        return <tr key={sample.id}><td>{sample.case_id}</td><td>第 {sample.repetition} 次</td><td><Badge status={sample.status} /></td><td><Badge status={rulesState(sample.rule_json)} /></td><td><Badge status={sample.score_status === 'SUCCEEDED' ? score?.passed === true ? 'PASS' : score?.passed === false ? 'FAIL' : 'UNKNOWN' : sample.score_status} label={sample.score_status === 'SUCCEEDED' ? score?.passed === true ? '评分达标' : score?.passed === false ? '评分未达标' : '评分结果未记录' : sample.score_status === 'RUNNING' ? '评分中' : undefined} />{sample.score_status === 'SUCCEEDED' && score?.total != null && <small>{score.total.toFixed(1)} / 100</small>}</td><td><button className="as-link" aria-label={`查看场景 ${sample.case_id} 第 ${sample.repetition} 次`} onClick={() => onSample(sample.id)}>查看结果 →</button></td></tr>
      })}</tbody></table></div> : <Empty title="暂无测试样本" />}
      <RawData value={data.batch} title="任务配置与原始数据" />
    </>}
  </Panel>
}

export function CreateTest({ onClose, onCreated }: { onClose: () => void; onCreated: (id: string) => void }) {
  const cases = useResource<Scenario[]>('/cases'), batches = useResource<Batch[]>('/batches')
  const [step, setStep] = useState(0), [chosen, setChosen] = useState<string[]>([]), [category, setCategory] = useState('')
  const [mode, setMode] = useState<ToolMode>('FIXTURE'), [repeat, setRepeat] = useState(3)
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const [attemptSignature, setAttemptSignature] = useState('')
  const submission = useRef({ signature: '', key: crypto.randomUUID() }), lock = useRef(false)
  const rows = cases.data ?? [], filtered = rows.filter(item => !category || item.category === category)
  const allSelected = filtered.length > 0 && filtered.every(item => chosen.includes(item.caseId))
  const validRepeat = Number.isInteger(repeat) && repeat >= 1 && repeat <= 10
  const active = batches.data?.some(batch => activeStatus(batch.status))
  const signature = JSON.stringify({ caseIds: [...chosen].sort(), toolMode: mode, repetitions: repeat })
  const confirmingAttempt = attemptSignature === signature
  function toggle(ids: string[], checked: boolean) { setChosen(previous => checked ? [...new Set([...previous, ...ids])] : previous.filter(id => !ids.includes(id))) }
  async function submit() {
    if (lock.current || !chosen.length || !validRepeat || active && !confirmingAttempt) return
    lock.current = true; setBusy(true); setError('')
    if (submission.current.signature !== signature) submission.current = { signature, key: crypto.randomUUID() }
    setAttemptSignature(signature)
    try { const result = await request<{ id: string }>('/batches', { caseIds: chosen, toolMode: mode, repetitions: repeat, requestKey: submission.current.key }); onCreated(result.id) }
    catch (e) { setError(e instanceof Error ? e.message : '启动失败，请重试。'); batches.reload() }
    finally { lock.current = false; setBusy(false) }
  }
  return <Overlay title="新建测试" onClose={() => { if (!lock.current) onClose() }} modal wide>
    <ol className="as-steps">{['选择场景', '设置运行方式', '确认并启动'].map((title, index) => <li key={title} aria-current={step === index ? 'step' : undefined} className={index < step ? 'as-step-done' : ''}><span>{index + 1}</span>{title}</li>)}</ol>
    {error && <p role="alert" className="as-notice as-notice-error">{error}</p>}
    {active && <p className="as-notice as-notice-warning">已有测试正在运行，可以先设置场景，待当前任务结束后再启动。</p>}
    {step === 0 && <>
      <ResourceState {...cases} hasData={Boolean(cases.data)} />
      <div className="as-selection-bar"><label>场景分类<select value={category} onChange={e => setCategory(e.target.value)}><option value="">全部分类</option>{[...new Set(rows.map(item => item.category))].map(id => <option key={id} value={id}>{categories[id] ?? '其他场景'}</option>)}</select></label><label className="as-check"><input type="checkbox" checked={allSelected} disabled={!filtered.length} onChange={e => toggle(filtered.map(item => item.caseId), e.target.checked)} />{category ? '选择此分类' : '全选场景'}</label><span className="as-muted">已选 {chosen.length} / {rows.length} 个场景</span></div>
      <div className="as-case-list">{filtered.map(item => <div key={item.caseId} className={chosen.includes(item.caseId) ? 'as-case as-case-chosen' : 'as-case'}><label className="as-check"><input type="checkbox" checked={chosen.includes(item.caseId)} onChange={e => toggle([item.caseId], e.target.checked)} /><span><strong>{categories[item.category] ?? '其他场景'}</strong><small>{item.caseId}</small></span></label><p className="as-clamp" title={item.userMessages?.[0]}>{item.userMessages?.[0] ?? '场景内容未记录'}</p><details className="as-disclosure"><summary>查看完整场景</summary><ol className="as-messages">{item.userMessages?.map((message, index) => <li key={index}>{message}</li>)}</ol>{item.expectations?.rules.map((rule, index) => <p className="as-footnote" key={index}>{rule.desc ?? '检查规则'}</p>)}</details></div>)}</div>
      {cases.data && !rows.length && <Empty title="暂无可选场景" />}
    </>}
    {step === 1 && <div className="as-settings"><h3>选择工具运行方式</h3>{(['FIXTURE', 'LIVE'] as const).map(value => <label key={value} className={`as-mode-option ${mode === value ? 'as-mode-selected' : ''}`}><input type="radio" name="toolMode" value={value} checked={mode === value} onChange={() => setMode(value)} /><span><strong>{modeName(value)}</strong><small>{value === 'FIXTURE' ? '外部工具返回固定测试数据，便于重复验证；模型仍实际调用。' : '实际调用外部工具，结果会受到实时数据和接口状态影响。'}</small></span></label>)}<label className="as-repeat">每场景重复次数<input type="number" min={1} max={10} step={1} value={Number.isNaN(repeat) ? '' : repeat} onChange={e => setRepeat(e.target.value === '' ? NaN : Number(e.target.value))} /></label><p className="as-footnote">可选 1～10 次，重复运行用于观察结果波动。</p>{!validRepeat && <p role="alert" className="as-error-text">请输入 1～10 之间的整数。</p>}<p className="as-notice">两种方式均实际调用模型，可能产生费用。固定工具数据不代表真实营业、天气或接口状态。</p></div>}
    {step === 2 && <div className="as-confirm"><h3>确认测试设置</h3><div className="as-confirm-total"><strong>{chosen.length * repeat}</strong><span>次测试执行</span></div><dl className="as-metric-rows"><div><dt>所选场景</dt><dd>{chosen.length} 个</dd></div><div><dt>每场景重复</dt><dd>{repeat} 次</dd></div><div><dt>运行方式</dt><dd>{modeName(mode)}</dd></div></dl><p className="as-notice as-notice-warning">启动后将实际调用模型，可能产生费用。测试不扣用户积分，也不写入个人聊天记录。</p><p className="as-footnote">测试一次运行一个任务，结束后可比较相同工具模式下的共同场景。</p><ResourceState {...batches} hasData={Boolean(batches.data)} /></div>}
    <footer className="as-modal-foot"><span className="as-muted">{step === 0 ? `已选择 ${chosen.length} 个场景` : `${chosen.length} 个场景 × ${validRepeat ? repeat : '—'} 次`}</span><div className="as-button-row"><button disabled={busy} onClick={step ? () => setStep(step - 1) : onClose}>{step ? '上一步' : '取消'}</button>{step < 2 ? <button className="as-primary" disabled={step === 0 ? !chosen.length : !validRepeat} onClick={() => setStep(step + 1)}>下一步 →</button> : <button className="as-primary" disabled={busy || active && !confirmingAttempt || batches.loading || !batches.data} onClick={() => { void submit() }}>{busy ? '正在启动…' : confirmingAttempt ? '重试确认任务' : '启动测试'}</button>}</div></footer>
  </Overlay>
}
