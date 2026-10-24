import type { ChatMessage } from '../types'

export const MAX_MESSAGE_LENGTH = 8000
export const MAX_QUOTE_LENGTH = 2000
export interface ReplyDraft {
  role: ChatMessage['role']
  original: string
  excerpt: string
  truncated: boolean
}
const segmenter = new Intl.Segmenter(undefined, { granularity:'grapheme' })

export function messageText(message: Pick<ChatMessage, 'content' | 'image' | 'role'>): string {
  return message.role === 'user' && message.image && message.content.trim() === '[图片]' ? '' : message.content
}

export function createReplyDraft(role: ReplyDraft['role'], original: string): ReplyDraft {
  let end = original.length
  if (end > MAX_QUOTE_LENGTH) {
    end = 0
    for (const part of segmenter.segment(original)) {
      const next = part.index + part.segment.length
      if (next > MAX_QUOTE_LENGTH) break
      end = next
    }
  }
  return {role, original, excerpt:original.slice(0,end), truncated:end < original.length}
}

export function composeReply(text: string, quote: ReplyDraft | null, labels: {
  assistant: string; user: string; question: string; truncated: string
}): string {
  if (!quote) return text.trim()
  const quoted = quote.excerpt.replace(/\r\n?/g,'\n').split('\n').map(line => `> ${line}`).join('\n')
  return `${quote.role === 'assistant' ? labels.assistant : labels.user}\n${quoted}${quote.truncated ? `\n> ${labels.truncated}` : ''}\n\n${labels.question}\n${text.trim()}`
}

/** Called only by an explicit Copy click. Never reads or rewrites clipboard contents. */
export async function copyMessage(text: string): Promise<void> {
  try {
    if (navigator.clipboard?.writeText) { await navigator.clipboard.writeText(text); return }
  } catch { /* Try the user-initiated compatibility path. */ }
  const focused = document.activeElement
  const inputSelection = focused instanceof HTMLTextAreaElement || focused instanceof HTMLInputElement
    ? {start:focused.selectionStart, end:focused.selectionEnd, direction:focused.selectionDirection} : null
  const selection = window.getSelection()
  const ranges = selection ? Array.from({length:selection.rangeCount},(_,i) => selection.getRangeAt(i).cloneRange()) : []
  const field = document.createElement('textarea')
  field.value = text
  field.readOnly = true
  field.tabIndex = -1
  field.setAttribute('aria-hidden','true')
  Object.assign(field.style,{position:'fixed',left:'-9999px',top:'0',opacity:'0'})
  document.body.append(field)
  try {
    field.focus({preventScroll:true}); field.select()
    if (!document.execCommand?.('copy')) throw new Error('Clipboard write failed')
  } finally {
    field.remove()
    if (focused instanceof HTMLElement && focused.isConnected) focused.focus({preventScroll:true})
    if (inputSelection && (focused instanceof HTMLTextAreaElement || focused instanceof HTMLInputElement)) {
      try { focused.setSelectionRange(inputSelection.start,inputSelection.end,inputSelection.direction ?? undefined) } catch { /* Non-text inputs have no selection API. */ }
    } else if (selection) {
      selection.removeAllRanges(); ranges.forEach(range => selection.addRange(range))
    }
  }
}
