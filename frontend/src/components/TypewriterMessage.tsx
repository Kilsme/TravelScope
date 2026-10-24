import type { ReactNode } from 'react'
import { useTypewriter } from '../chat/useTypewriter'
import MarkdownView from './MarkdownView'

export default function TypewriterMessage({ text, streaming = false, flush = false, children }: {
  text:string; streaming?:boolean; flush?:boolean; children?:ReactNode
}) {
  const { text:visible, typing } = useTypewriter(text,streaming,flush)
  const writing = (streaming && !flush) || typing
  return <div className="typewriter-message" data-typing={typing} aria-busy={writing}>
    {visible ? <div className="bubble-content markdown-body"><MarkdownView text={visible} cursor={writing} /></div> : <>{children}{writing && <span className="cursor typewriter-cursor" aria-hidden="true" />}</>}
  </div>
}
