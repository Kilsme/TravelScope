import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import MarkdownView from './MarkdownView'

describe('Markdown typing cursor', () => {
  it.each([
    ['普通正文', /普通正文<span[^>]+><\/span><\/p>/],
    ['## 标题', /标题<span[^>]+><\/span><\/h2>/],
    ['末尾**加粗**', /加粗<span[^>]+><\/span><\/strong><\/p>/],
    ['- 第一项\n- 最后一项', /最后一项<span[^>]+><\/span><\/li>/],
    ['> 引用正文', /引用正文<span[^>]+><\/span><\/p>\n<\/blockquote>/],
    ['末尾[链接](https://example.com)', /链接<span[^>]+><\/span><\/a><\/p>/],
  ] as const)('keeps the cursor inside the last text container: %s', (text, expected) => {
    const html = renderToStaticMarkup(<MarkdownView text={text} cursor />)
    expect(html).toMatch(expected)
    expect(html.match(/class="cursor typewriter-cursor"/g)).toHaveLength(1)
    expect(html).toContain('aria-hidden="true"')
  })
  it('removes the cursor without changing the rendered text after completion', () => {
    const html = renderToStaticMarkup(<MarkdownView text="完整**正文**" />)
    expect(html).toBe('<p>完整<strong>正文</strong></p>')
  })
})
