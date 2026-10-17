import { useState } from 'react'
import { Badge, Empty, Id, ResourceState } from './common'
import { date, duration, number, taskNames, statusNames, useResource } from './model'
import type { Run } from './model'

export default function Runs({ params, update, revision }: { params: URLSearchParams; update: (patch: Record<string, string | null>) => void; revision: number }) {
  const source = params.get('source') === 'ASSESSMENT' ? 'ASSESSMENT' : 'ONLINE'
  const task = params.get('task') ?? '', status = params.get('status') ?? '', session = params.get('session') ?? ''
  const pageValue = Number(params.get('page'))
  const page = Number.isFinite(pageValue) ? Math.max(0, Math.floor(pageValue)) : 0
  const [draftState, setDraftState] = useState({ session, value: session })
  if (draftState.session !== session) setDraftState({ session, value: session })
  const draft = draftState.session === session ? draftState.value : session
  const setDraft = (value: string) => setDraftState({ session, value })
  const query = new URLSearchParams({ source, page: String(page), size: '25', taskType: task, status, sessionId: session })
  const resource = useResource<Run[]>(`/runs?${query}`, revision), rows = resource.data
  function filter(patch: Record<string, string | null>) { update({ ...patch, page: null, runId: null }) }
  return <>
    <div className="as-section-head as-view-head"><div><h2>运行记录</h2><p>找到一次执行，查看结果与调用过程。</p></div><span className="as-muted">每页 25 条</span></div>
    <form className="as-filters" onSubmit={e => { e.preventDefault(); filter({ session: draft.trim() || null }) }}>
      <label>来源<select aria-label="来源" value={source} onChange={e => filter({ source: e.target.value })}><option value="ONLINE">日常使用</option><option value="ASSESSMENT">测试运行</option></select></label>
      <label>任务类型<select aria-label="任务类型" value={task} onChange={e => filter({ task: e.target.value || null })}><option value="">全部类型</option>{Object.entries(taskNames).map(([id, title]) => <option key={id} value={id}>{title}</option>)}</select></label>
      <label>运行结果<select aria-label="运行结果" value={status} onChange={e => filter({ status: e.target.value || null })}><option value="">全部结果</option>{['RUNNING', 'PLANNING_COMPLETED', 'WAITING_INPUT', 'REPLIED', 'FAILED', 'TIMED_OUT', 'CANCELLED', 'INTERRUPTED', 'INCOMPLETE'].map(id => <option key={id} value={id}>{statusNames[id]}</option>)}</select></label>
      <label className="as-search-field">会话编号<input placeholder="输入完整会话编号" value={draft} onChange={e => setDraft(e.target.value)} /></label><button className="as-primary" type="submit">查询</button>
      {(task || status || session) && <button type="button" onClick={() => { setDraft(''); filter({ task: null, status: null, session: null }) }}>重置</button>}
    </form>
    <ResourceState {...resource} hasData={Boolean(rows)} />
    {rows && (rows.length ? <div className="as-table-wrap"><table className="as-table"><thead><tr><th>开始时间</th><th>任务类型</th><th>运行结果</th><th className="as-num">耗时</th><th className="as-num">模型用量</th><th>记录完整情况</th><th>操作</th></tr></thead><tbody>{rows.map(row => <tr key={row.id}>
      <td>{date(row.started_at)}<small><Id value={row.id} /></small></td><td>{taskNames[row.task_type] ?? '未分类'}</td><td><Badge status={row.status} /></td><td className="as-num">{duration(row.duration_ms)}</td>
      <td className="as-num">{number(row.tokens)}<small>{row.usage_complete === false ? '仅已记录部分' : row.usage_complete === true ? '用量完整' : '用量完整性未记录'}</small></td>
      <td><Badge status={row.data_complete === true ? 'PASS' : 'UNKNOWN'} label={row.data_complete === true ? '完整' : row.data_complete === false ? '不完整' : '未记录'} /></td><td><button className="as-link" aria-label={`查看运行 ${row.id}`} onClick={() => update({ runId: row.id, sampleId: row.sample_id ?? null })}>查看详情 <span aria-hidden="true">→</span></button></td>
    </tr>)}</tbody></table></div> : <Empty title="没有符合条件的运行记录">尝试调整筛选条件，或在日常使用后刷新查看。</Empty>)}
    <div className="as-pagination"><span>{rows?.length ? `本页 ${rows.length} 条记录` : '当前无记录'}</span><div><button disabled={!page || resource.loading} onClick={() => update({ page: String(page - 1) })}>上一页</button><span>第 {page + 1} 页</span><button disabled={!rows || rows.length < 25 || resource.loading} onClick={() => update({ page: String(page + 1) })}>下一页</button></div></div>
  </>
}
