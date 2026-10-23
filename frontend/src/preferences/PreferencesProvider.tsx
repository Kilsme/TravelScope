import { createContext, useContext, useSyncExternalStore } from 'react'
import type { ReactNode } from 'react'
import { getPreferences, subscribePreferences } from './store'
import type { Preferences } from './store'
const Context = createContext<Preferences>(getPreferences())
export function PreferencesProvider({ children }: { children: ReactNode }) {
  const value = useSyncExternalStore(subscribePreferences, getPreferences, getPreferences)
  return <Context.Provider value={value}>{children}</Context.Provider>
}
export const usePreferences = () => useContext(Context)
