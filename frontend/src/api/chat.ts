import type { ChatEvent, Conversation } from '../types'

const BASE = '/api/chat'

/** 解析 SSE 字节流为事件序列（event: xxx\ndata: xxx\n\n） */
async function* parseSse(reader: ReadableStreamDefaultReader<Uint8Array>): AsyncGenerator<ChatEvent> {
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    const blocks = buffer.split('\n\n')
    buffer = blocks.pop() ?? ''
    for (const block of blocks) {
      const event = parseBlock(block)
      if (event) yield event
    }
  }
}

function parseBlock(block: string): ChatEvent | null {
  let event = ''
  const dataLines: string[] = []
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5))
    }
  }
  if (!event && dataLines.length === 0) return null
  return { event: event as ChatEvent['event'], data: dataLines.join('\n') }
}

/**
 * 流式对话：POST /api/chat/stream 读取 SSE
 *
 * @param onEvent 每个事件回调；返回 false 可中止读取
 */
export async function streamChat(
  conversationId: number | null,
  message: string,
  onEvent: (event: ChatEvent) => boolean | void,
): Promise<void> {
  const resp = await fetch(`${BASE}/stream`, {
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

/** 会话列表 */
export async function listConversations(): Promise<Conversation[]> {
  const resp = await fetch(`${BASE}/conversations`)
  if (!resp.ok) throw new Error('获取会话列表失败')
  return resp.json()
}

/** 新建会话 */
export async function createConversation(): Promise<Conversation> {
  const resp = await fetch(`${BASE}/conversations`, { method: 'POST' })
  if (!resp.ok) throw new Error('新建会话失败')
  return resp.json()
}

/** 历史消息 */
export async function listMessages(conversationId: number) {
  const resp = await fetch(`${BASE}/conversations/${conversationId}/messages`)
  if (!resp.ok) throw new Error('获取历史消息失败')
  return resp.json() as Promise<{ id: number; role: 'user' | 'assistant'; content: string; createdAt: string }[]>
}
