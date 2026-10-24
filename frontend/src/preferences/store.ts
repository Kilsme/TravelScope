export type Locale = 'zh-CN' | 'en'
export type Theme = 'light' | 'dark' | 'peach' | 'blue-bowl'
export interface Preferences { theme: Theme; locale: Locale }
export const themes: readonly Theme[] = ['light', 'dark', 'peach', 'blue-bowl']
export const PREFERENCES_KEY = 'travelscope:appearance:v2:user:'
export const preferencesKey = (userId: number) => `${PREFERENCES_KEY}${userId}`
const defaults: Preferences = { theme:'light', locale:'zh-CN' }
const memory = new Map<number | null, Preferences>()
export function parsePreferences(raw: string | null): Preferences {
  try {
    const value = JSON.parse(raw ?? '{}')
    return { theme: themes.includes(value?.theme) ? value.theme : 'light', locale: value?.locale === 'en' ? 'en' : 'zh-CN' }
  } catch { return { ...defaults } }
}
function cachedOwner(): number | null {
  try {
    const id = Number(localStorage.getItem('travelscope_user_id'))
    return localStorage.getItem('travelscope_token') && Number.isSafeInteger(id) && id > 0 ? id : null
  } catch { return null }
}
function read(id: number | null): Preferences {
  if (memory.has(id)) return memory.get(id)!
  if (id !== null) try { return parsePreferences(localStorage.getItem(preferencesKey(id))) } catch { /* memory fallback */ }
  return { ...defaults }
}
let owner = cachedOwner()
let state = read(owner)
const listeners = new Set<() => void>()
export const getPreferences = () => state
export const getPreferenceOwner = () => owner
export function applyPreferences(value: Preferences) {
  if (typeof document === 'undefined') return
  document.documentElement.dataset.theme = value.theme
  document.documentElement.lang = value.locale
  document.title = value.locale === 'en' ? 'TravelScope Travel Assistant' : 'TravelScope 智能旅游助手'
}
function publish(next: Preferences) {
  state = next
  applyPreferences(state)
  listeners.forEach(listener => listener())
}
/** Account identity, never role or page, owns a preference record. */
export function bindPreferenceOwner(userId: number | null) {
  const next = userId !== null && Number.isSafeInteger(userId) && userId > 0 ? userId : null
  if (next === owner) return
  owner = next
  publish(read(owner))
}
export function updatePreferences(patch: Partial<Preferences>, persist = true) {
  const next = parsePreferences(JSON.stringify({ ...state, ...patch }))
  if (persist) {
    memory.set(owner, next)
    if (owner !== null) try { localStorage.setItem(preferencesKey(owner), JSON.stringify(next)) } catch { /* usable in memory */ }
  }
  publish(next)
}
export const subscribePreferences = (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener) } }
export function handlePreferenceStorage(event: Pick<StorageEvent, 'key' | 'newValue'>) {
  if (event.key === null) {
    memory.clear()
    bindPreferenceOwner(cachedOwner())
    publish(read(owner))
  } else if (event.key === 'travelscope_token' || event.key === 'travelscope_user_id') {
    bindPreferenceOwner(cachedOwner())
  } else if (owner !== null && event.key === preferencesKey(owner)) {
    const next = parsePreferences(event.newValue)
    memory.set(owner, next)
    publish(next)
  }
}
applyPreferences(state)
if (typeof window !== 'undefined') window.addEventListener('storage', handlePreferenceStorage)
