import { usePreferences } from '../preferences/PreferencesProvider'
import { t, ui } from '../preferences/i18n'
import SidebarTools from '../components/SidebarTools';
import { useCallback, useEffect, useRef, useState } from 'react';
import '../App.css';
import { useDurableChat } from '../chat/useDurableChat';
import MarkdownView from '../components/MarkdownView';
import RequirementFormCard from '../components/RequirementFormCard';
import ReviewReportCard from '../components/ReviewReportCard';
import type { AgentActivity } from '../types';
import type { TurnRequest } from '../chat/turnTypes';
/** Markdown 渲染（全站共享出口 MarkdownView：GFM+流式清理+波浪号免疫+图片直连定制，见 components/MarkdownView.tsx） */
function Md({ text }: {
    text: string;
}) {
    usePreferences();
    return <MarkdownView text={text}/>;
}
const INTENT_LABELS: Record<string, string> = {
    CHAT: '💬 闲聊咨询',
    TOOL_CALL: '🔧 单点查询',
    PLANNING: '🗺️ 行程规划',
    RAG: '📚 知识库问答',
    UNKNOWN: '❓ 未识别',
};
/** 活动流状态 → 圆点样式（RUNNING 黄 / SUCCESS 绿 / FAILED 红 / THINKING 蓝） */
const ACTIVITY_DOT: Record<string, string> = {
    RUNNING: 'dot-running',
    SUCCESS: 'dot-success',
    FAILED: 'dot-failed',
    THINKING: 'dot-thinking',
};
/** 活动流子代理名 → 中文可读名 */
const AGENT_LABELS: Record<string, string> = {
    'travel-master': '主规划',
    'intake-agent': '需求收集',
    'planning-agent': '行程规划',
    'poi-research': '景点调研',
    'route-optimizer': '路线优化',
    'reviewer-agent': '质量评审',
};
/**
 * Agent 活动流小字区（2026-09-22 过程/结果分离改造）：
 * 气泡下方灰色小字流式滚动（最近 4 条，更旧的淡出），点击标题栏折叠。
 * 过程叙述（委派/工具调用/思考摘要）在这里展示，正文只留最终结果。
 */
function ActivityStream({ activities }: {
    activities: AgentActivity[];
}) {
    usePreferences();
    const [collapsed, setCollapsed] = useState(false);
    if (!activities || activities.length === 0)
        return null;
    // RUNNING 条目若已有同名 SUCCESS/FAILED 后续条目，则隐藏中间 RUNNING（减噪）
    const resolved = new Set(activities
        .filter((a) => a.state === 'SUCCESS' || a.state === 'FAILED')
        .map((a) => a.action));
    const visible = activities.filter((a) => !(a.state === 'RUNNING' && resolved.has(a.action)));
    return (<div className={`activity-stream ${collapsed ? 'collapsed' : ''}`}>
      <button className="activity-toggle" onClick={() => setCollapsed((c) => !c)}>{t("\u2699\uFE0F Agent 活动\uFF08")}{ui(visible.length)}）{ui(collapsed ? ' ▸' : ' ▾')}
      </button>
      {ui(!collapsed && (<div className="activity-items">
          {ui(visible.slice(-4).map((a, i, arr) => (<div key={i} className={`activity-item ${i < arr.length - 1 ? 'faded' : ''}`}>
              <span className={`activity-dot ${ACTIVITY_DOT[a.state] ?? 'dot-thinking'}`}/>
              <span className="activity-agent">
                {ui(AGENT_LABELS[a.agent] ?? a.agent)}
              </span>
              <span className="activity-action">{ui(a.action)}</span>
            </div>)))}
        </div>))}
    </div>);
}
/**
 * 图片压缩（私人讲解员，2026-10-22）：canvas 重绘最长边 1600px、JPEG q0.8；
 * base64 超 1.2MB 自动降质（网关请求体 2MiB 硬上限，留 prompt/历史余量）。
 * 返回裸 base64（不带 data: 前缀，后端 Base64Source 约定）。
 */
async function compressImage(file: File): Promise<string> {
    const dataUrl = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(reader.result as string);
        reader.onerror = () => reject(new Error('读取图片失败'));
        reader.readAsDataURL(file);
    });
    const img = await new Promise<HTMLImageElement>((resolve, reject) => {
        const el = new Image();
        el.onload = () => resolve(el);
        el.onerror = () => reject(new Error('图片解码失败'));
        el.src = dataUrl;
    });
    const MAX_EDGE = 1600;
    const scale = Math.min(1, MAX_EDGE / Math.max(img.naturalWidth, img.naturalHeight));
    const w = Math.max(1, Math.round(img.naturalWidth * scale));
    const h = Math.max(1, Math.round(img.naturalHeight * scale));
    const canvas = document.createElement('canvas');
    canvas.width = w;
    canvas.height = h;
    canvas.getContext('2d')!.drawImage(img, 0, 0, w, h);
    let quality = 0.8;
    let base64 = canvas.toDataURL('image/jpeg', quality).split(',')[1];
    while (base64.length > 1200000 && quality > 0.3) {
        quality -= 0.15;
        base64 = canvas.toDataURL('image/jpeg', quality).split(',')[1];
    }
    return base64;
}
function ChatPage() {
    usePreferences();
    const [input, setInput] = useState('');
    const [pendingImage, setPendingImage] = useState<{
        raw: string;
        preview: string;
    } | null>(null);
    const bottomRef = useRef<HTMLDivElement>(null);
    const fileInputRef = useRef<HTMLInputElement>(null);
    const restoreDraft = useCallback((request: TurnRequest) => {
        setInput((value) => value || request.message);
        if (request.imageBase64)
            setPendingImage((value) => value ?? {
                raw: request.imageBase64!,
                preview: `data:image/jpeg;base64,${request.imageBase64}`,
            });
    }, []);
    const { conversations, activeId, messages, streaming, notice, send, stop, selectConversation, } = useDurableChat(restoreDraft);
    useEffect(() => {
        bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
    }, [messages, pendingImage]);
    const handleImageFile = useCallback(async (file: File | null | undefined) => {
        if (!file || !file.type.startsWith('image/'))
            return;
        try {
            const raw = await compressImage(file);
            setPendingImage({ raw, preview: `data:image/jpeg;base64,${raw}` });
        }
        catch (e) {
            console.error('图片处理失败', e);
        }
    }, []);
    const handleSend = useCallback(async () => {
        const text = input.trim(), image = pendingImage;
        if ((!text && !image) || streaming)
            return;
        if (await send(text, image?.raw ?? null)) {
            setInput((value) => (value.trim() === text ? '' : value));
            setPendingImage((value) => (value === image ? null : value));
        }
    }, [input, pendingImage, streaming, send]);
    const submitForm = useCallback(async (source: string, action: string, values: Record<string, string>) => {
        await send(JSON.stringify({ __form__: { __action__: action, ...values } }), null, source);
    }, [send]);
    const handleNewConversation = () => {
        void selectConversation(null);
    };
    const loadHistory = (id: number) => {
        void selectConversation(id);
    };
    const handleStop = () => {
        void stop();
    };
    return (<div className="app">
      <aside className="sidebar">
        <button className="new-chat-btn" onClick={handleNewConversation}>{t("\uFF0B 新建会话")}</button>
        <div className="conversation-list">
          {conversations.map((conv) => (<div key={conv.id} className={`conversation-item ${conv.id === activeId ? 'active' : ''}`} onClick={() => loadHistory(conv.id)}>
              <div className="conversation-title">{conv.title}</div>
              <div className="conversation-time">{ui(conv.updatedAt)}</div>
            </div>))}
        </div>
        <SidebarTools />
      </aside>

      <main className="chat-area">
        <header className="chat-header">{t("TravelScope 智能旅游助手")}</header>

        <div className="message-list">
          {ui(messages.length === 0 && (<div className="empty-hint">
              <h2>{t("\uD83C\uDF0D 欢迎使用 TravelScope")}</h2>
              <p>{t("问我旅游规划\u3001天气\u3001酒店\u3001景点\u3001火车票\u3001机票\uFF0C什么都可以\uFF5E")}</p>
            </div>))}
          {messages.map((msg, idx) => (<div key={`${msg.turnId ?? msg.id ?? idx}:${msg.role}`} className={`message-row ${msg.role}`}>
              <div className={`bubble ${msg.role}`}>
                {ui(msg.intent && (<span className="intent-badge">
                    {ui(INTENT_LABELS[msg.intent] ?? msg.intent)}
                  </span>))}
                {/* 正文：assistant 用 Markdown 渲染（最终方案含表格/标题）；PLANNING 流期间正文为空 →
                R3② 实时预览（骨架先行/逐天填充）优先于阶段占位；done 全文到达后切换为终稿 */}
                {msg.role === 'assistant' ? (msg.content ? (<div className="bubble-content markdown-body">
                      <Md text={msg.content}/>
                    </div>) : msg.streaming ? (msg.intent === 'PLANNING' &&
                (msg.itinerarySkeleton ||
                    (msg.itineraryDays && msg.itineraryDays.length > 0)) ? (<div className="bubble-content itinerary-preview">
                        {ui(msg.itinerarySkeleton && (<div className="itinerary-preview-head">{t("\uD83D\uDDFA 行程骨架已就绪\uFF08")}{ui(msg.itinerarySkeleton.dayCount)}{ui(' ')}{t("天\uFF09")}{ui(msg.itinerarySkeleton.days.map((d) => (<span key={d.day} className="itinerary-preview-day-names">
                                Day{ui(d.day)}：
                                {ui(d.poiNames.join('、') || t("\uFF08待填充\uFF09"))}
                              </span>)))}
                          </div>))}
                        {msg.itineraryDays?.map((d) => (<div key={d.day} className="markdown-body itinerary-preview-day">
                            <Md text={d.markdown}/>
                          </div>))}
                        <div className="itinerary-preview-hint">{t("\u23F3 内容量产中\uFF08预览\uFF09\uFF0C最终方案以完成为准并附质检标注")}</div>
                      </div>) : (<div className="bubble-content phase-hint">
                        {ui(msg.intent === 'PLANNING'
                    ? t("\uD83D\uDDFA 正在为您规划行程\uFF0C过程见下方活动流\u2026") : t("思考中\u2026"))}
                      </div>)) : null) : (<div className="bubble-content">
                    {ui(msg.image && (<img className="user-image" src={msg.image} alt={t("用户图片")}/>))}
                    {msg.content}
                  </div>)}
                {ui(msg.streaming && <span className="cursor">▌</span>)}
                {ui(msg.requirementForm && (<RequirementFormCard payload={msg.requirementForm} submitted={!!msg.formSubmitted} disabled={streaming || !msg.turnId} onAction={(action, values) => submitForm(msg.turnId!, action, values)}/>))}
                {msg.reviewReport && (<ReviewReportCard raw={msg.reviewReport}/>)}
                {ui(msg.notice && (<div role="status" className="phase-hint">
                    {ui(msg.notice)}
                  </div>))}
              </div>
              {ui(msg.role === 'assistant' &&
                msg.activities &&
                msg.activities.length > 0 && (<div className="activity-wrap">
                    <ActivityStream activities={msg.activities}/>
                  </div>))}
            </div>))}
          <div ref={bottomRef}/>
        </div>

        <div className="input-area">
          {ui(notice && (<div role="status" className="phase-hint">
              {ui(notice)}
            </div>))}
          <div className="input-main">
            {/* 待发送图片预览（私人讲解员）：一次一张，可移除 */}
            {ui(pendingImage && (<div className="image-preview">
                <img src={pendingImage.preview} alt={t("待发送图片")}/>
                <button className="image-remove" onClick={() => setPendingImage(null)} title={t("移除图片")}>
                  ✕
                </button>
                <span className="image-hint">{t("发送后将由 AI 讲解这张图片\uFF08可附文字提问\uFF09")}</span>
              </div>))}
            <textarea value={input} placeholder={ui(pendingImage
            ? t("可附提问\uFF08如\uFF1A这是哪里\uFF1F帮我规划去这里的行程\uFF09\uFF0C留空则直接讲解\u2026") : t("输入你的问题\uFF0C例如\uFF1A帮我规划杭州三日游\u2026"))} rows={2} onChange={(e) => setInput(e.target.value)} onPaste={(e) => {
            const file = Array.from(e.clipboardData.files).find((f) => f.type.startsWith('image/'));
            if (file) {
                e.preventDefault();
                handleImageFile(file);
            }
        }} onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                handleSend();
            }
        }} disabled={streaming}/>
          </div>
          <div className="input-actions">
            <button className="photo-btn" onClick={() => fileInputRef.current?.click()} disabled={streaming} title={t("添加照片提问")}>
              📷
            </button>
            {ui(streaming ? (<button className="stop-btn" onClick={handleStop}>{t("停止")}</button>) : (<button className="send-btn" onClick={handleSend} disabled={!input.trim() && !pendingImage}>{t("发送")}</button>))}
          </div>
          {/* 隐藏文件选择（照片提问入口；粘贴截图走 textarea onPaste 同一压缩管线） */}
          <input ref={fileInputRef} type="file" accept="image/*" hidden onChange={(e) => {
            handleImageFile(e.target.files?.[0]);
            e.target.value = ''; // 允许重复选择同一文件
        }}/>
        </div>
      </main>
    </div>);
}
export default ChatPage;
