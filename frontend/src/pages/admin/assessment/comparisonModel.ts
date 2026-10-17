import { cost, duration, number, percent } from './model'
import type { ComparisonRow } from './model'
export type Format = 'rate' | 'duration' | 'cost' | 'number'
export const formatMetric = (format: Format, value: unknown) => (format === 'rate' ? percent : format === 'duration' ? duration : format === 'cost' ? cost : number)(value)
export const comparisonMetrics: { label: string; key: keyof ComparisonRow; format: Format; partial?: 'tokens' | 'cost' }[] = [
  { label: '整体达标率', key: 'overall_pass_rate', format: 'rate' },
  { label: '正常执行率', key: 'execution_success_rate', format: 'rate' },
  { label: '规则通过率', key: 'rule_pass_rate', format: 'rate' },
  { label: '评分达标率', key: 'score_pass_rate', format: 'rate' },
  { label: '行程平均分（仅评分成功）', key: 'mean_score', format: 'number' },
  { label: '评分成功样本数', key: 'score_count', format: 'number' },
  { label: '评分失败样本数', key: 'score_errors', format: 'number' },
  { label: 'P50 规划耗时', key: 'p50_ms', format: 'duration' },
  { label: 'P95 规划耗时', key: 'p95_ms', format: 'duration' },
  { label: '完整规划平均耗时', key: 'mean_ms', format: 'duration' },
  { label: '完整规划耗时标准差', key: 'stddev_ms', format: 'duration' },
  { label: '失败执行平均耗时', key: 'failed_mean_ms', format: 'duration' },
  { label: '超时比例', key: 'timeout_rate', format: 'rate' },
  { label: '多轮系统处理总耗时', key: 'system_time_ms', format: 'duration' },
  { label: '全部输入 Token', key: 'known_input_tokens', format: 'number', partial: 'tokens' },
  { label: '全部输出 Token', key: 'known_output_tokens', format: 'number', partial: 'tokens' },
  { label: '规划输入 Token', key: 'planning_input_tokens', format: 'number', partial: 'tokens' },
  { label: '规划输出 Token', key: 'planning_output_tokens', format: 'number', partial: 'tokens' },
  { label: '评分输入 Token', key: 'scoring_input_tokens', format: 'number', partial: 'tokens' },
  { label: '评分输出 Token', key: 'scoring_output_tokens', format: 'number', partial: 'tokens' },
  { label: '已记录费用', key: 'known_cost', format: 'cost', partial: 'cost' },
]
