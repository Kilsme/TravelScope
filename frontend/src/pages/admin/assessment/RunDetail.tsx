import { useCallback, useEffect, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Badge, CopyId, Empty, MetricRows, Overlay, Panel, RawData, ResourceState } from './common'
import { cost, date, duration, finite, number, parse, request, sourceName, taskNames, useResource } from './model'
import type { BatchDetail, Evaluation, Payload, RuleResult, Run, Sample, ScoreResult, Span } from './model'

const ruleNames: Record<string, string> = { FIELD_COMPLETE: '行程信息完整性', CONSTRAINT_COVERED: '需求条件检查', TOOL_ORDER: '调用依赖检查', EXPECTED_WAITING_INPUT: '补充信息行为', BUDGET: '预算检查', DAYS: '行程天数', TIME_OVERLAP: '时间冲突', BUDGET_SUM_AND_LIMIT: '费用合计与预算' }
const methodNames: Record<string, string> = { HEURISTIC: '关键词初筛', HARD_CONSTRAINT: '明确条件核验', EVENT_EVIDENCE: '调用事件核验', EVENT_DEPENDENCY: '调用依赖核验' }
const stepNames: Record<string, string> = { 'planning-agent': '行程规划', 'itinerary-agent': '行程编排', 'poi-research': '景点检索', 'route-optimizer': '路线优化', 'reviewer-agent': '行程审阅', 'posthoc-scorer': '行程评分', ask_user: '补充信息', 'travel-master': '需求处理' }
const kindNames: Record<string, string> = { AGENT: '任务步骤', MODEL: '模型调用', TOOL: '工具调用' }

export default function RunDetail({ id, sampleId, batchId, revision, onClose, onRunChange }: { id: string | null; sampleId: string | null; batchId: string | null; revision: number; onClose: () => void; onRunChange: (id: string) => void }) {
  const rounds = useResource<Run[]>(sampleId ? `/samples/${encodeURIComponent(sampleId)}/runs` : null, revision)
  const context = useResource<BatchDetail>(sampleId && batchId ? `/batches/${encodeURIComponent(batchId)}` : null, revision)
  const latestId = id ?? rounds.data?.[0]?.id ?? null
  useEffect(() => { if (!id && rounds.data?.[0]) onRunChange(rounds.data[0].id) }, [id, rounds.data, onRunChange])
  const sample = context.data?.samples.find(item => item.id === sampleId)
  return <Overlay title={sampleId ? '场景结果与运行详情' : '运行详情'} onClose={onClose} wide>
    {sampleId && <><ResourceState {...rounds} hasData={Boolean(rounds.data)} /><ResourceState {...context} hasData={Boolean(context.data)} />
      {rounds.data && <section className="as-rounds"><div className="as-section-head"><h3>{sample?.case_id ?? '测试场景'} · 全部运行轮次</h3><span className="as-muted">共 {rounds.data.length} 轮</span></div><div className="as-round-list">{[...rounds.data].reverse().map((run, index) => <button key={run.id} aria-pressed={latestId === run.id} onClick={() => onRunChange(run.id)}>第 {index + 1} 轮 <Badge status={run.status} /></button>)}</div></section>}
    </>}
    {latestId ? <RunContent key={latestId} id={latestId} revision={revision} sample={sample} /> : rounds.data && <><Empty title="此场景尚无运行记录">可能仍在排队，或尚未采集到执行。可关闭后刷新任务。</Empty>{sample && <SampleResults sample={sample} />}</>}
  </Overlay>
}

function RunContent({ id, revision, sample }: { id: string; revision: number; sample?: Sample }) {
  const [tab, setTab] = useState('summary')
  const run = useResource<Run>(`/runs/${encodeURIComponent(id)}`, revision)
  return <>
    <ResourceState {...run} hasData={Boolean(run.data)} />
    {run.data && <>
      <div className="as-run-heading"><Badge status={run.data.status} /><span>{sourceName(run.data.source)} · {taskNames[run.data.task_type] ?? '未分类'}</span><span>{date(run.data.started_at)}</span></div>
      <nav className="as-detail-tabs" aria-label="运行详情导航">{[['summary', '结果摘要'], ['timeline', '调用过程'], ['technical', '技术信息']].map(([key, title]) => <button key={key} aria-current={tab === key ? 'page' : undefined} onClick={() => setTab(key)}>{title}</button>)}</nav>
      <div hidden={tab !== 'summary'}><Summary run={run.data} revision={revision} sample={sample} /></div>
      {tab === 'timeline' && <Timeline id={id} revision={revision} />}
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
  const pending = submitting || Boolean(ticket && (!evaluations.data?.[0] || evaluations.data[0].id === ticket.previousId || evaluations.data[0].status === 'RUNNING')) || evaluations.data?.some(item => item.status === 'RUNNING')
  const eligible = run.source === 'ONLINE' && run.status === 'PLANNING_COMPLETED' && Boolean(finalArtifact)
  async function evaluate() {
    if (lock.current || pending || !eligible) return
    lock.current = true; setSubmitting(true); setError('')
    try { await request(`/runs/${encodeURIComponent(run.id)}/evaluations`, {}); setTicket({ previousId: evaluations.data?.[0]?.id ?? '' }); setScoreRevision(value => value + 1) }
    catch (e) { setError(e instanceof Error ? e.message : '评分提交失败。') }
    finally { lock.current = false; setSubmitting(false) }
  }
  return <>
    <div className="as-run-metrics"><div><span>执行用时</span><strong>{duration(run.duration_ms)}</strong></div><div><span>模型用量{tokens != null && !usageComplete ? '（仅已记录部分）' : ''}</span><strong>{number(tokens)}</strong></div><div><span>记录完整情况</span><strong>{run.data_complete === true ? '完整' : run.data_complete === false ? '不完整' : '未记录'}</strong></div></div>
    <ResourceState {...usage} hasData={Boolean(usage.data)} />
    {run.data_complete === false && <p className="as-notice as-notice-warning">此执行记录不完整，请结合实际行程和调用证据判断。</p>}
    {sample && <SampleResults sample={sample} showScore={false} />}
    {run.source === 'ASSESSMENT' && !sample && <p className="as-footnote">此处展示执行与评分。场景级规则结果可从“测试任务”中的场景入口查看。</p>}
    <Panel title="行程评分" subtitle="评分异常与方案未达标分别显示。" action={run.source === 'ONLINE' && <button disabled={!eligible || pending || evaluations.loading} title={!eligible ? '仅支持最终行程仍可获取的已完成日常规划' : '实际调用模型进行评分，可能产生费用'} onClick={() => { void evaluate() }}>{pending ? '评分处理中…' : '发起评分'}</button>}>
      {error && <p role="alert" className="as-notice as-notice-error">{error}</p>}{ticket && pending && <p role="status" className="as-notice">评分已提交，完成后自动更新。</p>}
      <ResourceState {...evaluations} hasData={Boolean(evaluations.data)} />
      {evaluations.data && (evaluations.data.length ? <>{evaluations.data.map((item, index) => <div key={item.id} className="as-score-entry">{index > 0 ? <details className="as-disclosure"><summary>历史评分 · {date(item.created_at)}</summary><Score status={item.status} value={item.result_json} /></details> : <><div className="as-score-meta">最近评分 · {date(item.created_at)}</div><Score status={item.status} value={item.result_json} /></>}</div>)}</> : sample ? <Score status={sample.score_status} value={sample.score_json} /> : <p className="as-muted">尚未评分。仅支持为已完成且正文仍可获取的日常规划发起评分。</p>)}
    </Panel>
    <Panel title="生成的行程" subtitle="仅展示执行结束时明确保存的最终版本。"><ResourceState {...artifacts} hasData={Boolean(artifacts.data)} />{finalArtifact ? <article className="as-markdown"><ReactMarkdown remarkPlugins={[remarkGfm]}>{finalArtifact.body}</ReactMarkdown></article> : artifacts.data && <Empty title="暂无可确认的最终行程">可能尚未生成、未保存最终版本，或已超过 30 天保留期。</Empty>}</Panel>
  </>
}

function Score({ status, value }: { status: string; value: string | ScoreResult | null }) {
  const result = parse<ScoreResult>(value)
  if (status === 'ERROR') return <div className="as-notice as-notice-error"><strong>评分失败</strong><p>评分服务未能完成判断，不代表行程质量差。{result?.errorType && <span>错误详情可在原始记录中查看。</span>}</p><RawData value={result} title="评分错误详情" /></div>
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

function Timeline({ id, revision }: { id: string; revision: number }) {
  const resource = useResource<Span[]>(`/runs/${encodeURIComponent(id)}/spans`, revision)
  const [expanded, setExpanded] = useState<string | null>(null)
  const spans = resource.data ?? []
  const phases = [...new Set(spans.map(span => span.phase || 'OTHER'))].sort((a, b) => (a === 'PLANNING' ? -1 : a === 'SCORING' ? 1 : 0) - (b === 'PLANNING' ? -1 : b === 'SCORING' ? 1 : 0))
  function depth(span: Span) { let count = 0, parent = span.parent_id; const seen = new Set([span.id]); while (parent && !seen.has(parent)) { seen.add(parent); const node = spans.find(item => item.id === parent); if (!node) break; count++; parent = node.parent_id }; return Math.min(count, 4) }
  return <><p className="as-footnote">按实际开始时间展示；规划和评分阶段使用各自的时间刻度。展开步骤可查看已脱敏的调用内容。</p><ResourceState {...resource} hasData={Boolean(resource.data)} />{resource.data && !spans.length && <Empty title="暂无调用过程记录" />}{phases.map(phase => {
    const group = spans.filter(span => (span.phase || 'OTHER') === phase)
    const starts = group.map(span => Date.parse(span.started_at)).filter(Number.isFinite), start = Math.min(...starts)
    const end = Math.max(...group.map(span => { const time = Date.parse(span.started_at); return Number.isFinite(time) ? Math.max(time + (span.duration_ms ?? 0), Date.parse(span.ended_at ?? '') || time) : start }))
    const total = Math.max(1, end - start)
    return <section className="as-timeline-phase" key={phase}><h3>{phase === 'PLANNING' ? '规划阶段' : phase === 'SCORING' ? '评分阶段' : '其他调用'}</h3>{group.map(span => {
      const offset = Date.parse(span.started_at) - start
      const hasTime = Number.isFinite(offset) && finite(span.duration_ms)
      return <div className="as-timeline-step" key={span.id} style={{ marginLeft: depth(span) * 16 }}><div className="as-timeline-step-head"><div><span className="as-step-kind">{kindNames[span.kind] ?? '执行步骤'}</span><strong>{stepNames[span.name] ?? (span.kind === 'MODEL' ? '模型处理' : span.kind === 'TOOL' ? '外部工具调用' : '任务处理')}</strong></div><Badge status={span.status} label={span.status === 'ERROR' ? '调用失败' : undefined} /></div>
        <div className="as-timeline-meta"><span>{date(span.started_at)}</span><span>{duration(span.duration_ms)}</span>{span.attempt_index != null && <span>尝试 {span.attempt_index}</span>}</div>
        {hasTime && <div className="as-timeline-track" aria-label={`耗时 ${duration(span.duration_ms)}`}><span style={{ left: `${Math.max(0, offset / total * 100)}%`, width: `${Math.min(100 - offset / total * 100, Math.max(0.5, span.duration_ms! / total * 100))}%` }} /></div>}
        <div className="as-timeline-meta"><span>{span.kind === 'AGENT' ? '子调用汇总用量' : '输入 / 输出用量'} {number(span.aggregate_input_tokens ?? span.input_tokens)} / {number(span.aggregate_output_tokens ?? span.output_tokens)}</span><span>已记录费用 {cost(span.cost)}{span.cost_complete !== true ? '（不完整）' : ''}</span></div>
        {span.error_type && <p className="as-error-text">调用发生错误，详见技术记录。</p>}<div className="as-button-row"><button className="as-link" onClick={() => setExpanded(expanded === span.id ? null : span.id)} aria-expanded={expanded === span.id}>{expanded === span.id ? '收起调用内容' : '查看调用内容（已脱敏）'}</button></div>
        {expanded === span.id && <CallPayload id={span.id} />}<RawData value={span} title="步骤技术记录" />
      </div>
    })}</section>
  })}</>
}

function CallPayload({ id }: { id: string }) {
  const resource = useResource<Payload[]>(`/spans/${encodeURIComponent(id)}/payload`)
  return <div className="as-call-content"><ResourceState {...resource} hasData={Boolean(resource.data)} />{resource.data && (resource.data.length ? resource.data.map((item, index) => <div key={item.id ?? index}><h4>{item.kind === 'INPUT' ? '输入内容' : item.kind === 'OUTPUT' ? '返回内容' : '调用内容'}</h4><pre>{item.body}</pre></div>) : <p className="as-muted">正文未记录或已超过 30 天保留期。</p>)}</div>
}
