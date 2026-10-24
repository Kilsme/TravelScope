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

describe('Markdown image previews', () => {
  it('renders CDN pictures as lazy, accessible thumbnail buttons without changing URLs', () => {
    const src = 'https://store.is.autonavi.com/showpic/69cf8cf1d42c80510000003313168566?type=pic'
    const html = renderToStaticMarkup(<MarkdownView text={`![曲院风荷](${src}) ![雷峰塔](${src})`} />)
    expect(html.match(/class="gallery-thumbnail"/g)).toHaveLength(2)
    expect(html.match(/aria-haspopup="dialog"/g)).toHaveLength(2)
    expect(html).toContain('aria-label="查看图片：曲院风荷"')
    expect(html).toContain(`src="${src}"`)
    expect(html).toContain('loading="lazy"')
    expect(html).toContain('referrerPolicy="no-referrer"')
    expect(html).not.toContain('<dialog')
  })
  it('supports reference and linked images without nesting buttons inside links', () => {
    const html = renderToStaticMarkup(<MarkdownView text={'[![图片](https://example.com/image.jpg)](https://example.com/original)\n\n![引用][photo]\n\n[photo]: https://example.com/reference.jpg'} />)
    expect(html.match(/class="gallery-thumbnail"/g)).toHaveLength(2)
    expect(html).not.toContain('<a')
    expect(html).toContain('https://example.com/reference.jpg')
  })
  it('keeps ordinary links and does not turn code examples or unsafe image URLs into previews', () => {
    const html = renderToStaticMarkup(<MarkdownView text={'[官网](https://example.com)\n\n`![语法](https://example.com/image.jpg)`\n\n![非法](javascript:alert%281%29)'} />)
    expect(html).toContain('<a href="https://example.com">官网</a>')
    expect(html).not.toContain('gallery-thumbnail')
    expect(html).not.toContain('<img')
  })
})
