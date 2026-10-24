import { useCallback, useEffect, useRef } from 'react'

export function useChatScroll(conversationId:number | null) {
  const containerRef = useRef<HTMLDivElement>(null), contentRef = useRef<HTMLDivElement>(null)
  const following = useRef(true)
  const follow = useCallback(() => {
    following.current = true
    const node = containerRef.current
    if (node) node.scrollTop = node.scrollHeight
  }, [])
  const onScroll = useCallback(() => {
    const node = containerRef.current
    if (node) following.current = node.scrollHeight - node.clientHeight - node.scrollTop <= 72
  }, [])
  useEffect(() => {
    follow()
    let frame = 0
    const observer = new ResizeObserver(() => {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(() => {
        const node = containerRef.current
        if (following.current && node) node.scrollTop = node.scrollHeight
      })
    })
    if (contentRef.current) observer.observe(contentRef.current)
    if (containerRef.current) observer.observe(containerRef.current)
    return () => { observer.disconnect(); cancelAnimationFrame(frame) }
  }, [conversationId,follow])
  return { containerRef,contentRef,onScroll,follow }
}
