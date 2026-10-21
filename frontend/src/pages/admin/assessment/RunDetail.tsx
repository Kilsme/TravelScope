import { useCallback, useEffect, useRef, useState } from 'react'
import Trace from './Trace'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { defuseGfmTildes } from '../../../markdown'
import { Badge, CopyId, Empty, MetricRows, Overlay, Panel, RawData, ResourceState } from './common'
import { date, duration, finite, number, parse, request, sourceName, taskNames, useResource } from './model'
import type { BatchDetail, Evaluation, Payload, RuleResult, Run, Sample, ScoreResult, Span } from './model'

const ruleNames: Record<string, string> = { FIELD_COMPLETE: '行程信息完整性', CONSTRAINT_COVERED: '需求条件检查', TOOL_ORDER: '调用依赖检查', EXPECTED_WAITING_INPUT: '补充信息行为', BUDGET: '预算检查', DAYS: '行程天数', TIME_OVERLAP: '时间冲突', BUDGET_SUM_AND_LIMIT: '费用合计与预算' }
const methodNames: Record<string, string> = { HEURISTIC: '关键词初筛', HARD_CONSTRAINT: '明确条件核验', EVENT_EVIDENCE: '调用事件核验', EVENT_DEPENDENCY: '调用依赖核验' }

export default function RunDetail({ id, sampleId, batchId, revision, onClose, onRunChange, detailTab, spanId, traceFocus, onViewChange, onSpanChange }: { id: string | null; sampleId: string | null; batchId: string | null; revision: number; onClose: () => void; onRunChange: (id: string) => void; detailTab: string; spanId: string | null; traceFocus: string | null; onViewChange: (tab: string) => void; onSpanChange: (id: string) => void }) {
  const rounds = useResource<Run[]>(sampleId ? `/samples/${encodeURIComponent(sampleId)}/runs` : null, revision)
  const context = useResource<BatchDetail>(sampleId && batchId ? `/batches/${encodeURIComponent(batchId)}` : null, revision)
  const latestId = id ?? rounds.data?.[0]?.id ?? null
  useEffect(() => { if (!id && rounds.data?.[0]) onRunChange(rounds.data[0].id) }, [id, rounds.data, onRunChange])
  const sample = context.data?.samples.find(item => item.id === sampleId)
  return <Overlay title={sampleId ? '场景结果与运行详情' : '运行详情'} onClose={onClose} wide expandable>
    {sampleId && <><ResourceState {...rounds} hasData={Boolean(rounds.data)} /><ResourceState {...context} hasData={Boolean(context.data)} />
      {rounds.data && <section className="as-rounds"><div className="as-section-head"><h3>{sample?.case_id ?? '测试场景'} · 全部运行轮次</h3><span className="as-muted">共 {rounds.data.length} 轮</span></div><div className="as-round-list">{[...rounds.data].reverse().map((run, index) => <button key={run.id} aria-pressed={latestId === run.id} onClick={() => onRunChange(run.id)}>第 {index + 1} 轮 <Badge status={run.status} /></button>)}</div></section>}
    </>}
    {latestId ? <RunContent key={latestId} id={latestId} revision={revision} sample={sample} tab={detailTab} spanId={spanId} traceFocus={traceFocus} onViewChange={onViewChange} onSpanChange={onSpanChange} /> : rounds.data && <><Empty title="此场景尚无运行记录">可能仍在排队，或尚未采集到执行。可关闭后刷新任务。</Empty>{sample && <SampleResults sample={sample} />}</>}
  </Overlay>
}

function RunContent({ id, revision, sample, tab, spanId, traceFocus, onViewChange, onSpanChange }: { id: string; revision: number; sample?: Sample; tab: string; spanId: string | null; traceFocus: string | null; onViewChange: (tab: string) => void; onSpanChange: (id: string) => void }) {

  const run = useResource<Run>(`/runs/${encodeURIComponent(id)}`, revision)
  return <>
    <ResourceState {...run} hasData={Boolean(run.data)} />
    {run.data && <>
      <div className="as-run-heading"><Badge status={run.data.status} /><span>{sourceName(run.data.source)} · {taskNames[run.data.task_type] ?? '未分类'}</span><span>{date(run.data.started_at)}</span></div>
      <nav className="as-detail-tabs" aria-label="运行详情导航">{[['summary', '结果与评分'], ['trace', '调用链'], ['technical', '技术信息']].map(([key, title]) => <button key={key} aria-current={tab === key ? 'page' : undefined} onClick={() => onViewChange(key)}>{title}</button>)}</nav>
      <div hidden={tab !== 'summary'}><Summary run={run.data} revision={revision} sample={sample} /></div>
      {tab === 'trace' && <Trace id={id} revision={revision} spanId={spanId} focus={traceFocus} onSelect={onSpanChange} />}
      {tab === 'technical' && <><CopyId value={run.data.id} label="运行编号" /><CopyId value={run.data.session_id} label="会话编号" /><CopyId value={run.data.trace_id} label="追踪编号" /><CopyId value={run.data.turn_id} label="轮次编号" /><RawData value={run.data} title="运行原始字段" />{sample && <RawData value={sample} title="测试样本原始字段" />}<TechnicalArtifacts id={id} revision={revision} /></>}
    </>}
  </>
}

function Summary({ run, revision, sample }: { run: Run; revision: number; sample?: Sample }) {
  const artifacts = useResource<Payload[]>(`/runs/${encodeURIComponent(run.id)}/artifacts`, revision)
  const usage = useResource<Span[]>(`/runs/${encodeURIComponent(run.id)}/spans`, revision)
  const modelCalls = usage.data?.filter(span => span.kind === 'MODEL') ?? []
  const recordedUsage = modelCalls.flatMap(span => [span.input_tokens, span.output_tokens]).filter(finite)
  const tokens = run.tokens ?? (recordedUsage.length ? recordedUsage.reduce((sum, value) => sum + value, 0) : null)
  const usageComplete = modelCalls.length > 0 && modelCalls.every(span => span.usage_kind === 'ACTUAL')
  const [scoreRevision, setScoreRevision] = useState(0), [ticket, setTicket] = useState<{ previousId: string } | null>(null)
  const [error, setError] = useState(''), [submitting, setSubmitting] = useState(false), lock = useRef(false)
  const pollScores = useCallback((items: Evaluation[]) => items.some(item => item.status === 'RUNNING') || Boolean(ticket && (!items[0] || items[0].id === ticket.previousId)), [ticket])
  const evaluations = useResource<Evaluation[]>(`/runs/${encodeURIComponent(run.id)}/evaluations`, revision + scoreRevision, pollScores)
  const finalArtifact = artifacts.data?.find(item => item.id === run.planning_artifact_id)
  const finalBody = finalArtifact?.name === 'itinerary_draft.json'
    ? parse<{ markdownBody?: string }>(finalArtifact.body)?.markdownBody ?? ''
    : finalArtifact?.body ?? ''
  const pending = submitting || Boolean(ticket && (!evaluations.data?.[0] || evaluations.data[0].id === ticket.previousId || evaluations.data[0].status === 'RUNNING')) || evaluations.data?.some(item => item.status === 'RUNNING')
  const eligible = run.source === 'ONLINE' && run.status === 'PLANNING_COMPLETED' && Boolean(finalArtifact && finalBody)
  async function evaluate() {
    if (lock.current || pending || !eligible) return
    lock.current = true; setSubmitting(true); setError('')
    try { await request(`/runs/${encodeURIComponent(run.id)}/evaluations`, {}); setTicket({ previousId: evaluations.data?.[0]?.id ?? '' }); setScoreRevision(value => value + 1) }
    catch (e) { setError(e instanceof Error ? e.message : '评分提交失败。') }
    finally { lock.current = false; setSubmitting(false) }
  }
  return <>
    <div className="as-run-metrics"><div><span>执行用时</span><strong>{duration(run.duration_ms)}</strong></div><div><span>模型 Token{tokens != null && !usageComplete ? '（仅已记录部分）' : ''}</span><strong>{number(tokens)}</strong></div><div><span>记录完整情况</span><strong>{run.data_complete === true ? '完整' : run.data_complete === false ? '不完整' : '未记录'}</strong></div></div>
    <ResourceState {...usage} hasData={Boolean(usage.data)} />
    {run.data_complete === false && <p className="as-notice as-notice-warning">此执行记录不完整，请结合实际行程和调用证据判断。</p>}
    {sample && <SampleResults sample={sample} showScore={false} />}
    {run.source === 'ASSESSMENT' && !sample && <p className="as-footnote">此处展示执行与评分。场景级规则结果可从“测试任务”中的场景入口查看。</p>}
    <Panel title="行程评分" subtitle="评分异常与方案未达标分别显示。" action={run.source === 'ONLINE' && <button disabled={!eligible || pending || evaluations.loading} title={!eligible ? '仅支持最终行程仍可获取的已完成日常规划' : '实际调用模型进行评分，可能产生费用'} onClick={() => { void evaluate() }}>{pending ? '评分处理中…' : '发起评分'}</button>}>
      {error && <p role="alert" className="as-notice as-notice-error">{error}</p>}{ticket && pending && <p role="status" className="as-notice">评分已提交，完成后自动更新。</p>}
      <ResourceState {...evaluations} hasData={Boolean(evaluations.data)} />
      {evaluations.data && (evaluations.data.length ? <>{evaluations.data.map((item, index) => <div key={item.id} className="as-score-entry">{index > 0 ? <details className="as-disclosure"><summary>历史评分 · {date(item.created_at)}</summary><Score status={item.status} value={item.result_json} /></details> : <><div className="as-score-meta">最近评分 · {date(item.created_at)}</div><Score status={item.status} value={item.result_json} /></>}</div>)}</> : sample ? <Score status={sample.score_status} value={sample.score_json} /> : <p className="as-muted">尚未评分。仅支持为已完成且正文仍可获取的日常规划发起评分。</p>)}
    </Panel>
    <Panel title="生成的行程" subtitle="仅展示执行结束时明确保存的最终版本。"><ResourceState {...artifacts} hasData={Boolean(artifacts.data)} />{finalArtifact && finalBody ? <article className="as-markdown"><ReactMarkdown remarkPlugins={[remarkGfm]}>{defuseGfmTildes(finalBody)}</ReactMarkdown></article> : artifacts.data && <Empty title="暂无可确认的最终行程">可能尚未生成、未保存最终版本，或已超过 30 天保留期。</Empty>}</Panel>
  </>
}

function Score({ status, value }: { status: string; value: string | ScoreResult | null }) {
  const result = parse<ScoreResult>(value)
  if (status === 'ERROR') {
    const legacy = result?.errorType === 'MismatchedInputException' || result?.errorType === 'JsonParseException' ? '模型返回的评分格式不符合要求，未能解析。' : ''
    return <div className="as-notice as-notice-error"><strong>评分失败</strong><p>{result?.message || legacy || '评分服务未能完成判断。'}这不代表行程质量差。</p><RawData value={result} title="评分错误详情" /></div>
  }
  if (status !== 'SUCCEEDED') return <Badge status={status} label={status === 'RUNNING' ? '评分中' : undefined} />
  return <><div className="as-score-total"><strong>{finite(result?.total) ? result.total.toFixed(1) : '—'}<small> / 100</small></strong><Badge status={result?.passed === true ? 'PASS' : result?.passed === false ? 'FAIL' : 'UNKNOWN'} label={result?.passed === true ? '评分达标' : result?.passed === false ? '评分未达标' : '评分结果未记录'} /><span className="as-muted">达标线 {number(result?.threshold)} 分</span></div>
    {result?.score ? <><MetricRows items={[{ label: '路线连贯性', value: `${number(result.score.routeCoherence)} / 10` }, { label: '时间安排', value: `${number(result.score.timeDensity)} / 10` }, { label: '偏好匹配', value: `${number(result.score.preferenceMatch)} / 10` }]} /><p className="as-score-reason">{result.score.reason}</p>{result.score.evidence?.map((evidence, index) => <blockquote key={index}>{evidence}</blockquote>)}</> : <p className="as-muted">评分明细未记录或无法解析。</p>}</>
}

function SampleResults({ sample, showScore = true }: { sample: Sample; showScore?: boolean }) {
  const rules = parse<RuleResult[]>(sample.rule_json)
  return <><Panel title="规则检查" subtitle="无法判断不算通过，评分达标不能覆盖规则未通过。">{Array.isArray(rules) && rules.length ? <div className="as-rules">{rules.map((rule, index) => <div key={index}><div className="as-rule-head"><strong>{ruleNames[rule.type] ?? '条件检查'}</strong><Badge status={rule.status} /></div><small>{methodNames[rule.method] ?? '其他检查方式'}</small><p>{rule.evidence || '未记录判断证据'}</p></div>)}</div> : <p className="as-muted">暂无规则检查结果。</p>}</Panel>{showScore && <Panel title="行程评分"><Score status={sample.score_status} value={sample.score_json} /></Panel>}</>
}

function TechnicalArtifacts({ id, revision }: { id: string; revision: number }) {
  const resource = useResource<Payload[]>(`/runs/${encodeURIComponent(id)}/artifacts`, revision)
  return <><ResourceState {...resource} hasData={Boolean(resource.data)} />{resource.data && <RawData value={resource.data} title="全部保存的行程与版本" />}</>
}
