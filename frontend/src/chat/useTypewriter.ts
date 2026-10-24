import { useEffect, useState, useSyncExternalStore } from 'react'
import { TypewriterBuffer } from './typewriter'

export function useTypewriter(text:string, streaming:boolean, flush:boolean) {
  const [buffer] = useState(() => new TypewriterBuffer(text,streaming))
  const view = useSyncExternalStore(buffer.subscribe,buffer.getSnapshot,buffer.getSnapshot)
  useEffect(() => { buffer.update(text,streaming,flush) }, [buffer,text,streaming,flush])
  useEffect(() => {
    const media = window.matchMedia('(prefers-reduced-motion: reduce)')
    const motion = () => buffer.setReducedMotion(media.matches)
    const visibility = () => buffer.setHidden(document.hidden)
    motion(); visibility()
    media.addEventListener('change',motion)
    document.addEventListener('visibilitychange',visibility)
    return () => { media.removeEventListener('change',motion); document.removeEventListener('visibilitychange',visibility); buffer.dispose() }
  }, [buffer])
  return view
}
