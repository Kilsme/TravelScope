import { useEffect, useState } from 'react'
import { apiFetch } from '../../../api/auth'

export type Source = 'ONLINE' | 'ASSESSMENT'
export type ToolMode = 'FIXTURE' | 'LIVE'
export type Tab = 'overview' | 'runs' | 'batches' | 'compare'
export type NullableNumber = number | null
export interface Ingestion {
  enabled: boolean; degraded: boolean; queueDepth: number; droppedWrites: number
  persistenceFailures: number; pendingIncompleteRuns: number
}
export interface Overview extends TokenMetrics {
  sample_count: number; completed_plans: number; execution_success_rate: NullableNumber
  p50_ms: NullableNumber; p95_ms: NullableNumber; mean_ms: NullableNumber; stddev_ms: NullableNumber
  failure_rate: NullableNumber; timeout_rate: NullableNumber; failed_mean_ms: NullableNumber
  data_completeness: NullableNumber; usage_completeness: NullableNumber
  known_input_tokens: NullableNumber; known_output_tokens: NullableNumber; known_cost: NullableNumber
  known_tool_cost: NullableNumber; scoring_known_cost: NullableNumber; scoring_known_tokens: NullableNumber
  planning_known_cost: NullableNumber; tool_calls: number; cost_complete: boolean | null
  score_pass_rate: NullableNumber; rule_pass_rate: NullableNumber; overall_pass_rate: NullableNumber
  hard_constraint_coverage: NullableNumber; ingestion?: Ingestion
  assessment_sample_count?: number; scored_sample_count?: number; score_errors?: number
}
export interface Run {
  id: string; source: Source; task_type: string; status: string; started_at: string
  ended_at?: string; duration_ms: NullableNumber; tokens: NullableNumber; data_complete: boolean | null
  usage_complete?: boolean | null; session_id?: string; trace_id?: string; turn_id?: string
  sample_id?: string; artifact_available?: boolean; planning_artifact_id?: string
  span_count?: number; failed_span_count?: number; latest_score_status?: string | null; latest_score_total?: NullableNumber; latest_score_passed?: boolean | null
  tool_mode?: ToolMode | null
}
export interface Span {
  id: string; parent_id?: string; kind: string; name: string; phase: string; status: string
  started_at: string; ended_at?: string; duration_ms: NullableNumber; attempt_index?: number
  input_tokens?: NullableNumber; output_tokens?: NullableNumber; aggregate_input_tokens?: NullableNumber
  aggregate_output_tokens?: NullableNumber; aggregate_usage_complete?: boolean | null
  usage_kind?: string; cost?: NullableNumber; cost_complete?: boolean; error_type?: string
  model?: string; request_id?: string; attempt_group?: string
  aggregate_cost?: NullableNumber; aggregate_cost_complete?: boolean | null; aggregate_model_count?: number
}
export interface RuleResult { type: string; status: string; method: string; evidence: string }
export interface ScoreResult {
  score?: { routeCoherence: number; timeDensity: number; preferenceMatch: number; reason: string; evidence: string[] }
  total?: number; threshold?: number; passed?: boolean; errorType?: string; note?: string
}
export interface Evaluation { id: string; status: string; result_json: string | ScoreResult | null; created_at: string; scorer_version?: string }
export interface Payload { id: string; name: string; kind?: string; body: string; created_at: string }
export interface Scenario {
  caseId: string; category: string; userMessages: string[]
  expectations?: { rules: { type: string; desc?: string; params?: object }[]; rubric?: { minTotal: number } }
}
export interface Sample {
  id: string; case_id: string; repetition: number; status: string
  rule_json: string | RuleResult[] | null; score_status: string; score_json: string | ScoreResult | null
}
export interface Batch { id: string; status: string; tool_mode: ToolMode; repetitions: number; created_at: string; ended_at?: string; config_json?: string | object }
export interface BatchDetail { batch: Batch; samples: Sample[]; metrics?: BatchMetrics }
export interface TokenMetrics {
  known_input_tokens?: NullableNumber; known_output_tokens?: NullableNumber
  planning_input_tokens?: NullableNumber; planning_output_tokens?: NullableNumber
  scoring_input_tokens?: NullableNumber; scoring_output_tokens?: NullableNumber
  usage_complete?: boolean | null; data_complete?: boolean | null; score_count?: number; mean_score?: NullableNumber
}
export interface BatchMetrics extends TokenMetrics {
  samples: number; completed_plans: number; p50_ms: NullableNumber; p95_ms: NullableNumber
  mean_ms: NullableNumber; stddev_ms: NullableNumber; known_cost: NullableNumber; cost_complete: boolean | null
  rule_pass_rate: NullableNumber; score_pass_rate: NullableNumber; overall_pass_rate: NullableNumber; score_errors: number
  execution_success_rate: NullableNumber
}
export interface ComponentMetric {
  kind: string; component_name: string; phase: string; call_count: number; succeeded: number; failed: number; cancelled?: number
  duration_samples: number; mean_ms: NullableNumber; p50_ms: NullableNumber; p95_ms: NullableNumber; failed_mean_ms: NullableNumber
  input_tokens: NullableNumber; output_tokens: NullableNumber; usage_complete: boolean | null
  known_cost: NullableNumber; cost_complete: boolean | null; data_complete: boolean | null; batch_id?: string; case_id?: string
}
export interface ComparisonRow extends TokenMetrics {
  batch_id: string; case_id: string; samples: number; overall_pass_rate: NullableNumber
  execution_success_rate: NullableNumber; p50_ms: NullableNumber; p95_ms: NullableNumber
  known_cost: NullableNumber; cost_complete: boolean | null; completed_plans: number
  score_errors: number; timeout_rate: NullableNumber; score_pass_rate: NullableNumber; rule_pass_rate: NullableNumber
  mean_ms: NullableNumber; stddev_ms: NullableNumber; failed_mean_ms: NullableNumber; system_time_ms: NullableNumber
}
export interface Comparison { baseline: Batch; candidate: Batch; configurationDiff: boolean; scenarios: ComparisonRow[]; components?: ComponentMetric[] }

export const tabs: { id: Tab; label: string }[] = [
  { id: 'overview', label: '表现概览' }, { id: 'runs', label: '运行记录' },
  { id: 'batches', label: '测试任务' }, { id: 'compare', label: '批次对比' },
]
export const categories: Record<string, string> = {
  NORMAL_PLANNING: '常规行程', MISSING_INFORMATION: '信息补充', CLOSED_VENUE: '景点关闭', WEATHER_CONFLICT: '天气冲突',
  TOOL_FAILURE: '工具故障', MODIFIED_REQUIREMENTS: '需求变更', MULTI_CITY: '多城市出行', FAMILY: '亲子出行',
  STRICT_BUDGET: '预算限制', POI_NOT_EXIST: '地点纠错', ROUTE_TOO_DENSE: '路线过密',
}
export const taskNames: Record<string, string> = { PLANNING: '行程规划', CHAT: '日常对话', TOOL_CALL: '工具调用', RAG: '资料检索', UNKNOWN: '未分类' }
export const agentNames: Record<string, string> = { 'planning-agent': '行程规划', 'itinerary-agent': '行程编排', 'poi-research': '景点检索', 'route-optimizer': '路线优化', 'reviewer-agent': '行程审阅', 'posthoc-scorer': '行程评分', 'travel-master': '需求处理', 'intake-agent': '需求收集' }
export const kindNames: Record<string, string> = { AGENT: 'Agent', MODEL: '模型', TOOL: '工具', MODEL_CALL: '模型请求组' }
export const failedSpan = (span: Pick<Span, 'status' | 'error_type'>) => ['FAILED', 'ERROR', 'TIMED_OUT', 'INTERRUPTED'].includes(span.status) || Boolean(span.error_type)
export const phaseName = (phase: string) => phase === 'PLANNING' ? '规划' : phase === 'SCORING' ? '评分' : '其他'
export const statusNames: Record<string, string> = {
  PLANNING_COMPLETED: '行程已生成', WAITING_INPUT: '等待补充信息', REPLIED: '已回复', FAILED: '运行失败',
  TIMED_OUT: '已超时', CANCELLED: '已取消', INTERRUPTED: '已中断', INCOMPLETE: '未完整完成',
  QUEUED: '排队中', RUNNING: '运行中', CANCELLING: '取消中', COMPLETED: '已结束', SUCCEEDED: '运行完成', SUCCESS: '运行完成',
  PENDING: '等待评分', SKIPPED: '未评分', ERROR: '评分失败', PASS: '通过', FAIL: '未通过', UNKNOWN: '无法判断',
}
export const modeName = (value: string) => value === 'FIXTURE' ? '固定工具数据' : value === 'LIVE' ? '真实工具' : '未记录'
export const sourceName = (value: string) => value === 'ONLINE' ? '日常使用' : value === 'ASSESSMENT' ? '测试运行' : '未记录'
export const activeStatus = (status: string) => ['QUEUED', 'RUNNING', 'CANCELLING'].includes(status)
export const endedStatus = (status: string) => ['COMPLETED', 'CANCELLED', 'INTERRUPTED'].includes(status)
export const finite = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v)
export const number = (v: unknown) => finite(v) ? new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(v) : '未记录'
export const percent = (v: unknown) => finite(v) ? `${(v * 100).toFixed(1)}%` : '暂无数据'
export const duration = (v: unknown) => finite(v) ? `${(v / 1000).toFixed(v < 10000 ? 2 : 1)} 秒` : '未记录'
export const cost = (v: unknown) => finite(v) ? new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 6 }).format(v) : '未记录'
export const shortId = (v?: string) => v ? v.length > 12 ? `${v.slice(0, 8)}…${v.slice(-4)}` : v : '未记录'
export function date(v?: string) {
  if (!v || !Number.isFinite(Date.parse(v))) return '未记录'
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(v))
}
export const json = (value: unknown) => JSON.stringify(value, null, 2) ?? '未记录'
export function parse<T>(value: string | T | null | undefined): T | null {
  if (value == null) return null
  if (typeof value !== 'string') return value
  try { return JSON.parse(value) as T } catch { return null }
}
export function rulesState(value: Sample['rule_json']) {
  const rules = parse<RuleResult[]>(value)
  if (!Array.isArray(rules) || !rules.length) return 'UNKNOWN'
  if (rules.some(r => r.status === 'FAIL')) return 'FAIL'
  return rules.every(r => r.status === 'PASS') ? 'PASS' : 'UNKNOWN'
}
export async function request<T>(path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  let response: Response
  try {
    response = await apiFetch(`/api/admin/assessment${path}`, {
      signal, ...(body === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
    })
  } catch (e) {
    if (signal?.aborted) throw e
    throw new Error('网络连接失败，请检查服务后重试。')
  }
  if (!response.ok) {
    const detail = await response.text()
    const known = ['已有批次在执行', '已有活跃批次或重复请求', '评测开关未启用', '仅可评分已完成的日常规划'].find(message => detail.includes(message))
    const messages: Record<number, string> = {
      400: '查询条件不符合要求，请检查所选任务和运行方式。', 401: '登录已过期，请重新登录。', 403: '需要管理员权限。',
      404: '记录不存在或已过保留期。', 409: '当前任务状态不支持此操作，请刷新后重试。',
      410: '行程或需求正文未记录、已过保留期，暂时无法评分。', 429: '评分服务繁忙，请稍后重试。',
    }
    throw new Error(known === '已有批次在执行' || known === '已有活跃批次或重复请求' ? '已有测试正在运行，请等待结束后再启动。' : known === '评测开关未启用' ? '测试功能尚未启用，请检查服务配置。' : known === '仅可评分已完成的日常规划' ? '仅支持为已完成的日常规划评分。' : messages[response.status] ?? '服务暂时不可用，请稍后重试。')
  }
  const content = await response.text()
  try { return content ? JSON.parse(content) as T : undefined as T }
  catch { throw new Error('服务返回的数据暂时无法读取，请刷新后重试。') }
}

interface Resource<T> { resourceKey: string | null; data: T | null; loading: boolean; error: string; updatedAt?: string }
export function useResource<T>(path: string | null, revision = 0, pollWhen?: (data: T) => boolean) {
  const [retry, setRetry] = useState(0)
  const [state, setState] = useState<Resource<T>>({ resourceKey: null, data: null, loading: false, error: '' })
  useEffect(() => {
    if (!path) return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined
    let last: T | null = null
    async function load() {
      setState(previous => ({ resourceKey: path, data: previous.resourceKey === path ? previous.data : null, loading: true, error: '', updatedAt: previous.resourceKey === path ? previous.updatedAt : undefined }))
      try {
        const data = await request<T>(path!, undefined, controller.signal)
        if (controller.signal.aborted) return
        last = data
        setState({ resourceKey: path, data, loading: false, error: '', updatedAt: new Date().toISOString() })
      } catch (e) {
        if (controller.signal.aborted) return
        setState(previous => ({ ...previous, loading: false, error: e instanceof Error ? e.message : '加载失败，请重试。' }))
      }
      if (!controller.signal.aborted && last && pollWhen?.(last)) timer = setTimeout(() => { void load() }, 3000)
    }
    void load()
    return () => { controller.abort(); clearTimeout(timer) }
  }, [path, revision, retry, pollWhen])
  const current = state.resourceKey === path ? state : { resourceKey: path, data: null, loading: Boolean(path), error: '' }
  return { ...current, reload: () => setRetry(value => value + 1) }
}
