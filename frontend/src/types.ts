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
  /** 前端临时状态：工具调用提示（如 get-tickets:SUCCESS） */
  toolCalls?: string[]
  /** 前端临时状态：意图分类结果 */
  intent?: string
  streaming?: boolean
}

/** SSE 事件类型（与后端 ChatEvent 对应） */
export type ChatEventType = 'intent' | 'delta' | 'tool' | 'done' | 'error'

export interface ChatEvent {
  event: ChatEventType
  data: string
}

/** 意图分类结果事件载荷 */
export interface IntentPayload {
  intent: string
  reason: string
}
