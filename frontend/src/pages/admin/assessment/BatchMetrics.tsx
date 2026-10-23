import { usePreferences } from '../../../preferences/PreferencesProvider'
import { t, ui } from '../../../preferences/i18n'
import { MetricRows } from './common';
import { cost, duration, number, percent } from './model';
import type { BatchMetrics } from './model';
export default function BatchMetricsView({ metrics }: {
    metrics?: BatchMetrics;
}) {
    usePreferences();
    if (!metrics)
        return <p className="as-notice">{t("此任务暂无批次统计\uFF0C请确认后端已更新并刷新\u3002缺失统计不会补成零\u3002")}</p>;
    return <section className="as-batch-metrics" aria-label={t("批次评估摘要")}><h3>{t("批次评估摘要")}</h3><p className="as-footnote">{ui(number(metrics.samples))}{t("个样本 \u00B7")}{ui(number(metrics.completed_plans))}{t("次完整规划 \u00B7")}{ui(number(metrics.score_count))}{t("个成功评分样本\u3002耗时取最终完整规划轮次\uFF1BToken 与费用包含全部轮次和评分\u3002")}</p><div className="as-grid">
    <div><h4>{t("规划性能")}</h4><MetricRows items={[{ label: t("P50 规划耗时"), value: duration(metrics.p50_ms) }, { label: t("P95 规划耗时"), value: duration(metrics.p95_ms) }, { label: t("平均耗时"), value: duration(metrics.mean_ms) }, { label: t("耗时标准差"), value: duration(metrics.stddev_ms) }]}/></div>
    <div><h4>{t("Token 与费用")}</h4><MetricRows items={[{ label: t("输入 / 输出 Token"), value: `${number(metrics.known_input_tokens)} / ${number(metrics.known_output_tokens)}` }, { label: t("规划输入 / 输出 Token"), value: `${number(metrics.planning_input_tokens)} / ${number(metrics.planning_output_tokens)}` }, { label: t("评分输入 / 输出 Token"), value: `${number(metrics.scoring_input_tokens)} / ${number(metrics.scoring_output_tokens)}` }, { label: t("已记录费用"), value: cost(metrics.known_cost) }]}/><p className="as-footnote">Token {ui(metrics.usage_complete === true ? t("记录完整") : t("仅包含已记录部分"))}{t("\u00B7 费用")}{ui(metrics.cost_complete === true ? t("完整") : t("不完整"))}</p></div>
    <div><h4>{t("运行与行程质量")}</h4><MetricRows items={[{ label: t("正常执行率"), value: percent(metrics.execution_success_rate) }, { label: t("整体达标率"), value: percent(metrics.overall_pass_rate) }, { label: t("规则通过率"), value: percent(metrics.rule_pass_rate) }, { label: t("评分达标率"), value: percent(metrics.score_pass_rate) }, { label: t("行程平均分"), value: number(metrics.mean_score), hint: t("仅统计评分成功的样本\uFF1B评分错误不计为零分\u3002") }, { label: t("评分失败"), value: number(metrics.score_errors) }]}/></div>
  </div></section>;
}
