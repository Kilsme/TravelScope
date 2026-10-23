export type Locale = 'zh-CN' | 'en'
export type Theme = 'light' | 'dark'
export interface Preferences { theme: Theme; locale: Locale }
export const PREFERENCES_KEY = 'travelscope:appearance:v1'
export function parsePreferences(raw: string | null): Preferences {
  try {
    const value = JSON.parse(raw ?? '{}')
    return { theme: value?.theme === 'dark' ? 'dark' : 'light', locale: value?.locale === 'en' ? 'en' : 'zh-CN' }
  } catch { return { theme: 'light', locale: 'zh-CN' } }
}
function read(): Preferences {
  try { return parsePreferences(localStorage.getItem(PREFERENCES_KEY)) }
  catch { return parsePreferences(null) }
}
let state = read()
const listeners = new Set<() => void>()
export const getPreferences = () => state
export function applyPreferences(value: Preferences) {
  if (typeof document === 'undefined') return
  document.documentElement.dataset.theme = value.theme
  document.documentElement.lang = value.locale
  document.title = value.locale === 'en' ? 'TravelScope Travel Assistant' : 'TravelScope 智能旅游助手'
}
export function updatePreferences(patch: Partial<Preferences>, persist = true) {
  state = parsePreferences(JSON.stringify({ ...state, ...patch }))
  applyPreferences(state)
  if (persist) try { localStorage.setItem(PREFERENCES_KEY, JSON.stringify(state)) } catch { /* Session-only preferences remain usable. */ }
  listeners.forEach(listener => listener())
}
export const subscribePreferences = (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener) } }
applyPreferences(state)
if (typeof window !== 'undefined') window.addEventListener('storage', event => {
  if (event.key === PREFERENCES_KEY || event.key === null) updatePreferences(read(), false)
})
