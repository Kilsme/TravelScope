export interface SseFrame {
  event: string
  data: string
  id?: string
}

/** Incomplete frames are deliberately NOT emitted on EOF. */
export async function* parseSse(
  reader: ReadableStreamDefaultReader<Uint8Array>,
): AsyncGenerator<SseFrame> {
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const chunk = await reader.read()
    if (chunk.done) return
    buffer += decoder.decode(chunk.value, { stream: true })
    for (;;) {
      const boundary = /\r?\n\r?\n/.exec(buffer)
      if (!boundary) break
      const frame = buffer.slice(0, boundary.index)
      buffer = buffer.slice(boundary.index + boundary[0].length)
      let event = 'message',
        id: string | undefined
      const data: string[] = []
      for (const line of frame.split(/\r?\n/)) {
        if (line.startsWith(':')) continue
        const colon = line.indexOf(':')
        const field = colon < 0 ? line : line.slice(0, colon)
        const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
        if (field === 'event') event = value
        if (field === 'data') data.push(value)
        if (field === 'id' && !value.includes('\0')) id = value
      }
      if (data.length) yield { event, id, data: data.join('\n') }
    }
  }
}
