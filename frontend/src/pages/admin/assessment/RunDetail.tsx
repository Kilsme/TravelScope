import { usePreferences } from '../../../preferences/PreferencesProvider'
import { t, ui } from '../../../preferences/i18n'
import { useCallback, useEffect, useRef, useState } from 'react';
import Trace from './Trace';
import MarkdownView from '../../../components/MarkdownView';
import { Badge, CopyId, Empty, MetricRows, Overlay, Panel, RawData, ResourceState } from './common';
import { date, duration, finite, number, parse, request, sourceName, taskNames, useResource } from './model';
import type { BatchDetail, Evaluation, Payload, RuleResult, Run, Sample, ScoreResult, Span } from './model';
const ruleNames: Record<string, string> = { FIELD_COMPLETE: '行程信息完整性', CONSTRAINT_COVERED: '需求条件检查', TOOL_ORDER: '调用依赖检查', EXPECTED_WAITING_INPUT: '补充信息行为', BUDGET: '预算检查', DAYS: '行程天数', TIME_OVERLAP: '时间冲突', BUDGET_SUM_AND_LIMIT: '费用合计与预算' };
const methodNames: Record<string, string> = { HEURISTIC: '关键词初筛', HARD_CONSTRAINT: '明确条件核验', EVENT_EVIDENCE: '调用事件核验', EVENT_DEPENDENCY: '调用依赖核验' };
export default function RunDetail({ id, sampleId, batchId, revision, onClose, onRunChange, detailTab, spanId, traceFocus, onViewChange, onSpanChange }: {
    id: string | null;
    sampleId: string | null;
    batchId: string | null;
    revision: number;
    onClose: () => void;
    onRunChange: (id: string) => void;
    detailTab: string;
    spanId: string | null;
    traceFocus: string | null;
    onViewChange: (tab: string) => void;
    onSpanChange: (id: string) => void;
}) {
    usePreferences();
    const rounds = useResource<Run[]>(sampleId ? `/samples/${encodeURIComponent(sampleId)}/runs` : null, revision);
    const context = useResource<BatchDetail>(sampleId && batchId ? `/batches/${encodeURIComponent(batchId)}` : null, revision);
    const latestId = id ?? rounds.data?.[0]?.id ?? null;
    useEffect(() => { if (!id && rounds.data?.[0])
        onRunChange(rounds.data[0].id); }, [id, rounds.data, onRunChange]);
    const sample = context.data?.samples.find(item => item.id === sampleId);
    return <Overlay title={ui(sampleId ? t("场景结果与运行详情") : t("运行详情"))} onClose={onClose} wide expandable>
    {ui(sampleId && <><ResourceState {...rounds} hasData={Boolean(rounds.data)}/><ResourceState {...context} hasData={Boolean(context.data)}/>
      {ui(rounds.data && <section className="as-rounds"><div className="as-section-head"><h3>{ui(sample?.case_id ?? t("测试场景"))}{t("\u00B7 全部运行轮次")}</h3><span className="as-muted">{t("共")}{ui(rounds.data.length)}{t("轮")}</span></div><div className="as-round-list">{ui([...rounds.data].reverse().map((run, index) => <button key={run.id} aria-pressed={latestId === run.id} onClick={() => onRunChange(run.id)}>{t("第")}{ui(index + 1)}{t("轮")}<Badge status={run.status}/></button>))}</div></section>)}
    </>)}
    {ui(latestId ? <RunContent key={latestId} id={latestId} revision={revision} sample={sample} tab={detailTab} spanId={spanId} traceFocus={traceFocus} onViewChange={onViewChange} onSpanChange={onSpanChange}/> : rounds.data && <><Empty title={t("此场景尚无运行记录")}>{t("可能仍在排队\uFF0C或尚未采集到执行\u3002可关闭后刷新任务\u3002")}</Empty>{ui(sample && <SampleResults sample={sample}/>)}</>)}
  </Overlay>;
}
function RunContent({ id, revision, sample, tab, spanId, traceFocus, onViewChange, onSpanChange }: {
    id: string;
    revision: number;
    sample?: Sample;
    tab: string;
    spanId: string | null;
    traceFocus: string | null;
    onViewChange: (tab: string) => void;
    onSpanChange: (id: string) => void;
}) {
    usePreferences();
    const run = useResource<Run>(`/runs/${encodeURIComponent(id)}`, revision);
    return <>
    <ResourceState {...run} hasData={Boolean(run.data)}/>
    {ui(run.data && <>
      <div className="as-run-heading"><Badge status={run.data.status}/><span>{ui(sourceName(run.data.source))} · {ui(taskNames[run.data.task_type] ?? t("未分类"))}</span><span>{ui(date(run.data.started_at))}</span></div>
      <nav className="as-detail-tabs" aria-label={t("运行详情导航")}>{ui([['summary', t("结果与评分")], ['trace', t("调用链")], ['technical', t("技术信息")]].map(([key, title]) => <button key={key} aria-current={tab === key ? 'page' : undefined} onClick={() => onViewChange(key)}>{ui(title)}</button>))}</nav>
      <div hidden={tab !== 'summary'}><Summary run={run.data} revision={revision} sample={sample}/></div>
      {ui(tab === 'trace' && <Trace id={id} revision={revision} spanId={spanId} focus={traceFocus} onSelect={onSpanChange}/>)}
      {ui(tab === 'technical' && <><CopyId value={run.data.id} label={t("运行编号")}/><CopyId value={run.data.session_id} label={t("会话编号")}/><CopyId value={run.data.trace_id} label={t("追踪编号")}/><CopyId value={run.data.turn_id} label={t("轮次编号")}/><RawData value={run.data} title={t("运行原始字段")}/>{ui(sample && <RawData value={sample} title={t("测试样本原始字段")}/>)}<TechnicalArtifacts id={id} revision={revision}/></>)}
    </>)}
  </>;
}
function Summary({ run, revision, sample }: {
    run: Run;
    revision: number;
    sample?: Sample;
}) {
    usePreferences();
    const artifacts = useResource<Payload[]>(`/runs/${encodeURIComponent(run.id)}/artifacts`, revision);
    const usage = useResource<Span[]>(`/runs/${encodeURIComponent(run.id)}/spans`, revision);
    const modelCalls = usage.data?.filter(span => span.kind === 'MODEL') ?? [];
    const recordedUsage = modelCalls.flatMap(span => [span.input_tokens, span.output_tokens]).filter(finite);
    const tokens = run.tokens ?? (recordedUsage.length ? recordedUsage.reduce((sum, value) => sum + value, 0) : null);
    const usageComplete = modelCalls.length > 0 && modelCalls.every(span => span.usage_kind === 'ACTUAL');
    const [scoreRevision, setScoreRevision] = useState(0), [ticket, setTicket] = useState<{
        previousId: string;
    } | null>(null);
    const [error, setError] = useState(''), [submitting, setSubmitting] = useState(false), lock = useRef(false);
    const pollScores = useCallback((items: Evaluation[]) => items.some(item => item.status === 'RUNNING') || Boolean(ticket && (!items[0] || items[0].id === ticket.previousId)), [ticket]);
    const evaluations = useResource<Evaluation[]>(`/runs/${encodeURIComponent(run.id)}/evaluations`, revision + scoreRevision, pollScores);
    const finalArtifact = artifacts.data?.find(item => item.id === run.planning_artifact_id);
    const finalBody = finalArtifact?.name === 'itinerary_draft.json'
        ? parse<{
            markdownBody?: string;
        }>(finalArtifact.body)?.markdownBody ?? ''
        : finalArtifact?.body ?? '';
    const pending = submitting || Boolean(ticket && (!evaluations.data?.[0] || evaluations.data[0].id === ticket.previousId || evaluations.data[0].status === 'RUNNING')) || evaluations.data?.some(item => item.status === 'RUNNING');
    const eligible = run.source === 'ONLINE' && run.status === 'PLANNING_COMPLETED' && Boolean(finalArtifact && finalBody);
    async function evaluate() {
        if (lock.current || pending || !eligible)
            return;
        lock.current = true;
        setSubmitting(true);
        setError('');
        try {
            await request(`/runs/${encodeURIComponent(run.id)}/evaluations`, {});
            setTicket({ previousId: evaluations.data?.[0]?.id ?? '' });
            setScoreRevision(value => value + 1);
        }
        catch (e) {
            setError(e instanceof Error ? e.message : '评分提交失败。');
        }
        finally {
            lock.current = false;
            setSubmitting(false);
        }
    }
    return <>
    <div className="as-run-metrics"><div><span>{t("执行用时")}</span><strong>{ui(duration(run.duration_ms))}</strong></div><div><span>{t("模型 Token")}{ui(tokens != null && !usageComplete ? t("\uFF08仅已记录部分\uFF09") : '')}</span><strong>{ui(number(tokens))}</strong></div><div><span>{t("记录完整情况")}</span><strong>{ui(run.data_complete === true ? t("完整") : run.data_complete === false ? t("不完整") : t("未记录"))}</strong></div></div>
    <ResourceState {...usage} hasData={Boolean(usage.data)}/>
    {ui(run.data_complete === false && <p className="as-notice as-notice-warning">{t("此执行记录不完整\uFF0C请结合实际行程和调用证据判断\u3002")}</p>)}
    {ui(sample && <SampleResults sample={sample} showScore={false}/>)}
    {ui(run.source === 'ASSESSMENT' && !sample && <p className="as-footnote">{t("此处展示执行与评分\u3002场景级规则结果可从\u201C测试任务\u201D中的场景入口查看\u3002")}</p>)}
    <Panel title={t("行程评分")} subtitle={t("评分异常与方案未达标分别显示\u3002")} action={run.source === 'ONLINE' && <button disabled={!eligible || pending || evaluations.loading} title={ui(!eligible ? t("仅支持最终行程仍可获取的已完成日常规划") : t("实际调用模型进行评分\uFF0C可能产生费用"))} onClick={() => { void evaluate(); }}>{ui(pending ? t("评分处理中\u2026") : t("发起评分"))}</button>}>
      {ui(error && <p role="alert" className="as-notice as-notice-error">{ui(error)}</p>)}{ui(ticket && pending && <p role="status" className="as-notice">{t("评分已提交\uFF0C完成后自动更新\u3002")}</p>)}
      <ResourceState {...evaluations} hasData={Boolean(evaluations.data)}/>
      {ui(evaluations.data && (evaluations.data.length ? <>{ui(evaluations.data.map((item, index) => <div key={item.id} className="as-score-entry">{ui(index > 0 ? <details className="as-disclosure"><summary>{t("历史评分 \u00B7")}{ui(date(item.created_at))}</summary><Score status={item.status} value={item.result_json}/></details> : <><div className="as-score-meta">{t("最近评分 \u00B7")}{ui(date(item.created_at))}</div><Score status={item.status} value={item.result_json}/></>)}</div>))}</> : sample ? <Score status={sample.score_status} value={sample.score_json}/> : <p className="as-muted">{t("尚未评分\u3002仅支持为已完成且正文仍可获取的日常规划发起评分\u3002")}</p>))}
    </Panel>
    <Panel title={t("生成的行程")} subtitle={t("仅展示执行结束时明确保存的最终版本\u3002")}><ResourceState {...artifacts} hasData={Boolean(artifacts.data)}/>{ui(finalArtifact && finalBody ? <article className="as-markdown"><MarkdownView text={finalBody} plain/></article> : artifacts.data && <Empty title={t("暂无可确认的最终行程")}>{t("可能尚未生成\u3001未保存最终版本\uFF0C或已超过 30 天保留期\u3002")}</Empty>)}</Panel>
  </>;
}
function Score({ status, value }: {
    status: string;
    value: string | ScoreResult | null;
}) {
    usePreferences();
    const result = parse<ScoreResult>(value);
    if (status === 'ERROR') {
        const legacy = result?.errorType === 'MismatchedInputException' || result?.errorType === 'JsonParseException' ? '模型返回的评分格式不符合要求，未能解析。' : '';
        return <div className="as-notice as-notice-error"><strong>{t("评分失败")}</strong><p>{ui(result?.message || legacy || t("评分服务未能完成判断\u3002"))}{t("这不代表行程质量差\u3002")}</p><RawData value={result} title={t("评分错误详情")}/></div>;
    }
    if (status !== 'SUCCEEDED')
        return <Badge status={status} label={ui(status === 'RUNNING' ? t("评分中") : undefined)}/>;
    return <><div className="as-score-total"><strong>{ui(finite(result?.total) ? result.total.toFixed(1) : '—')}<small> / 100</small></strong><Badge status={result?.passed === true ? 'PASS' : result?.passed === false ? 'FAIL' : 'UNKNOWN'} label={ui(result?.passed === true ? t("评分达标") : result?.passed === false ? t("评分未达标") : t("评分结果未记录"))}/><span className="as-muted">{t("达标线")}{ui(number(result?.threshold))}{t("分")}</span></div>
    {ui(result?.score ? <><MetricRows items={[{ label: t("路线连贯性"), value: `${number(result.score.routeCoherence)} / 10` }, { label: t("时间安排"), value: `${number(result.score.timeDensity)} / 10` }, { label: t("偏好匹配"), value: `${number(result.score.preferenceMatch)} / 10` }]}/><p className="as-score-reason">{result.score.reason}</p>{ui(result.score.evidence?.map((evidence, index) => <blockquote key={index}>{evidence}</blockquote>))}</> : <p className="as-muted">{t("评分明细未记录或无法解析\u3002")}</p>)}</>;
}
function SampleResults({ sample, showScore = true }: {
    sample: Sample;
    showScore?: boolean;
}) {
    usePreferences();
    const rules = parse<RuleResult[]>(sample.rule_json);
    return <><Panel title={t("规则检查")} subtitle={t("无法判断不算通过\uFF0C评分达标不能覆盖规则未通过\u3002")}>{ui(Array.isArray(rules) && rules.length ? <div className="as-rules">{ui(rules.map((rule, index) => <div key={index}><div className="as-rule-head"><strong>{ui(ruleNames[rule.type] ?? t("条件检查"))}</strong><Badge status={rule.status}/></div><small>{ui(methodNames[rule.method] ?? t("其他检查方式"))}</small><p>{ui(rule.evidence || t("未记录判断证据"))}</p></div>))}</div> : <p className="as-muted">{t("暂无规则检查结果\u3002")}</p>)}</Panel>{ui(showScore && <Panel title={t("行程评分")}><Score status={sample.score_status} value={sample.score_json}/></Panel>)}</>;
}
function TechnicalArtifacts({ id, revision }: {
    id: string;
    revision: number;
}) {
    usePreferences();
    const resource = useResource<Payload[]>(`/runs/${encodeURIComponent(id)}/artifacts`, revision);
    return <><ResourceState {...resource} hasData={Boolean(resource.data)}/>{ui(resource.data && <RawData value={resource.data} title={t("全部保存的行程与版本")}/>)}</>;
}
