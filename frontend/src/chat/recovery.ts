import { getTurn, readEvents, submitTurn, TurnApiError } from './api'
import { applyTurnEvent, SequenceGap } from './reducer'
import { deleteOutbox } from './storage'
import { activeStatuses, type Outbox, type TurnView } from './turnTypes'

export function wait(ms: number, signal: AbortSignal): Promise<void> {
  signal.throwIfAborted()
  return new Promise((resolve, reject) => {
    const finish = () => {
      signal.removeEventListener('abort', abort)
      resolve()
    }
    const timer = setTimeout(finish, ms)
    const abort = () => {
      clearTimeout(timer)
      signal.removeEventListener('abort', abort)
      reject(signal.reason ?? new DOMException('Aborted', 'AbortError'))
    }
    signal.addEventListener('abort', abort, { once: true })
  })
}
async function reconnectWait(
  attempt: number,
  signal: AbortSignal,
  minimum = 0,
) {
  const ms = Math.max(
    minimum,
    Math.min(15_000, 1000 * 2 ** Math.min(attempt, 4)) *
      (0.8 + Math.random() * 0.4),
  )
  if (
    typeof window !== 'undefined' &&
    typeof navigator !== 'undefined' &&
    navigator.onLine === false
  ) {
    while (!navigator.onLine) {
      signal.throwIfAborted()
      await new Promise<void>((resolve, reject) => {
        const cleanup = () => {
          clearTimeout(timer)
          window.removeEventListener('online', online)
          signal.removeEventListener('abort', abort)
        }
        const online = () => {
          cleanup()
          resolve()
        }
        const abort = () => {
          cleanup()
          reject(signal.reason)
        }
        const timer = setTimeout(online, 30_000)
        window.addEventListener('online', online, { once: true })
        signal.addEventListener('abort', abort, { once: true })
      })
    }
  } else await wait(ms, signal)
}
function retryable(error: unknown) {
  return (
    !(error instanceof TurnApiError) ||
    error.status >= 500 ||
    error.status === 429
  )
}
export async function recoverRequest<T>(
  request: () => Promise<T>,
  signal: AbortSignal,
  notice: (text: string) => void,
): Promise<T> {
  let attempt = 0
  for (;;) {
    signal.throwIfAborted()
    try {
      return await request()
    } catch (e) {
      signal.throwIfAborted()
      if (!retryable(e)) throw e
      notice('网络不可用，正在恢复账户与会话…')
      await reconnectWait(
        attempt++,
        signal,
        e instanceof TurnApiError ? e.retryAfter * 1000 : 0,
      )
    }
  }
}
export async function sendOutbox(
  entry: Outbox,
  signal: AbortSignal,
  notice: (text: string) => void,
): Promise<TurnView> {
  let attempt = 0
  for (;;) {
    signal.throwIfAborted()
    try {
      const turn = await submitTurn(entry.request, signal)
      if (turn.status === 'PREPARING') {
        notice('消息准备中，正在确认…')
        await reconnectWait(attempt++, signal)
        continue
      }
      await deleteOutbox(entry).catch(() =>
        notice('消息已受理，本地待发送记录将稍后清理。'),
      )
      return turn
    } catch (e) {
      signal.throwIfAborted()
      if (!retryable(e)) throw e
      notice('网络中断，正在确认原消息是否已接收…')
      await reconnectWait(
        attempt++,
        signal,
        e instanceof TurnApiError ? e.retryAfter * 1000 : 0,
      )
    }
  }
}
export async function followTurn(
  initial: TurnView,
  signal: AbortSignal,
  publish: (view: TurnView) => Promise<void>,
  notice: (text: string) => void,
) {
  let view = initial,
    attempt = 0
  for (;;) {
    signal.throwIfAborted()
    try {
      const remote = await getTurn(view.turnId, signal)
      if (!activeStatuses.has(remote.status)) {
        await publish(remote)
        notice('')
        return
      }
      if (view.snapshotSeq > remote.lastSeq) view = remote
      else view = { ...view, status: remote.status, lastSeq: remote.lastSeq }
      await publish(view)
      notice('')
      await readEvents(view.turnId, view.snapshotSeq, signal, async (event) => {
        view = applyTurnEvent(view, event)
        await publish(view)
        attempt = 0
      })
      if (!activeStatuses.has(view.status)) return
      throw new Error('连接在任务结束前关闭')
    } catch (e) {
      signal.throwIfAborted()
      if (
        e instanceof SequenceGap ||
        (e instanceof TurnApiError &&
          (e.status === 410 || e.code === 'CURSOR_INVALID'))
      ) {
        view = await getTurn(view.turnId, signal)
        await publish(view)
        continue
      }
      if (!retryable(e)) throw e
      notice('连接中断，正在恢复原任务…')
      await reconnectWait(
        attempt++,
        signal,
        e instanceof TurnApiError ? e.retryAfter * 1000 : 0,
      )
    }
  }
}
