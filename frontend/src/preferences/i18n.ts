import { getPreferences } from './store'
import { messages } from './messages'
import type { MessageKey } from './messages'
export type Params = Record<string, string | number>
export interface UiMessage { key?: MessageKey; params?: Params; fallback: string }
function interpolate(value: string, params: Params = {}) {
  return value.replace(/\{(\w+)\}/g, (token, key: string) => String(params[key] ?? token))
}
export function t(key: MessageKey, params?: Params) {
  return interpolate(getPreferences().locale === 'en' ? messages[key] : key, params)
}
const escape = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const templates = (Object.keys(messages) as MessageKey[]).filter(key => /\{\d+\}/.test(key)).map(key => {
  const indices: string[] = []
  const parts = key.split(/(\{\d+\})/).map(part => {
    const match = /^\{(\d+)\}$/.exec(part)
    if (match) { indices.push(match[1]); return '(.+?)' }
    return escape(part)
  })
  return { key, indices, pattern: new RegExp('^' + parts.join('') + '$') }
})
/** Converts known UI strings to keys. Unknown server/third-party prose remains verbatim. */
const englishKeys = new Map<string,MessageKey>(Object.entries(messages).map(([key,value])=>[value,key as MessageKey]))
export function uiMessage(text: string): UiMessage {
  if (Object.hasOwn(messages,text)) return { key:text as MessageKey, fallback:text }
  const englishKey=englishKeys.get(text)
  if (englishKey) return {key:englishKey,fallback:text}
  for (const template of templates) {
    const match=template.pattern.exec(text)
    if (match) return {key:template.key,params:Object.fromEntries(template.indices.map((index,i)=>[index,match[i+1]])),fallback:text}
  }
  return {fallback:text}
}
export const resolveMessage = (message: UiMessage) => message.key ? t(message.key,message.params) : message.fallback
/** For dynamic UI labels only. Never call on conversation or generated body content. */
export function ui<T>(value: T): T {
  if (typeof value !== 'string') return value
  const translated=resolveMessage(uiMessage(value))
  if (translated !== value || !/^[ ·]+/.test(value)) return translated as T
  return value.replace(/^([ ·]+)(.+)$/, (_,prefix,label) => prefix+resolveMessage(uiMessage(label))) as T
}
export const intlLocale = () => getPreferences().locale === 'en' ? 'en-US' : 'zh-CN'
