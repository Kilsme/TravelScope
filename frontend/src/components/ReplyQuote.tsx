import { useState } from 'react'
import type { ReplyDraft } from '../chat/messageActions'
import { usePreferences } from '../preferences/PreferencesProvider'
import { t } from '../preferences/i18n'
import PixelIcon from './PixelIcon'

export default function ReplyQuote({ quote, onCancel }: { quote:ReplyDraft; onCancel:() => void }) {
  usePreferences()
  const [expanded,setExpanded] = useState(false)
  return <section className="reply-quote" aria-label={t('引用消息')}>
    <div className="reply-quote-header">
      <span><PixelIcon name="reply" />{t(quote.role === 'assistant' ? '引用 AI 的消息：' : '引用我的消息：')}</span>
      <button type="button" onClick={onCancel} aria-label={t('取消引用')} title={t('取消引用')}><PixelIcon name="close" /></button>
    </div>
    <div className={`reply-quote-text${expanded ? ' expanded' : ''}`}>{expanded ? quote.original : quote.excerpt}</div>
    {quote.truncated && <p className="reply-quote-hint">{t('已截取，发送时仅包含前 2000 字符')}</p>}
    <button className="reply-quote-expand" type="button" aria-expanded={expanded} onClick={() => setExpanded(value => !value)}>
      <PixelIcon name={expanded ? 'chevronRight' : 'chevronDown'} />{t(expanded ? '收起原文' : '展开原文')}
    </button>
  </section>
}
