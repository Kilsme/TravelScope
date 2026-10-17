import Components from './Components'
import { Badge, Hint, MetricRows, Panel, ResourceState } from './common'
import { cost, date, duration, modeName, number, percent, useResource } from './model'
import type { Overview as OverviewData, Source, ToolMode, ComponentMetric } from './model'

export default function Overview({ source, mode, revision, onScope, onInspect }: { source: Source; mode: ToolMode; revision: number; onScope: (source: Source, mode: ToolMode) => void; onInspect: (component: ComponentMetric) => void }) {
  const resource = useResource<OverviewData>(`/overview?source=${source}${source === 'ASSESSMENT' ? `&toolMode=${mode}` : ''}`, revision)
  const data = resource.data, ingestion = data?.ingestion
  return <>
    <div className="as-scope"><div className="as-segment" role="group" aria-label="统计来源">{(['ONLINE', 'ASSESSMENT'] as const).map(value => <button key={value} aria-pressed={source === value} onClick={() => onScope(value, mode)}>{value === 'ONLINE' ? '日常使用' : '测试运行'}</button>)}</div>
      {source === 'ASSESSMENT' && <label className="as-inline-label">运行方式<select aria-label="运行方式" value={mode} onChange={e => onScope(source, e.target.value as ToolMode)}><option value="FIXTURE">固定工具数据</option><option value="LIVE">真实工具</option></select></label>}
      <span className="as-scope-note">当前保留记录的汇总{source === 'ASSESSMENT' && ` · ${modeName(mode)}`}</span><span className="as-updated" aria-live="polite">{resource.loading ? '更新中…' : resource.updatedAt ? `更新于 ${date(resource.updatedAt)}` : ''}</span>
    </div>
    <ResourceState {...resource} hasData={Boolean(data)} />
    {data && <>
      <section className="as-kpis" aria-label="关键表现">
        <div><div className="as-kpi-label">运行次数<Hint>当前统计范围内的执行记录数，不等同于测试场景数量。</Hint></div><strong>{number(data.sample_count)}</strong><small>所有任务类型的执行记录</small></div>
        <div><div className="as-kpi-label">正常响应率<Hint>已回复、等待补充信息或完成规划的执行数，占全部执行记录的比例。</Hint></div><strong>{percent(data.execution_success_rate)}</strong><small>包含已回复、补充信息和完成规划</small></div>
        <div><div className="as-kpi-label">P50 规划耗时<Hint>完整规划耗时的中位数（P50），约 50% 的完整规划在此时长内完成。</Hint></div><strong>{duration(data.p50_ms)}</strong><small>典型用时 · {number(data.completed_plans)} 次完整规划</small></div>
        <div><div className="as-kpi-label">P95 规划耗时<Hint>完整规划耗时的第 95 百分位（P95），约 95% 的完整规划在此时长内完成。</Hint></div><strong>{duration(data.p95_ms)}</strong><small>95% 完整规划的耗时不超过此值</small></div>
      </section>
      {data.sample_count === 0 && <p className="as-notice">此范围内暂无执行记录。可以切换统计来源，或新建测试。</p>}
      {ingestion && (!ingestion.enabled || ingestion.degraded) && <div className="as-notice as-notice-warning" role="status">{!ingestion.enabled ? '运行记录采集尚未启用，当前仅展示已保留的数据。' : '部分运行记录可能缺失，请结合记录完整情况判断结果。'}</div>}
      <div className="as-overview-summary"><span>输入 Token <strong>{number(data.known_input_tokens)}</strong></span><span>输出 Token <strong>{number(data.known_output_tokens)}</strong></span><span>已记录费用 <strong>{cost(data.known_cost)}</strong>{data.cost_complete !== true && '（不完整）'}</span><button className="as-link" onClick={() => document.getElementById('as-overview-detail')?.scrollIntoView({ block: 'start' })}>查看性能、费用与质量明细 ↓</button></div>
    </>}
    <Components source={source} mode={mode} revision={revision} onInspect={onInspect} />
    {data && <>
      <div className="as-grid" id="as-overview-detail">
        <Panel title="规划性能" subtitle="区分执行是否完成与行程是否达标。"><MetricRows items={[
          { label: '已生成完整行程', value: `${number(data.completed_plans)} 次` }, { label: '未正常完成的比例', value: percent(data.failure_rate), hint: '包括失败、中断、未完整完成和超时；不包括主动取消。' },
          { label: '超时比例', value: percent(data.timeout_rate) }, { label: '失败执行平均用时', value: duration(data.failed_mean_ms), hint: '按后端口径统计失败、超时、取消和中断执行。' },
        ]} /><div className="as-visible-metrics"><MetricRows items={[{ label: '完整规划平均用时', value: duration(data.mean_ms) }, { label: '规划用时波动（标准差）', value: duration(data.stddev_ms) }]} /></div></Panel>
        <Panel title="Token 与费用" subtitle="费用按接口记录展示，缺失部分不计为零。"><MetricRows items={[
          { label: '已记录费用', value: cost(data.known_cost) }, { label: '输入 / 输出 Token', value: `${number(data.known_input_tokens)} / ${number(data.known_output_tokens)}`, hint: '模型实际返回的输入 / 输出 Token，用于衡量模型处理的文本用量。' },
          { label: 'Token 记录完整率', value: percent(data.usage_completeness) }, { label: '工具调用次数', value: number(data.tool_calls) },
        ]} />{data.cost_complete !== true && <p className="as-footnote">仅包含已记录部分，无法据此确认完整费用。</p>}<div className="as-visible-metrics"><h3>规划、评分费用与 Token</h3><MetricRows items={[
          { label: '规划输入 / 输出 Token', value: `${number(data.planning_input_tokens)} / ${number(data.planning_output_tokens)}` }, { label: '评分输入 / 输出 Token', value: `${number(data.scoring_input_tokens)} / ${number(data.scoring_output_tokens)}` }, { label: '规划已记录费用', value: cost(data.planning_known_cost) }, { label: '工具已记录费用', value: cost(data.known_tool_cost) }, { label: '评分已记录费用', value: cost(data.scoring_known_cost) }, { label: '评分 Token', value: number(data.scoring_known_tokens) },
        ]} /></div></Panel>
        {source === 'ASSESSMENT' && <Panel title="测试质量" subtitle={`基于 ${number(data.assessment_sample_count)} 个已产生执行记录的测试样本，其中 ${number(data.scored_sample_count)} 个评分成功。`}><MetricRows items={[
          { label: '整体达标率', value: percent(data.overall_pass_rate), hint: '测试样本中，运行成功、全部规则通过且行程评分达标的比例。无法判断的规则不算通过。' }, { label: '规则检查通过率', value: percent(data.rule_pass_rate) }, { label: '行程评分达标率', value: percent(data.score_pass_rate) }, { label: '明确条件的可核验比例', value: percent(data.hard_constraint_coverage), hint: '明确条件检查中能够判断为通过或未通过的比例，不代表通过率。' },
          { label: '评分失败样本数', value: number(data.score_errors) },
        ]} /><p className="as-footnote">各通过率以当前范围内已产生记录的测试样本为分母。关键词检查仅作初筛；高评分不能覆盖规则未通过的结果。</p></Panel>}
        <Panel title="记录完整情况" subtitle="判断当前指标是否有足够的数据支持。" action={ingestion && <Badge status={ingestion.enabled && !ingestion.degraded ? 'COMPLETED' : 'UNKNOWN'} label={!ingestion.enabled ? '未启用采集' : ingestion.degraded ? '存在采集异常' : '采集已启用'} />}>
          <MetricRows items={[{ label: '执行记录完整率', value: percent(data.data_completeness) }, { label: '费用记录', value: data.cost_complete === true ? '完整' : data.cost_complete === false ? '不完整' : '暂无数据' }]} />
          {ingestion && <details className="as-disclosure"><summary>查看采集计数</summary><MetricRows items={[{ label: '等待写入', value: number(ingestion.queueDepth) }, { label: '已丢弃写入', value: number(ingestion.droppedWrites) }, { label: '写入失败', value: number(ingestion.persistenceFailures) }, { label: '待标记不完整的记录', value: number(ingestion.pendingIncompleteRuns) }]} /></details>}<p className="as-footnote">调用内容与行程保留 30 天，指标与测试记录保留 180 天。</p>
        </Panel>
      </div>
    </>}
  </>
}
