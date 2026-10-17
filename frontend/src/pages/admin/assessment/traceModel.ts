import type { Span } from './model'

export interface TraceRow { span: Span; depth: number; ancestors: string[]; malformed: boolean; children: boolean }
/** A forest retains every node, including orphans and cycles, without recursive rendering. */
export function buildTrace(spans: Span[]): TraceRow[] {
  const sorted = [...spans].sort((a, b) => (Date.parse(a.started_at) || 0) - (Date.parse(b.started_at) || 0) || a.id.localeCompare(b.id))
  const ids = new Set(sorted.map(span => span.id)), children = new Map<string, Span[]>()
  sorted.forEach(span => { if (span.parent_id && span.parent_id !== span.id && ids.has(span.parent_id)) children.set(span.parent_id, [...children.get(span.parent_id) ?? [], span]) })
  const visited = new Set<string>(), result: TraceRow[] = []
  function walk(root: Span, malformed: boolean) {
    const stack = [{ span: root, ancestors: [] as string[], malformed }]
    while (stack.length) {
      const item = stack.pop()!
      if (visited.has(item.span.id)) continue
      visited.add(item.span.id)
      const nested = children.get(item.span.id) ?? []
      result.push({ ...item, depth: item.ancestors.length, children: nested.length > 0 })
      for (const child of [...nested].reverse()) stack.push({ span: child, ancestors: [...item.ancestors, item.span.id], malformed: item.malformed })
    }
  }
  sorted.filter(span => !span.parent_id || span.parent_id === span.id || !ids.has(span.parent_id)).forEach(span => walk(span, Boolean(span.parent_id)))
  sorted.filter(span => !visited.has(span.id)).forEach(span => walk(span, true))
  return result
}
