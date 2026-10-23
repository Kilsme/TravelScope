import 'fake-indexeddb/auto'
import { describe, expect, it, vi, afterEach } from 'vitest'
import { parseSse } from './sse'
import { applyTurnEvent, SequenceGap } from './reducer'
import {
  clearRecovery,
  listOutbox,
  listTurns,
  saveOutbox,
  saveTurn,
} from './storage'
import { mergeMessages } from './useDurableChat'
import type { TurnView } from './turnTypes'

const turn = (): TurnView => ({
  turnId: 'turn',
  conversationId: 12,
  status: 'RUNNING',
  userMessageId: 1,
  assistantMessageId: null,
  lastSeq: 0,
  snapshotSeq: 0,
  snapshot: { content: '' },
  outcome: null,
  inputText: '你好',
  createdAt: '2026-10-22T00:00:00Z',
})
async function frames(text: string, chunkSize: number) {
  const bytes = new TextEncoder().encode(text)
  const stream = new ReadableStream<Uint8Array>({
    start(c) {
      for (let i = 0; i < bytes.length; i += chunkSize)
        c.enqueue(bytes.slice(i, i + chunkSize))
      c.close()
    },
  })
  const result = []
  for await (const event of parseSse(stream.getReader())) result.push(event)
  return result
}
afterEach(() => vi.restoreAllMocks())
describe('SSE boundaries', () => {
  it.each([1, 2, 3, 7, 30])(
    'handles UTF-8 and CRLF across %i-byte chunks, comments and multiline data',
    async (size) => {
      expect(
        await frames(
          ': heartbeat\r\n\r\nid: 3\r\nevent: delta\r\ndata: 杭州🌍\r\ndata: 第二行\r\n\r\nid: 4\nevent: delta\ndata: incomplete',
          size,
        ),
      ).toEqual([{ id: '3', event: 'delta', data: '杭州🌍\n第二行' }])
    },
  )
})
describe('durable reducer', () => {
  it('does not duplicate repeated events and stops on a gap', () => {
    const first = applyTurnEvent(turn(), {
      turnId: 'turn',
      seq: 1,
      type: 'delta',
      payload: '杭州',
    })
    expect(
      applyTurnEvent(first, {
        turnId: 'turn',
        seq: 1,
        type: 'delta',
        payload: '杭州',
      }),
    ).toBe(first)
    expect(() =>
      applyTurnEvent(first, {
        turnId: 'turn',
        seq: 3,
        type: 'delta',
        payload: '遗漏',
      }),
    ).toThrow(SequenceGap)
    expect(first.snapshot.content).toBe('杭州')
  })
  it('replaces partial content on terminal and retains error outside the body', () => {
    const first = applyTurnEvent(turn(), {
      turnId: 'turn',
      seq: 1,
      type: 'delta',
      payload: '半截',
    })
    const end = applyTurnEvent(first, {
      turnId: 'turn',
      seq: 2,
      type: 'terminal',
      payload: {
        status: 'FAILED',
        outcome: 'FAILED',
        content: '权威正文',
        assistantMessageId: 2,
        error: { code: 'FAILED', message: '处理失败' },
      },
    })
    expect(end.snapshot.content).toBe('权威正文')
    expect(end.status).toBe('FAILED')
    expect(
      mergeMessages(
        [{ id: 1, turnId: 'turn', role: 'user', content: '你好' }],
        [end],
      ),
    ).toHaveLength(2)
    expect(mergeMessages([], [end])[1].notice).toBe('处理失败')
  })
  it('replaces days by version and never reopens a submitted form', () => {
    let view = turn()
    view = applyTurnEvent(view, {
      turnId: 'turn',
      seq: 1,
      type: 'itinerary_day',
      payload: { day: 1, version: 2, markdown: '新版' },
    })
    view = applyTurnEvent(view, {
      turnId: 'turn',
      seq: 2,
      type: 'itinerary_day',
      payload: { day: 1, version: 1, markdown: '旧版' },
    })
    expect(view.snapshot.itineraryDays).toEqual([
      { day: 1, version: 2, markdown: '新版' },
    ])
    view.snapshot.formSubmitted = true
    view = applyTurnEvent(view, {
      turnId: 'turn',
      seq: 3,
      type: 'requirement_form',
      payload: { fields: [], hint: null, errors: [] },
    })
    expect(view.snapshot.formSubmitted).toBe(true)
  })
})
describe('IndexedDB recovery', () => {
  it('freezes original request including null conversation and compressed image, scoped by user', async () => {
    await clearRecovery(1)
    await clearRecovery(2)
    const request = {
      turnId: 'turn',
      conversationId: null,
      message: '杭州',
      imageBase64: 'same-compressed-bytes',
      sourceTurnId: null,
    }
    await saveOutbox(1, request)
    request.conversationId = 12 as never
    request.imageBase64 = 'new-image'
    expect((await listOutbox(1))[0].request).toMatchObject({
      conversationId: null,
      imageBase64: 'same-compressed-bytes',
    })
    expect(await listOutbox(2)).toEqual([])
    const view = turn()
    view.snapshotSeq = 42
    view.lastSeq = 42
    view.snapshot.content = '覆盖到42'
    await saveTurn(1, view)
    const restored = (await listTurns(1))[0]
    expect([restored.snapshotSeq, restored.snapshot.content]).toEqual([
      42,
      '覆盖到42',
    ])
    await saveOutbox(2, { ...request, turnId: 'other' })
    await clearRecovery(1)
    expect(await listOutbox(1)).toEqual([])
    expect(await listTurns(1)).toEqual([])
    expect(await listOutbox(2)).toHaveLength(1)
  })
})
