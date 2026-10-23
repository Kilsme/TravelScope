import 'fake-indexeddb/auto'
import { expect, it, vi, afterEach } from 'vitest'
import { getTurn, readEvents, submitTurn, TurnApiError } from './api'
import { followTurn, sendOutbox } from './recovery'
import { listOutbox, saveOutbox } from './storage'
import type { TurnView } from './turnTypes'
import { updatePreferences } from '../preferences/store'
vi.mock('./api', async (original) => ({
  ...(await original<typeof import('./api')>()),
  getTurn: vi.fn(),
  readEvents: vi.fn(),
  submitTurn: vi.fn(),
}))
const view: TurnView = {
  turnId: 'recovery',
  conversationId: 123,
  status: 'RUNNING',
  userMessageId: 1,
  assistantMessageId: null,
  lastSeq: 0,
  snapshotSeq: 0,
  snapshot: { content: '' },
  outcome: null,
  inputText: '原请求',
  createdAt: '',
}
afterEach(() => {
  vi.useRealTimers()
  vi.resetAllMocks()
  updatePreferences({ locale: 'zh-CN' }, false)
})
it('keeps an offline request language frozen after the UI language changes', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  const entry=await saveOutbox(19,{turnId:'frozen-language',conversationId:null,message:'Original request',imageBase64:null,sourceTurnId:null,locale:'zh-CN'})
  updatePreferences({locale:'en'},false)
  vi.mocked(submitTurn).mockRejectedValueOnce(new TypeError('offline')).mockResolvedValue({...view,turnId:'frozen-language'})
  const sending=sendOutbox(entry,new AbortController().signal,()=>{})
  await vi.advanceTimersByTimeAsync(2000)
  await sending
  expect(vi.mocked(submitTurn).mock.calls.map(call=>call[0].locale)).toEqual(['zh-CN','zh-CN'])
  expect(await listOutbox(19)).toEqual([])
})
it('keeps one SSE subscription when presentation preferences change between events', async () => {
  vi.mocked(getTurn).mockResolvedValue(view)
  vi.mocked(readEvents).mockImplementation(async (_id,_after,_signal,receive)=>{
    await receive({turnId:'recovery',seq:1,type:'delta',payload:'Original '})
    updatePreferences({locale:'en',theme:'dark'},false)
    await receive({turnId:'recovery',seq:2,type:'terminal',payload:{status:'SUCCEEDED',outcome:'REPLIED',content:'Original body',assistantMessageId:2,error:null}})
  })
  const published:TurnView[]=[]
  await followTurn(view,new AbortController().signal,async next=>{published.push(next)},()=>{})
  expect(readEvents).toHaveBeenCalledTimes(1)
  expect(submitTurn).not.toHaveBeenCalled()
  expect(published.at(-1)?.snapshot.content).toBe('Original body')
})
it('retries an unconfirmed acceptance with identical payload and never rewrites conversation ID', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  const entry = await saveOutbox(9, {
    turnId: 'recovery',
    conversationId: null,
    message: '原请求',
    imageBase64: 'jpeg-bytes',
    sourceTurnId: null,
  })
  vi.mocked(submitTurn)
    .mockRejectedValueOnce(new TypeError('connection lost'))
    .mockResolvedValue(view)
  const sending = sendOutbox(entry, new AbortController().signal, () => {})
  await vi.advanceTimersByTimeAsync(2000)
  expect(await sending).toEqual(view)
  expect(vi.mocked(submitTurn).mock.calls.map((c) => c[0])).toEqual([
    entry.request,
    entry.request,
  ])
  expect(await listOutbox(9)).toEqual([])
})
it('does not retry a deterministic busy response or automatically queue a new turn', async () => {
  const entry = await saveOutbox(10, {
    turnId: 'busy',
    conversationId: 123,
    message: '输入保留',
    imageBase64: null,
    sourceTurnId: null,
  })
  vi.mocked(submitTurn).mockRejectedValue(
    new TurnApiError(409, 'CONVERSATION_BUSY', '会话忙'),
  )
  await expect(
    sendOutbox(entry, new AbortController().signal, () => {}),
  ).rejects.toMatchObject({ code: 'CONVERSATION_BUSY' })
  expect(submitTurn).toHaveBeenCalledTimes(1)
})
it('after EOF queries the same task and uses final authoritative text without resubmitting', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  vi.mocked(getTurn)
    .mockResolvedValueOnce(view)
    .mockResolvedValueOnce({
      ...view,
      status: 'SUCCEEDED',
      lastSeq: 2,
      snapshotSeq: 2,
      snapshot: { content: '完整正文' },
    })
  vi.mocked(readEvents).mockImplementation(
    async (_id, _after, _signal, receive) => {
      await receive({
        turnId: 'recovery',
        seq: 1,
        type: 'delta',
        payload: '半截',
      })
    },
  )
  const published: TurnView[] = []
  const following = followTurn(
    view,
    new AbortController().signal,
    async (next) => {
      published.push(next)
    },
    () => {},
  )
  await vi.advanceTimersByTimeAsync(2000)
  await following
  expect(published.at(-1)?.snapshot.content).toBe('完整正文')
  expect(submitTurn).not.toHaveBeenCalled()
  expect(readEvents).toHaveBeenCalledTimes(1)
})
it('expired replay cursor falls back to a snapshot', async () => {
  vi.mocked(getTurn)
    .mockResolvedValueOnce(view)
    .mockResolvedValue({
      ...view,
      status: 'SUCCEEDED',
      snapshotSeq: 8,
      lastSeq: 8,
      snapshot: { content: '快照' },
    })
  vi.mocked(readEvents).mockRejectedValue(
    new TurnApiError(410, 'REPLAY_EXPIRED', '过期'),
  )
  const publish = vi.fn().mockResolvedValue(undefined)
  await followTurn(view, new AbortController().signal, publish, () => {})
  expect(publish.mock.calls.at(-1)?.[0].snapshot.content).toBe('快照')
})
