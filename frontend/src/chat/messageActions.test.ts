import { afterEach, describe, expect, it, vi } from 'vitest'
import { composeReply, copyMessage, createReplyDraft, MAX_MESSAGE_LENGTH, MAX_QUOTE_LENGTH, messageText } from './messageActions'

afterEach(() => vi.unstubAllGlobals())
const labels = {assistant:'引用 AI 的消息：',user:'引用我的消息：',question:'本次问题：',truncated:'已截取'}

describe('message quotes', () => {
  it('keeps the original Markdown and only treats an image placeholder as empty', () => {
    const original = '## 标题\n\n**加粗**\n\n```js\nconst a = 1;\n```'
    expect(messageText({role:'assistant',content:original})).toBe(original)
    expect(messageText({role:'user',content:'[图片]',image:'/photo.jpg'})).toBe('')
    expect(messageText({role:'user',content:'[图片] 这是哪里？',image:'/photo.jpg'})).toBe('[图片] 这是哪里？')
    expect(createReplyDraft('assistant',original)).toEqual({role:'assistant',original,excerpt:original,truncated:false})
  })
  it.each(['👨‍👩‍👧‍👦','🇨🇳','e\u0301','👍🏽','𠮷'])('never splits a grapheme at the quote limit: %s', glyph => {
    const original = '字'.repeat(1999) + glyph + '末尾'
    const quote = createReplyDraft('assistant',original)
    expect(quote.original).toBe(original)
    expect(quote.excerpt).toBe('字'.repeat(1999))
    expect(quote.truncated).toBe(true)
  })
  it('does not truncate a quote exactly at the limit', () => {
    const quote = createReplyDraft('user','字'.repeat(MAX_QUOTE_LENGTH))
    expect(quote.excerpt.length).toBe(2000)
    expect(quote.truncated).toBe(false)
  })
  it('quotes every line and preserves formatting without changing the question', () => {
    expect(composeReply('  下雨怎么办？  ',createReplyDraft('assistant','**西湖**\r\n\r\n- 步行'),labels))
      .toBe('引用 AI 的消息：\n> **西湖**\n> \n> - 步行\n\n本次问题：\n下雨怎么办？')
    expect(composeReply('继续',createReplyDraft('user','我的需求'),labels)).toContain('引用我的消息：\n> 我的需求')
    expect(composeReply(' 普通问题 ',null,labels)).toBe('普通问题')
  })
  it('uses the chosen language labels and marks excerpts without sending the hidden tail', () => {
    const result = composeReply('Where?',createReplyDraft('assistant','a'.repeat(2000)+'DO_NOT_SEND'),{
      assistant:'Quoted AI message:',user:'Quoted message from me:',question:'My question:',truncated:'Excerpt only',
    })
    expect(result).toContain('Quoted AI message:\n> ')
    expect(result).toContain('\n> Excerpt only\n\nMy question:\nWhere?')
    expect(result).not.toContain('DO_NOT_SEND')
  })
  it('counts quote prefixes and labels within the existing server length budget', () => {
    const quote = createReplyDraft('assistant','原文')
    const overhead = composeReply('',quote,labels).length
    expect(composeReply('字'.repeat(MAX_MESSAGE_LENGTH-overhead),quote,labels).length).toBe(8000)
    expect(composeReply('字'.repeat(MAX_MESSAGE_LENGTH-overhead+1),quote,labels).length).toBe(8001)
  })
})

describe('clipboard', () => {
  it('writes the raw message once and leaves unrelated UI out of the clipboard', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator',{clipboard:{writeText}})
    const text = '## 正文\n\n| 列 |\n| --- |\n| **值** |\n\n```js\nx()\n```'
    await copyMessage(text)
    expect(writeText).toHaveBeenCalledExactlyOnceWith(text)
  })
})
