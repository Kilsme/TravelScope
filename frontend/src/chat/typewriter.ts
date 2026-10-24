import { unified } from 'unified'
import remarkParse from 'remark-parse'
import remarkGfm from 'remark-gfm'

const parser = unified().use(remarkParse).use(remarkGfm)
const segmenter = new Intl.Segmenter(undefined, { granularity:'grapheme' })
interface Range { start:number; end:number; complete:boolean }
interface Node {
  type:string; children?:Node[]
  position?:{ start:{ offset?:number }; end:{ offset?:number } }
}
export interface RevealPlan { ends:number[]; ranges:Range[]; length:number }

/** Markdown is parsed with the same GFM grammar as MarkdownView. Only presentation is buffered. */
export function createRevealPlan(source:string, complete:boolean): RevealPlan {
  const tree = parser.parse(source)
  const ranges:Range[] = []
  function visit(node:Node) {
    const start = node.position?.start.offset, end = node.position?.end.offset
    if (start !== undefined && end !== undefined) {
      const raw = source.slice(start,end)
      if (node.type === 'code') {
        const fence = /^ {0,3}(`{3,}|~{3,})/.exec(raw)?.[1]
        const closed = fence && new RegExp('(?:^|\\n) {0,3}' + fence[0] + '{' + fence.length + ',}[ \\t]*\\r?$').test(raw.slice(fence.length))
        ranges.push({start,end,complete:complete || Boolean(closed) || /^(?:[ \t]*\r?\n){2}/.test(source.slice(end)) || tree.children.some(n => (n.position?.start.offset ?? 0) > end)})
      } else if (node.type === 'table') {
        ranges.push({start,end,complete:complete || /^(?:[ \t]*\r?\n){2}/.test(source.slice(end)) || tree.children.some(n => (n.position?.start.offset ?? 0) > end)})
      } else if (node.type === 'image' || node.type === 'imageReference' || node.type === 'inlineCode') {
        ranges.push({start,end,complete:true})
      }
    }
    node.children?.forEach(visit)
  }
  visit(tree)
  if (!complete) {
    // An unfinished image is still a text AST node. Do not expose its alt text / URL syntax.
    for (const match of source.matchAll(/!\[/g)) {
      const start = match.index
      if (source[start - 1] !== '\\' && !ranges.some(r => r.start <= start && r.end > start)) {
        ranges.push({start,end:source.length,complete:false})
        break
      }
    }
    // Hold potential table headers / partial fence openers at the end of the incoming stream.
    const tail = tree.children.at(-1)
    if (tail?.type === 'paragraph') {
      const start = tail.position?.start.offset ?? 0
      const raw = source.slice(start)
      if (!/\n[ \t]*\n$/.test(raw)) {
        const candidate = /(?:^|\n)([^\n]*(?<!\\)\|[^\n]*(?:\n[ \t:|-]*)?|[ \t]*[`~]{1,2})\r?\n?$/.exec(raw)
        if (candidate) ranges.push({start:start + candidate.index + (candidate[0].startsWith('\n') ? 1 : 0),end:source.length,complete:false})
      }
    }
  }
  ranges.sort((a,b) => a.start - b.start || b.end - a.end)
  return {ends:[...segmenter.segment(source)].map(part => part.index + part.segment.length), ranges, length:source.length}
}

/** Returns a UTF-16 offset on a complete grapheme boundary, jumping complete structured blocks. */
export function advanceReveal(plan:RevealPlan, offset:number, count:number): number {
  let lo = 0, hi = plan.ends.length
  while (lo < hi) { const mid = (lo + hi) >>> 1; if (plan.ends[mid] <= offset) lo = mid + 1; else hi = mid }
  let next = plan.ends[Math.min(plan.ends.length - 1,lo + Math.max(1,count) - 1)] ?? offset
  for (const range of plan.ranges) {
    if (offset < range.end && next > range.start) {
      if (!range.complete) return Math.max(offset,Math.min(next,range.start))
      next = Math.max(next,range.end)
    }
  }
  return next
}

export interface TypewriterSnapshot { text:string; typing:boolean }
/** A component-local external store: animation never mutates the durable snapshot or its cursor. */
export class TypewriterBuffer {
  private source:string
  private offset:number
  private wasLive:boolean
  private streaming:boolean
  private reduced = false
  private hidden = false
  private plan:RevealPlan = {ends:[],ranges:[],length:0}
  private frame:number | null = null
  private time = 0
  private credit = 0
  private rate = 52
  private value:TypewriterSnapshot
  private listeners = new Set<() => void>()
  constructor(text:string, streaming:boolean) {
    this.source = text; this.offset = text.length; this.wasLive = streaming; this.streaming = streaming
    this.value = {text,typing:false}
  }
  getSnapshot = () => this.value
  subscribe = (listener:() => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private publish() {
    const next = {text:this.source.slice(0,this.offset),typing:this.offset < this.source.length}
    if (next.text === this.value.text && next.typing === this.value.typing) return
    this.value = next; this.listeners.forEach(listener => listener())
  }
  dispose = () => { if (this.frame !== null) cancelAnimationFrame(this.frame); this.frame = null; this.credit = 0 }
  private flush() { this.dispose(); this.offset = this.source.length; this.rate = 52; this.publish() }
  setReducedMotion(value:boolean) { this.reduced = value; if (value) this.flush() }
  setHidden(value:boolean) { this.hidden = value; if (value) this.flush() }
  update(text:string, streaming:boolean, flush = false) {
    const historical = !this.wasLive && !streaming
    const append = text.startsWith(this.source)
    const changed = this.source !== text || this.streaming !== streaming
    this.wasLive ||= streaming; this.streaming = streaming; this.source = text
    if (historical || !append || flush || this.reduced || this.hidden) { this.flush(); return }
    if (changed) this.plan = createRevealPlan(text,!streaming)
    if (this.offset >= text.length) { this.rate = 52; this.publish(); return }
    const pending = text.length - this.offset
    this.rate = Math.max(this.rate,pending / (streaming ? .9 : .6))
    this.publish()
    if (this.frame === null) { this.time = performance.now(); this.frame = requestAnimationFrame(this.tick) }
  }
  private tick = (now:number) => {
    this.frame = null
    const elapsed = now - this.time
    if (elapsed < 32) { this.frame = requestAnimationFrame(this.tick); return }
    this.time = now
    this.credit += this.rate * Math.min(elapsed,100) / 1000
    const budget = Math.floor(this.credit)
    if (budget < 1) { this.frame = requestAnimationFrame(this.tick); return }
    this.credit -= budget
    const next = advanceReveal(this.plan,this.offset,budget)
    if (next === this.offset) { this.credit = 0; return } // Incomplete structure: wait for more source.
    this.offset = next; this.publish()
    if (next < this.source.length) this.frame = requestAnimationFrame(this.tick)
    else { this.rate = 52; this.credit = 0 }
  }
}
