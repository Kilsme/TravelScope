import { usePreferences } from '../../../preferences/PreferencesProvider'
import { t, ui } from '../../../preferences/i18n'
import Components from './Components';
import { Badge, Hint, MetricRows, Panel, ResourceState } from './common';
import { cost, date, duration, modeName, number, percent, useResource } from './model';
import type { Overview as OverviewData, Source, ToolMode, ComponentMetric } from './model';
export default function Overview({ source, mode, revision, onScope, onInspect }: {
    source: Source;
    mode: ToolMode;
    revision: number;
    onScope: (source: Source, mode: ToolMode) => void;
    onInspect: (component: ComponentMetric) => void;
}) {
    usePreferences();
    const resource = useResource<OverviewData>(`/overview?source=${source}${source === 'ASSESSMENT' ? `&toolMode=${mode}` : ''}`, revision);
    const data = resource.data, ingestion = data?.ingestion;
    return <>
    <div className="as-scope"><div className="as-segment" role="group" aria-label={t("统计来源")}>{ui((['ONLINE', 'ASSESSMENT'] as const).map(value => <button key={value} aria-pressed={source === value} onClick={() => onScope(value, mode)}>{ui(value === 'ONLINE' ? t("日常使用") : t("测试运行"))}</button>))}</div>
      {ui(source === 'ASSESSMENT' && <label className="as-inline-label">{t("运行方式")}<select aria-label={t("运行方式")} value={mode} onChange={e => onScope(source, e.target.value as ToolMode)}><option value="FIXTURE">{t("固定工具数据")}</option><option value="LIVE">{t("真实工具")}</option></select></label>)}
      <span className="as-scope-note">{t("当前保留记录的汇总")}{ui(source === 'ASSESSMENT' && ` · ${modeName(mode)}`)}</span><span className="as-updated" aria-live="polite">{ui(resource.loading ? t("更新中\u2026") : resource.updatedAt ? t("更新于 {0}", { "0": date(resource.updatedAt) }) : '')}</span>
    </div>
    <ResourceState {...resource} hasData={Boolean(data)}/>
    {ui(data && <>
      <section className="as-kpis" aria-label={t("关键表现")}>
        <div><div className="as-kpi-label">{t("运行次数")}<Hint>{t("当前统计范围内的执行记录数\uFF0C不等同于测试场景数量\u3002")}</Hint></div><strong>{ui(number(data.sample_count))}</strong><small>{t("所有任务类型的执行记录")}</small></div>
        <div><div className="as-kpi-label">{t("正常响应率")}<Hint>{t("已回复\u3001等待补充信息或完成规划的执行数\uFF0C占全部执行记录的比例\u3002")}</Hint></div><strong>{ui(percent(data.execution_success_rate))}</strong><small>{t("包含已回复\u3001补充信息和完成规划")}</small></div>
        <div><div className="as-kpi-label">{t("P50 规划耗时")}<Hint>{t("完整规划耗时的中位数\uFF08P50\uFF09\uFF0C约 50% 的完整规划在此时长内完成\u3002")}</Hint></div><strong>{ui(duration(data.p50_ms))}</strong><small>{t("典型用时 \u00B7")}{ui(number(data.completed_plans))}{t("次完整规划")}</small></div>
        <div><div className="as-kpi-label">{t("P95 规划耗时")}<Hint>{t("完整规划耗时的第 95 百分位\uFF08P95\uFF09\uFF0C约 95% 的完整规划在此时长内完成\u3002")}</Hint></div><strong>{ui(duration(data.p95_ms))}</strong><small>{t("95% 完整规划的耗时不超过此值")}</small></div>
      </section>
      {ui(data.sample_count === 0 && <p className="as-notice">{t("此范围内暂无执行记录\u3002可以切换统计来源\uFF0C或新建测试\u3002")}</p>)}
      {ui(ingestion && (!ingestion.enabled || ingestion.degraded) && <div className="as-notice as-notice-warning" role="status">{ui(!ingestion.enabled ? t("运行记录采集尚未启用\uFF0C当前仅展示已保留的数据\u3002") : t("部分运行记录可能缺失\uFF0C请结合记录完整情况判断结果\u3002"))}</div>)}
      <div className="as-overview-summary"><span>{t("输入 Token")}<strong>{ui(number(data.known_input_tokens))}</strong></span><span>{t("输出 Token")}<strong>{ui(number(data.known_output_tokens))}</strong></span><span>{t("已记录费用")}<strong>{ui(cost(data.known_cost))}</strong>{ui(data.cost_complete !== true && t("\uFF08不完整\uFF09"))}</span><button className="as-link" onClick={() => document.getElementById('as-overview-detail')?.scrollIntoView({ block: 'start' })}>{t("查看性能\u3001费用与质量明细 \u2193")}</button></div>
    </>)}
    <Components source={source} mode={mode} revision={revision} onInspect={onInspect}/>
    {ui(data && <>
      <div className="as-grid" id="as-overview-detail">
        <Panel title={t("规划性能")} subtitle={t("区分执行是否完成与行程是否达标\u3002")}><MetricRows items={[
                { label: t("已生成完整行程"), value: t("{0} 次", { "0": number(data.completed_plans) }) }, { label: t("未正常完成的比例"), value: percent(data.failure_rate), hint: t("包括失败\u3001中断\u3001未完整完成和超时\uFF1B不包括主动取消\u3002") },
                { label: t("超时比例"), value: percent(data.timeout_rate) }, { label: t("失败执行平均用时"), value: duration(data.failed_mean_ms), hint: t("按后端口径统计失败\u3001超时\u3001取消和中断执行\u3002") },
            ]}/><div className="as-visible-metrics"><MetricRows items={[{ label: t("完整规划平均用时"), value: duration(data.mean_ms) }, { label: t("规划用时波动\uFF08标准差\uFF09"), value: duration(data.stddev_ms) }]}/></div></Panel>
        <Panel title={t("Token 与费用")} subtitle={t("费用按接口记录展示\uFF0C缺失部分不计为零\u3002")}><MetricRows items={[
                { label: t("已记录费用"), value: cost(data.known_cost) }, { label: t("输入 / 输出 Token"), value: `${number(data.known_input_tokens)} / ${number(data.known_output_tokens)}`, hint: t("模型实际返回的输入 / 输出 Token\uFF0C用于衡量模型处理的文本用量\u3002") },
                { label: t("Token 记录完整率"), value: percent(data.usage_completeness) }, { label: t("工具调用次数"), value: number(data.tool_calls) },
            ]}/>{ui(data.cost_complete !== true && <p className="as-footnote">{t("仅包含已记录部分\uFF0C无法据此确认完整费用\u3002")}</p>)}<div className="as-visible-metrics"><h3>{t("规划\u3001评分费用与 Token")}</h3><MetricRows items={[
                { label: t("规划输入 / 输出 Token"), value: `${number(data.planning_input_tokens)} / ${number(data.planning_output_tokens)}` }, { label: t("评分输入 / 输出 Token"), value: `${number(data.scoring_input_tokens)} / ${number(data.scoring_output_tokens)}` }, { label: t("规划已记录费用"), value: cost(data.planning_known_cost) }, { label: t("工具已记录费用"), value: cost(data.known_tool_cost) }, { label: t("评分已记录费用"), value: cost(data.scoring_known_cost) }, { label: t("评分 Token"), value: number(data.scoring_known_tokens) },
            ]}/></div></Panel>
        {ui(source === 'ASSESSMENT' && <Panel title={t("测试质量")} subtitle={ui(t("基于 {0} 个已产生执行记录的测试样本\uFF0C其中 {1} 个评分成功\u3002", { "0": number(data.assessment_sample_count), "1": number(data.scored_sample_count) }))}><MetricRows items={[
                    { label: t("整体达标率"), value: percent(data.overall_pass_rate), hint: t("测试样本中\uFF0C运行成功\u3001全部规则通过且行程评分达标的比例\u3002无法判断的规则不算通过\u3002") }, { label: t("规则检查通过率"), value: percent(data.rule_pass_rate) }, { label: t("行程评分达标率"), value: percent(data.score_pass_rate) }, { label: t("明确条件的可核验比例"), value: percent(data.hard_constraint_coverage), hint: t("明确条件检查中能够判断为通过或未通过的比例\uFF0C不代表通过率\u3002") },
                    { label: t("评分失败样本数"), value: number(data.score_errors) },
                ]}/><p className="as-footnote">{t("各通过率以当前范围内已产生记录的测试样本为分母\u3002关键词检查仅作初筛\uFF1B高评分不能覆盖规则未通过的结果\u3002")}</p></Panel>)}
        <Panel title={t("记录完整情况")} subtitle={t("判断当前指标是否有足够的数据支持\u3002")} action={ingestion && <Badge status={ingestion.enabled && !ingestion.degraded ? 'COMPLETED' : 'UNKNOWN'} label={ui(!ingestion.enabled ? t("未启用采集") : ingestion.degraded ? t("存在采集异常") : t("采集已启用"))}/>}>
          <MetricRows items={[{ label: t("执行记录完整率"), value: percent(data.data_completeness) }, { label: t("费用记录"), value: data.cost_complete === true ? t("完整") : data.cost_complete === false ? t("不完整") : t("暂无数据") }]}/>
          {ui(ingestion && <details className="as-disclosure"><summary>{t("查看采集计数")}</summary><MetricRows items={[{ label: t("等待写入"), value: number(ingestion.queueDepth) }, { label: t("已丢弃写入"), value: number(ingestion.droppedWrites) }, { label: t("写入失败"), value: number(ingestion.persistenceFailures) }, { label: t("待标记不完整的记录"), value: number(ingestion.pendingIncompleteRuns) }]}/></details>)}<p className="as-footnote">{t("调用内容与行程保留 30 天\uFF0C指标与测试记录保留 180 天\u3002")}</p>
        </Panel>
      </div>
    </>)}
  </>;
}
