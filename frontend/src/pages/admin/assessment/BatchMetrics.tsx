import { MetricRows } from './common'
import { cost, duration, number, percent } from './model'
import type { BatchMetrics } from './model'

export default function BatchMetricsView({ metrics }: { metrics?: BatchMetrics }) {
  if (!metrics) return <p className="as-notice">此任务暂无批次统计，请确认后端已更新并刷新。缺失统计不会补成零。</p>
  return <section className="as-batch-metrics" aria-label="批次评估摘要"><h3>批次评估摘要</h3><p className="as-footnote">{number(metrics.samples)} 个样本 · {number(metrics.completed_plans)} 次完整规划 · {number(metrics.score_count)} 个成功评分样本。耗时取最终完整规划轮次；Token 与费用包含全部轮次和评分。</p><div className="as-grid">
    <div><h4>规划性能</h4><MetricRows items={[{ label: 'P50 规划耗时', value: duration(metrics.p50_ms) }, { label: 'P95 规划耗时', value: duration(metrics.p95_ms) }, { label: '平均耗时', value: duration(metrics.mean_ms) }, { label: '耗时标准差', value: duration(metrics.stddev_ms) }]} /></div>
    <div><h4>Token 与费用</h4><MetricRows items={[{ label: '输入 / 输出 Token', value: `${number(metrics.known_input_tokens)} / ${number(metrics.known_output_tokens)}` }, { label: '规划输入 / 输出 Token', value: `${number(metrics.planning_input_tokens)} / ${number(metrics.planning_output_tokens)}` }, { label: '评分输入 / 输出 Token', value: `${number(metrics.scoring_input_tokens)} / ${number(metrics.scoring_output_tokens)}` }, { label: '已记录费用', value: cost(metrics.known_cost) }]} /><p className="as-footnote">Token {metrics.usage_complete === true ? '记录完整' : '仅包含已记录部分'} · 费用{metrics.cost_complete === true ? '完整' : '不完整'}</p></div>
    <div><h4>运行与行程质量</h4><MetricRows items={[{ label: '正常执行率', value: percent(metrics.execution_success_rate) }, { label: '整体达标率', value: percent(metrics.overall_pass_rate) }, { label: '规则通过率', value: percent(metrics.rule_pass_rate) }, { label: '评分达标率', value: percent(metrics.score_pass_rate) }, { label: '行程平均分', value: number(metrics.mean_score), hint: '仅统计评分成功的样本；评分错误不计为零分。' }, { label: '评分失败', value: number(metrics.score_errors) }]} /></div>
  </div></section>
}
