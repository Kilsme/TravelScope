import { useEffect, useRef, useState } from 'react'
import { copyMessage } from '../chat/messageActions'
import { usePreferences } from '../preferences/PreferencesProvider'
import { t } from '../preferences/i18n'
import PixelIcon from './PixelIcon'

export default function MessageActions({ text, disabled, replyDisabled, onReply }: {
  text:string; disabled:boolean; replyDisabled:boolean; onReply:() => void
}) {
  usePreferences()
  const [status,setStatus] = useState<'idle' | 'copying' | 'copied' | 'failed'>('idle')
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const mounted = useRef(false)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; if (timer.current) clearTimeout(timer.current) } },[])
  async function copy() {
    if (timer.current) clearTimeout(timer.current)
    setStatus('copying')
    try {
      await copyMessage(text)
      if (!mounted.current) return
      setStatus('copied')
      timer.current = setTimeout(() => setStatus('idle'),2000)
    } catch { if (mounted.current) setStatus('failed') }
  }
  return <div className="message-actions">
    <button type="button" disabled={disabled || status === 'copying'} onClick={() => { void copy() }}>
      <PixelIcon name={status === 'copied' ? 'check' : 'clipboard'} />{t(status === 'copied' ? '已复制' : '复制')}
    </button>
    <button type="button" disabled={disabled || replyDisabled} onClick={onReply}><PixelIcon name="reply" />{t('回复')}</button>
    <span className="message-action-status" data-status={status} role="status">{status === 'failed' ? t('复制失败，请手动选择正文复制。') : status === 'copied' ? t('已复制') : ''}</span>
  </div>
}
