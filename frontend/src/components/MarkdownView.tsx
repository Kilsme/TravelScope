import { usePreferences } from '../preferences/PreferencesProvider'

import Markdown from 'react-markdown';
import type { Components } from 'react-markdown';
import type { Element, Root, RootContent } from 'hast';
import remarkGfm from 'remark-gfm';
import { defuseGfmTildes, sanitizeMarkdown } from '../markdown';
/**
 * 图片直连 CDN 定制（v5-F2）：referrerPolicy 防盗链保险（实测飞猪/高德两 CDN 无
 * Referer 校验，此为兜底）、懒加载（高德原图 100KB~1MB）、加载失败隐藏不留占位框。
 */
const components: Components = {
    img: ({ src, alt }) => (<img src={typeof src === 'string' ? src : undefined} alt={alt ?? ''} loading="lazy" referrerPolicy="no-referrer" onError={(e) => {
            e.currentTarget.style.display = 'none';
        }}/>),
};

/** Insert into the last rendered text container, rather than after its block-level wrapper. */
function rehypeTypewriterCursor() {
    return (tree: Root) => {
        let parent: Root | Element = tree;
        while (true) {
            const last: RootContent | undefined = parent.children.findLast(child => child.type !== 'text' || child.value.trim() !== '');
            if (last?.type !== 'element' || ['img', 'br', 'hr', 'input'].includes(last.tagName)) break;
            parent = last;
        }
        parent.children.push({type:'element', tagName:'span', properties:{className:['cursor','typewriter-cursor'], ariaHidden:'true'}, children:[]});
    };
}
/**
 * 全站共享 Markdown 渲染出口（聊天页气泡 / 评估页行程）：
 * GFM + 流式脏符号清理 + 波浪号删除线免疫 + 图片直连定制。
 * 新增 markdown 出口必须复用本组件，勿再各自拼 react-markdown。
 * plain=true 跳过流式清理（评估页终稿等完整文档场景），仍保留波浪号免疫与图片定制。
 */
export default function MarkdownView({ text, plain = false, cursor = false }: {
    text: string;
    plain?: boolean;
    cursor?: boolean;
}) {
    usePreferences();
    const prepared = plain ? defuseGfmTildes(text) : defuseGfmTildes(sanitizeMarkdown(text));
    return (<Markdown remarkPlugins={[remarkGfm]} rehypePlugins={cursor ? [rehypeTypewriterCursor] : []} components={components}>
      {prepared}
    </Markdown>);
}
