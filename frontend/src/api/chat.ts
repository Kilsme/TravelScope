import type { ChatEvent, ChatMessage, Conversation } from '../types'
import { apiFetch } from './auth'

const BASE = '/api/chat'

import { parseSse } from '../chat/sse'

/**
 * 流式对话：POST /api/chat/stream 读取 SSE（认证：Authorization Bearer）
 *
 * @param onEvent 每个事件回调；返回 false 可中止读取
 * @param imageBase64 私人讲解员图片（裸 base64，canvas 压缩后 JPEG；文本与图片至少其一）
 */
export async function streamChat(
  conversationId: number | null,
  message: string,
  onEvent: (event: ChatEvent) => boolean | void,
  imageBase64?: string,
): Promise<void> {
  const resp = await apiFetch(`${BASE}/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ conversationId, message, imageBase64: imageBase64 ?? null }),
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
      const keepGoing = onEvent(event as ChatEvent)
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
