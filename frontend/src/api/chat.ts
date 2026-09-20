import type { ChatEvent, ChatMessage, Conversation } from '../types'
import { apiFetch } from './auth'

const BASE = '/api/chat'

/**
 * 手写 SSE 解析：按空行分块，取 event: 与 data: 字段
 */
async function* parseSse(reader: ReadableStreamDefaultReader<Uint8Array>) {
  const decoder = new TextDecoder()
  let buf = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    const parts = buf.split('\n\n')
    buf = parts.pop()!
    for (const part of parts) {
      const event = /^event:\s*(.+)$/m.exec(part)?.[1]
      const data = /^data:\s?([\s\S]*)$/m.exec(part)?.[1] ?? ''
      if (event) {
        yield { event, data } as ChatEvent
      }
    }
  }
}

/**
 * 流式对话：POST /api/chat/stream 读取 SSE（认证：Authorization: Bearer）
 *
 * @param onEvent 每个事件回调；返回 false 可中止读取
 */
export async function streamChat(
  conversationId: number | null,
  message: string,
  onEvent: (event: ChatEvent) => boolean | void,
): Promise<void> {
  const resp = await apiFetch(`${BASE}/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ conversationId, message }),
  })

  if (!resp.ok || !resp.body) {
    let msg = `请求失败 (${resp.status})`
    try {
      const err = await resp.json()
      if (err?.message) msg = err.message
    } catch {
      /* 忽略解析失败 */
    }
    throw new Error(msg)
  }

  const reader = resp.body.getReader()
  try {
    for await (const event of parseSse(reader)) {
      const keepGoing = onEvent(event)
      if (keepGoing === false) {
        await reader.cancel()
        break
      }
    }
  } finally {
    reader.releaseLock()
  }
}

export async function listConversations(): Promise<Conversation[]> {
  const resp = await apiFetch(`${BASE}/conversations`)
  if (!resp.ok) {
    throw new Error('加载会话列表失败')
  }
  return resp.json()
}

export async function createConversation(): Promise<Conversation> {
  const resp = await apiFetch(`${BASE}/conversations`, { method: 'POST' })
  if (!resp.ok) {
    throw new Error('新建会话失败')
  }
  return resp.json()
}

export async function listMessages(conversationId: number): Promise<ChatMessage[]> {
  const resp = await apiFetch(`${BASE}/conversations/${conversationId}/messages`)
  if (!resp.ok) {
    throw new Error('加载历史消息失败')
  }
  return resp.json()
}
