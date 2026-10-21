/** 会话 */
export interface Conversation {
  id: number
  title: string
  updatedAt: string
}

/** 历史消息 */
export interface ChatMessage {
  id?: number
  role: 'user' | 'assistant'
  content: string
  /** 前端临时状态：Agent 活动流（agent_status SSE 事件累积；仅本轮流式期间使用） */
  activities?: AgentActivity[]
  /** 前端临时状态：意图分类结果 */
  intent?: string
  /** 前端临时状态：质检结论载荷 JSON（review_report SSE 事件；R1 分级渲染：passed/total/segments/warnings） */
  reviewReport?: string
  /** 前端临时状态：需求清单卡片（requirement_form SSE 事件；重推即原地刷新） */
  requirementForm?: RequirementFormPayload
  /** 前端临时状态：清单已提交（折叠为只读摘要，防重复提交） */
  formSubmitted?: boolean
  streaming?: boolean
}

/** Agent 活动流条目（agent_status SSE 事件载荷） */
export interface AgentActivity {
  agent: string
  action: string
  state: 'RUNNING' | 'SUCCESS' | 'FAILED' | 'THINKING' | string
}

/** SSE 事件类型（与后端 ChatEvent 对应） */
export type ChatEventType =
  | 'intent'
  | 'delta'
  | 'tool'
  | 'agent_status'
  | 'clarify_question'
  | 'requirement_form'
  | 'review_report'
  | 'done'
  | 'error'

export interface ChatEvent {
  event: ChatEventType
  data: string
}

/** 意图分类结果事件载荷 */
export interface IntentPayload {
  intent: string
  reason: string
}

/** 需求清单单字段规格（requirement_form 载荷；与后端 RequirementFormSpec.FieldSpec 对应） */
export interface RequirementFormField {
  name: string
  label: string
  type: 'text' | 'number' | 'date' | 'select' | 'multi-select' | string
  value: string | null
  filled: boolean
  required: boolean
  options: string[]
  placeholder: string
}

/** 字段级校验错误（geocode 打回） */
export interface RequirementFieldError {
  field: string
  message: string
}

/** 需求清单卡片载荷（requirement_form SSE 事件） */
export interface RequirementFormPayload {
  fields: RequirementFormField[]
  hint: string | null
  errors: RequirementFieldError[]
}
