import { apiFetch, getToken } from '../api/auth'
import { createContext, useContext, useSyncExternalStore, useEffect } from 'react'
import type { ReactNode } from 'react'
import { bindPreferenceOwner, getPreferences, subscribePreferences } from './store'
import type { Preferences } from './store'
const Context = createContext<Preferences>(getPreferences())
export function PreferencesProvider({ children, verifyIdentity = true }: { children: ReactNode; verifyIdentity?: boolean }) {
  useEffect(() => {
    if (!verifyIdentity) return
    const token = getToken()
    if (!token) { bindPreferenceOwner(null); return }
    const abort = new AbortController()
    void apiFetch('/api/auth/me', { signal:abort.signal }).then(async response => {
      if (response.ok) {
        const identity = await response.json() as { userId:number }
        if (!abort.signal.aborted && getToken() === token) {
          try { localStorage.setItem('travelscope_user_id', String(identity.userId)) } catch { /* preferences also work in memory */ }
          bindPreferenceOwner(identity.userId)
        }
      }
    }).catch(() => { /* A transient outage must not erase a remembered account preference. */ })
    return () => abort.abort()
  }, [verifyIdentity])
  const value = useSyncExternalStore(subscribePreferences, getPreferences, getPreferences)
  return <Context.Provider value={value}>{children}</Context.Provider>
}
export const usePreferences = () => useContext(Context)
