import type { ChatMessage } from '../types'

export type TurnStatus =
  | 'PREPARING'
  | 'QUEUED'
  | 'RUNNING'
  | 'CANCELLING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'INTERRUPTED'
export const activeStatuses = new Set<TurnStatus>([
  'PREPARING',
  'QUEUED',
  'RUNNING',
  'CANCELLING',
])
export interface TurnRequest {
  locale?: 'zh-CN' | 'en'
  turnId: string
  conversationId: number | null
  message: string
  imageBase64: string | null
  sourceTurnId: string | null
}
export interface Terminal {
  status: TurnStatus
  outcome: string
  content: string
  assistantMessageId: number | null
  error: { code: string; message: string } | null
}
export type Snapshot = Partial<ChatMessage> & {
  content: string
  terminal?: Terminal
}
export interface TurnView {
  locale?: 'zh-CN' | 'en'
  responseLocale?: 'zh-CN' | 'en'
  turnId: string
  conversationId: number
  status: TurnStatus
  userMessageId: number | null
  assistantMessageId: number | null
  lastSeq: number
  snapshotSeq: number
  snapshot: Snapshot
  outcome: string | null
  inputText: string
  image?: string
  createdAt: string
}
export interface TurnEvent {
  turnId: string
  seq: number
  type: string
  payload: unknown
}
export interface Outbox {
  key: string
  userId: number
  request: TurnRequest
  createdAt: string
}
export interface CachedTurn {
  key: string
  userId: number
  view: TurnView
}
