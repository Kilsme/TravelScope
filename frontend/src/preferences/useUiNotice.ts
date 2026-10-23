import { useCallback, useState } from 'react'
import { uiMessage } from './i18n'
import type { UiMessage } from './i18n'
export function useUiNotice(initial: string) {
  const [message, setMessage] = useState<UiMessage>(() => uiMessage(initial))
  const set = useCallback((text: string) => setMessage(uiMessage(text)), [])
  return [message, set] as const
}
