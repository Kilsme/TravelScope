/**
 * Markdown 渲染前预处理（全站共享——聊天页与评估页等所有 markdown 出口统一口径）。
 */

/**
 * 流式脏符号清理（2026-09-23）：
 * 1. 清理不成对的 **（流式截断时常见——半截加粗渲染成字面星号）：
 *    按段落统计 ** 出现次数，奇数个则把该段落所有 ** 去掉
 * 2. 压缩 3 个以上连续空行为最多 2 个（模型爱输出大片空白）
 * 3. 行内孤立的单个 * 不处理（列表项语法依赖它）
 */
export function sanitizeMarkdown(text: string): string {
  if (!text) return text
  return text
    .split(/\n{2,}/)
    .map((para) => {
      const count = (para.match(/\*\*/g) || []).length
      if (count % 2 !== 0) {
        return para.replace(/\*\*/g, '')
      }
      return para
    })
    .join('\n\n')
    .replace(/\n{4,}/g, '\n\n\n')
}

/**
 * GFM 删除线免疫（conv-78 实证）：remark-gfm 把单个 ~ 当删除线定界符，
 * 同一文本行出现两个 ~（如「10-22 小雨 8~18℃；10-23 晴 9~16℃」）即配对——
 * 波浪号被当语法字符吞掉、中间文字加删除线，渲染成「818℃…916℃」。
 * 全站不用删除线语法，~ 统一转全角～零副作用。
 * 豁免（v5-F2 图片直显）：markdown 链接/图片语法 [..](..) 整段保护后再替换——
 * CDN 图片 URL 含 ~ 时被改写会直接裂图，替换后按占位符还原。
 */
export function defuseGfmTildes(text: string): string {
  if (!text) return text
  const spans: string[] = []
  const guarded = text.replace(/\[[^\]]*\]\([^)]*\)/g, (m) => {
    spans.push(m)
    return `\u0000${spans.length - 1}\u0000`
  })
  return guarded
    .replace(/~/g, '～')
    .replace(/\u0000(\d+)\u0000/g, (_, i) => spans[Number(i)] ?? '')
}
