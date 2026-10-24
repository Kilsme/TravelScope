import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { advanceReveal, createRevealPlan, TypewriterBuffer } from './typewriter'

beforeEach(() => {
  vi.useFakeTimers({toFake:['setTimeout','clearTimeout','performance']})
  vi.stubGlobal('requestAnimationFrame',(callback:FrameRequestCallback) => setTimeout(() => callback(performance.now()),16))
  vi.stubGlobal('cancelAnimationFrame',(id:number) => clearTimeout(id))
})
afterEach(() => { vi.clearAllTimers(); vi.useRealTimers(); vi.unstubAllGlobals() })
const advance = (ms:number) => vi.advanceTimersByTime(ms)

describe('typewriter display buffer', () => {
  it('reveals a new live reply gradually while keeping the incoming source complete', () => {
    const buffer = new TypewriterBuffer('',true)
    const source = '这是逐字输出的旅行建议。'.repeat(5)
    buffer.update(source,true)
    expect(buffer.getSnapshot()).toEqual({text:'',typing:true})
    advance(96)
    expect(buffer.getSnapshot().text.length).toBeGreaterThan(0)
    expect(buffer.getSnapshot().text.length).toBeLessThan(source.length)
    advance(10000)
    expect(buffer.getSnapshot()).toEqual({text:source,typing:false})
  })
  it('immediately seeds history and restored live snapshots without replaying old content', () => {
    const history = new TypewriterBuffer('历史正文',false)
    history.update('历史正文补全',false)
    expect(history.getSnapshot().text).toBe('历史正文补全')
    const recovered = new TypewriterBuffer('已经收到的正文',true)
    recovered.update('已经收到的正文，新增内容继续输出。',true)
    expect(recovered.getSnapshot().text).toBe('已经收到的正文')
    advance(2000)
    expect(recovered.getSnapshot().text).toBe('已经收到的正文，新增内容继续输出。')
  })
  it('keeps a successful terminal suffix paced while a rewritten final body replaces immediately', () => {
    const buffer = new TypewriterBuffer('',true)
    buffer.update('正文前半段',true)
    advance(48)
    buffer.update('正文前半段以及完整的最终结论。'.repeat(100),false)
    expect(buffer.getSnapshot().typing).toBe(true)
    advance(700)
    expect(buffer.getSnapshot().typing).toBe(true)
    expect(buffer.getSnapshot().text.length).toBeLessThanOrEqual(12)
    buffer.update('服务器改写后的权威终稿',false)
    expect(buffer.getSnapshot()).toEqual({text:'服务器改写后的权威终稿',typing:false})
  })
  it('reveals only one grapheme per update even with a large backlog and delayed frames', () => {
    let frame:FrameRequestCallback = () => {}
    vi.stubGlobal('requestAnimationFrame',(callback:FrameRequestCallback) => { frame = callback; return 1 })
    const buffer = new TypewriterBuffer('',true)
    const pieces:string[] = []
    buffer.subscribe(() => pieces.push(buffer.getSnapshot().text))
    buffer.update('你👨‍👩‍👧‍👦好'.repeat(500),true)
    frame(1000)
    expect(buffer.getSnapshot().text).toBe('你')
    frame(2000)
    expect(buffer.getSnapshot().text).toBe('你👨‍👩‍👧‍👦')
    buffer.update('你👨‍👩‍👧‍👦好'.repeat(500),false)
    frame(3000)
    expect(buffer.getSnapshot().text).toBe('你👨‍👩‍👧‍👦好')
    expect(pieces).toEqual(['','你','你👨‍👩‍👧‍👦','你👨‍👩‍👧‍👦好'])
  })
  it('pauses briefly at punctuation without revealing the following character early', () => {
    const buffer = new TypewriterBuffer('',true)
    buffer.update('你，好。再见',true)
    advance(128)
    expect(buffer.getSnapshot().text).toBe('你，')
    advance(112)
    expect(buffer.getSnapshot().text).toBe('你，')
    advance(80)
    expect(buffer.getSnapshot().text).toBe('你，好。')
    advance(208)
    expect(buffer.getSnapshot().text).toBe('你，好。')
    advance(16)
    expect(buffer.getSnapshot().text).toBe('你，好。再')
  })
  it('flushes immediately on explicit stop, failure, reduced motion and backgrounding', () => {
    for (const action of ['stop','motion','hidden'] as const) {
      const buffer = new TypewriterBuffer('',true)
      buffer.update('完整接收但尚未显示的正文。'.repeat(10),true)
      if (action === 'stop') buffer.update('完整接收但尚未显示的正文。'.repeat(10),false,true)
      if (action === 'motion') buffer.setReducedMotion(true)
      if (action === 'hidden') buffer.setHidden(true)
      expect(buffer.getSnapshot().typing).toBe(false)
      expect(vi.getTimerCount()).toBe(0)
      if (action === 'stop') buffer.update('取消后的权威正文',false,true)
      else buffer.update('新的权威正文',true)
      expect(buffer.getSnapshot().typing).toBe(false)
    }
  })
  it('keeps animation alive across appends without duplicating source and cleans up on unmount', () => {
    const buffer = new TypewriterBuffer('',true)
    buffer.update('你',true);advance(32)
    buffer.update('你好，世界',true);advance(32)
    buffer.update('你好，世界！',false);advance(700)
    expect(buffer.getSnapshot().text).toBe('你好，世界！')
    buffer.update('你好，世界！'.repeat(100),true)
    buffer.dispose()
    expect(vi.getTimerCount()).toBe(0)
  })
})

describe('Unicode and atomic Markdown', () => {
  it('never slices a surrogate pair, family emoji, flag or combining character', () => {
    const pieces = ['你','👨‍👩‍👧‍👦','🇨🇳','e\u0301','👍🏽','好']
    const source = pieces.join(''), plan = createRevealPlan(source,true)
    let offset = 0
    for (const piece of pieces) {
      const next = advanceReveal(plan,offset,1)
      expect(source.slice(offset,next)).toBe(piece)
      offset = next
    }
  })
  it.each(['```ts\nconst x = 1\n','~~~js\nconst x = 1\n','    indented code'])('holds incomplete code: %s', source => {
    const plan = createRevealPlan(source,false)
    expect(advanceReveal(plan,0,1000)).toBe(0)
    expect(advanceReveal(createRevealPlan(source,true),0,1)).toBe(source.length)
  })
  it('reveals a closed code fence atomically before the turn finishes', () => {
    const code = '```ts\nconst x = 1\n```'
    expect(advanceReveal(createRevealPlan(code,false),0,1)).toBe(code.length)
  })
  it('holds table headers and rows until the table closes or the server completes', () => {
    const table = '| 天数 | 景点 |\n| --- | --- |\n| 一 | 西湖 |'
    expect(advanceReveal(createRevealPlan('| 天数 | 景点 |',false),0,100)).toBe(0)
    expect(advanceReveal(createRevealPlan(table,false),0,1000)).toBe(0)
    expect(advanceReveal(createRevealPlan(table + '\n\n接下来的建议',false),0,1)).toBe(table.length)
    expect(advanceReveal(createRevealPlan(table,true),0,1)).toBe(table.length)
  })
  it('never prints an incomplete image URL and shows complete images atomically', () => {
    const incomplete = '正文\n\n![西湖](https://example.com/image'
    expect(advanceReveal(createRevealPlan(incomplete,false),0,1000)).toBe(4)
    const complete = '![西湖](https://example.com/image.jpg)'
    expect(advanceReveal(createRevealPlan(complete,false),0,1)).toBe(complete.length)
    const inline = '`![not an image`'
    expect(advanceReveal(createRevealPlan(inline,false),0,1)).toBe(inline.length)
  })
  it('sleeps while waiting for a structure and resumes when its closing source arrives', () => {
    const buffer = new TypewriterBuffer('',true)
    buffer.update('![西湖](',true);advance(200)
    expect(buffer.getSnapshot().text).toBe('')
    expect(vi.getTimerCount()).toBe(0)
    buffer.update('![西湖](https://example.com/lake.jpg)',true);advance(64)
    expect(buffer.getSnapshot().text).toContain('lake.jpg)')
  })
})
