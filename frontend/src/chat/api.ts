import { apiFetch } from '../api/auth'
import { parseSse } from './sse'
import { SequenceGap } from './reducer'
import type { TurnEvent, TurnRequest, TurnView } from './turnTypes'

export class TurnApiError extends Error {
  status: number
  code: string
  activeTurnId?: string
  retryAfter: number
  constructor(
    status: number,
    code: string,
    message: string,
    activeTurnId?: string,
    retryAfter = 0,
  ) {
    super(message)
    this.status = status
    this.code = code
    this.activeTurnId = activeTurnId
    this.retryAfter = retryAfter
  }
}
async function checked(response: Response) {
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    throw new TurnApiError(
      response.status,
      body.code ?? 'REQUEST_FAILED',
      body.message ?? `请求失败 (${response.status})`,
      body.activeTurnId,
      Number(response.headers.get('Retry-After')) || 0,
    )
  }
  return response
}
const base = '/api/chat'
export const getTurn = async (
  id: string,
  signal?: AbortSignal,
): Promise<TurnView> =>
  (await checked(await apiFetch(`${base}/turns/${id}`, { signal }))).json()
export const submitTurn = async (
  request: TurnRequest,
  signal?: AbortSignal,
): Promise<TurnView> =>
  (
    await checked(
      await apiFetch(`${base}/turns`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
        signal,
      }),
    )
  ).json()
export const cancelTurn = async (id: string): Promise<TurnView> =>
  (
    await checked(
      await apiFetch(`${base}/turns/${id}/cancel`, { method: 'POST' }),
    )
  ).json()
export const conversationTurns = async (
  id: number,
  signal?: AbortSignal,
): Promise<TurnView[]> =>
  (
    await checked(
      await apiFetch(`${base}/conversations/${id}/turns`, { signal }),
    )
  ).json()
export const currentIdentity = async (
  signal?: AbortSignal,
): Promise<{ userId: number }> =>
  (await checked(await apiFetch('/api/auth/me', { signal }))).json()
export async function readEvents(
  id: string,
  after: number,
  signal: AbortSignal,
  receive: (e: TurnEvent) => Promise<void>,
) {
  const response = await checked(
    await apiFetch(`${base}/turns/${id}/events?afterSeq=${after}`, { signal }),
  )
  if (!response.body) throw new Error('服务器未返回事件流')
  const reader = response.body.getReader()
  try {
    for await (const frame of parseSse(reader)) {
      signal.throwIfAborted()
      let event: TurnEvent
      try {
        event = JSON.parse(frame.data) as TurnEvent
      } catch {
        throw new SequenceGap('事件格式无效')
      }
      if (
        !Number.isSafeInteger(event.seq) ||
        event.seq < 1 ||
        String(event.seq) !== frame.id ||
        event.turnId !== id ||
        event.type !== frame.event
      )
        throw new SequenceGap('事件协议不一致，需要重新同步')
      await receive(event)
    }
  } finally {
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}
