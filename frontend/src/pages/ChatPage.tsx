import { useCallback, useEffect, useRef, useState } from 'react'
import '../App.css'
import { createConversation, listConversations, listMessages, streamChat } from '../api/chat'
import MarkdownView from '../components/MarkdownView'
import RequirementFormCard from '../components/RequirementFormCard'
import ReviewReportCard from '../components/ReviewReportCard'
import type {
  AgentActivity,
  ChatMessage,
  ChatEvent,
  Conversation,
  IntentPayload,
  ItineraryDaySection,
  ItinerarySkeleton,
  RequirementFormPayload,
} from '../types'

/** Markdown 渲染（全站共享出口 MarkdownView：GFM+流式清理+波浪号免疫+图片直连定制，见 components/MarkdownView.tsx） */
function Md({ text }: { text: string }) {
  return <MarkdownView text={text} />
}

const INTENT_LABELS: Record<string, string> = {
  CHAT: '💬 闲聊咨询',
  TOOL_CALL: '🔧 单点查询',
  PLANNING: '🗺️ 行程规划',
  RAG: '📚 知识库问答',
  UNKNOWN: '❓ 未识别',
}

/** 活动流状态 → 圆点样式（RUNNING 黄 / SUCCESS 绿 / FAILED 红 / THINKING 蓝） */
const ACTIVITY_DOT: Record<string, string> = {
  RUNNING: 'dot-running',
  SUCCESS: 'dot-success',
  FAILED: 'dot-failed',
  THINKING: 'dot-thinking',
}

/** 活动流子代理名 → 中文可读名 */
const AGENT_LABELS: Record<string, string> = {
  'travel-master': '主规划',
  'intake-agent': '需求收集',
  'planning-agent': '行程规划',
  'poi-research': '景点调研',
  'route-optimizer': '路线优化',
  'reviewer-agent': '质量评审',
}

/**
 * Agent 活动流小字区（2026-09-22 过程/结果分离改造）：
 * 气泡下方灰色小字流式滚动（最近 4 条，更旧的淡出），点击标题栏折叠。
 * 过程叙述（委派/工具调用/思考摘要）在这里展示，正文只留最终结果。
 */
function ActivityStream({ activities }: { activities: AgentActivity[] }) {
  const [collapsed, setCollapsed] = useState(false)
  if (!activities || activities.length === 0) return null
  // RUNNING 条目若已有同名 SUCCESS/FAILED 后续条目，则隐藏中间 RUNNING（减噪）
  const resolved = new Set(
    activities.filter((a) => a.state === 'SUCCESS' || a.state === 'FAILED').map((a) => a.action),
  )
  const visible = activities.filter((a) => !(a.state === 'RUNNING' && resolved.has(a.action)))
  return (
    <div className={`activity-stream ${collapsed ? 'collapsed' : ''}`}>
      <button className="activity-toggle" onClick={() => setCollapsed((c) => !c)}>
        ⚙️ Agent 活动（{visible.length}）{collapsed ? ' ▸' : ' ▾'}
      </button>
      {!collapsed && (
        <div className="activity-items">
          {visible.slice(-4).map((a, i, arr) => (
            <div key={i} className={`activity-item ${i < arr.length - 1 ? 'faded' : ''}`}>
              <span className={`activity-dot ${ACTIVITY_DOT[a.state] ?? 'dot-thinking'}`} />
              <span className="activity-agent">{AGENT_LABELS[a.agent] ?? a.agent}</span>
              <span className="activity-action">{a.action}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/**
 * 图片压缩（私人讲解员，2026-10-22）：canvas 重绘最长边 1600px、JPEG q0.8；
 * base64 超 1.2MB 自动降质（网关请求体 2MiB 硬上限，留 prompt/历史余量）。
 * 返回裸 base64（不带 data: 前缀，后端 Base64Source 约定）。
 */
async function compressImage(file: File): Promise<string> {
  const dataUrl = await new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as string)
    reader.onerror = () => reject(new Error('读取图片失败'))
    reader.readAsDataURL(file)
  })
  const img = await new Promise<HTMLImageElement>((resolve, reject) => {
    const el = new Image()
    el.onload = () => resolve(el)
    el.onerror = () => reject(new Error('图片解码失败'))
    el.src = dataUrl
  })
  const MAX_EDGE = 1600
  const scale = Math.min(1, MAX_EDGE / Math.max(img.naturalWidth, img.naturalHeight))
  const w = Math.max(1, Math.round(img.naturalWidth * scale))
  const h = Math.max(1, Math.round(img.naturalHeight * scale))
  const canvas = document.createElement('canvas')
  canvas.width = w
  canvas.height = h
  canvas.getContext('2d')!.drawImage(img, 0, 0, w, h)
  let quality = 0.8
  let base64 = canvas.toDataURL('image/jpeg', quality).split(',')[1]
  while (base64.length > 1_200_000 && quality > 0.3) {
    quality -= 0.15
    base64 = canvas.toDataURL('image/jpeg', quality).split(',')[1]
  }
  return base64
}

function ChatPage() {
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [activeId, setActiveId] = useState<number | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  /** 待发送图片（私人讲解员）：raw = 裸 base64（发后端），preview = data URL（气泡预览） */
  const [pendingImage, setPendingImage] = useState<{ raw: string; preview: string } | null>(null)
  const abortRef = useRef(false)
  const bottomRef = useRef<HTMLDivElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const refreshConversations = useCallback(async () => {
    try {
      setConversations(await listConversations())
    } catch (e) {
      console.error('加载会话列表失败', e)
    }
  }, [])

  useEffect(() => {
    // 挂载时自动选中最近会话：activeId 为 null 时发消息会触发后端自动新建会话，
    // 而 SSE 流不回传新会话 ID → activeId 永远为 null → 每条消息各建一个会话
    // （意图缓存/需求状态机/Agent 记忆全部按会话隔离，等于每条消息全部失忆）
    ;(async () => {
      try {
        const list = await listConversations()
        setConversations(list)
        if (list.length > 0) {
          setActiveId(list[0].id) // 列表按 updatedAt 倒序，[0] 即最近会话
          const history = await listMessages(list[0].id)
          // image = 后端能力 URL（MinIO 代理端点），历史图片轮气泡回显
          setMessages(history.map((m) => ({ id: m.id, role: m.role, content: m.content, image: m.image })))
        }
      } catch (e) {
        console.error('加载会话列表失败', e)
      }
    })()
  }, [])

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, pendingImage])

  const loadHistory = useCallback(async (id: number) => {
    setActiveId(id)
    setMessages([])
    try {
      const history = await listMessages(id)
      setMessages(history.map((m) => ({ id: m.id, role: m.role, content: m.content, image: m.image })))
    } catch (e) {
      console.error('加载历史消息失败', e)
    }
  }, [])

  const handleNewConversation = useCallback(async () => {
    const conv = await createConversation()
    await refreshConversations()
    setActiveId(conv.id)
    setMessages([])
  }, [refreshConversations])

  /** 选择/粘贴图片 → 压缩 → 待发送预览（替换式：一次一张） */
  const handleImageFile = useCallback(async (file: File | null | undefined) => {
    if (!file || !file.type.startsWith('image/')) return
    try {
      const raw = await compressImage(file)
      setPendingImage({ raw, preview: `data:image/jpeg;base64,${raw}` })
    } catch (e) {
      console.error('图片处理失败', e)
    }
  }, [])

  /**
   * SSE 事件应用（发送消息与清单提交共用，避免两份处理逻辑漂移）。
   * 全部事件作用于「当前最后一条 assistant 消息」。
   */
  const applyEvent = useCallback(
    (event: ChatEvent) => {
      setMessages((prev) => {
        const next = [...prev]
        const cur = { ...next[next.length - 1] }

        switch (event.event) {
          case 'intent': {
            try {
              const payload = JSON.parse(event.data) as IntentPayload
              cur.intent = payload.intent
            } catch {
              cur.intent = 'UNKNOWN'
            }
            break
          }
          case 'delta': {
            cur.content += event.data
            break
          }
          case 'tool': {
            // 兼容旧事件（后端已废弃为 agent_status，防御性保留）
            cur.activities = [...(cur.activities ?? []), { agent: 'tool', action: event.data, state: 'RUNNING' }]
            break
          }
          case 'agent_status': {
            // Agent 活动流（2026-09-22 过程/结果分离）：工具调用/子代理委派/过程思考
            // 累积到气泡下方的小字活动流，不进正文
            try {
              const a = JSON.parse(event.data) as AgentActivity
              cur.activities = [...(cur.activities ?? []), a]
            } catch {
              /* 忽略解析失败 */
            }
            break
          }
          case 'clarify_question': {
            // 旧版 intake 反问（v3）：作为提示行追加
            cur.content += `${cur.content ? '\n\n' : ''}💬 ${event.data}`
            break
          }
          case 'requirement_form': {
            // 需求清单卡片（S2.3）：覆盖式挂载——后端每轮重推即原地刷新
            try {
              cur.requirementForm = JSON.parse(event.data) as RequirementFormPayload
              cur.formSubmitted = false
            } catch {
              /* 解析失败忽略 */
            }
            break
          }
          case 'review_report': {
            // reviewer 质检报告（FR-S08）：载荷 JSON，质检明细折叠区分级渲染
            cur.reviewReport = event.data
            break
          }
          case 'itinerary_skeleton': {
            // R3② 流式行程进度：骨架（route_plan 落盘即推，代码渲染零 LLM）
            try {
              cur.itinerarySkeleton = JSON.parse(event.data) as ItinerarySkeleton
            } catch {
              /* 解析失败忽略 */
            }
            break
          }
          case 'itinerary_day': {
            // R3② 流式行程进度：逐天正文（修订轮变化天 version+1 重推，旧版本丢弃）
            try {
              const d = JSON.parse(event.data) as ItineraryDaySection
              const list = [...(cur.itineraryDays ?? [])]
              const i = list.findIndex((x) => x.day === d.day)
              if (i < 0 || list[i].version < d.version) {
                if (i >= 0) list[i] = d
                else list.push(d)
                cur.itineraryDays = list.sort((a, b) => a.day - b.day)
              }
            } catch {
              /* 解析失败忽略 */
            }
            break
          }
          case 'done': {
            if (!cur.content && event.data) cur.content = event.data
            cur.streaming = false
            break
          }
          case 'error': {
            cur.content += `${cur.content ? '\n\n' : ''}⚠️ ${event.data}`
            cur.streaming = false
            break
          }
        }
        next[next.length - 1] = cur
        return next
      })
      if (event.event === 'done' || event.event === 'error') {
        refreshConversations()
      }
    },
    [refreshConversations],
  )

  const handleSend = useCallback(async () => {
    const text = input.trim()
    const image = pendingImage
    if ((!text && !image) || streaming) return

    setInput('')
    setPendingImage(null)
    setStreaming(true)
    abortRef.current = false

    // 无激活会话时先显式新建并锁定 ID：否则后端自动建会话但 SSE 不回传 ID，
    // activeId 保持 null，下一条消息又会新建一个会话（跨轮上下文全部丢失）
    let convId = activeId
    if (convId == null) {
      try {
        const conv = await createConversation()
        convId = conv.id
        setActiveId(conv.id)
        await refreshConversations()
      } catch (e) {
        setMessages((prev) => {
          const next = [...prev]
          const cur = { ...next[next.length - 1] }
          cur.content += `\n\n⚠️ ${e instanceof Error ? e.message : '创建会话失败'}`
          cur.streaming = false
          next[next.length - 1] = cur
          return next
        })
        setStreaming(false)
        return
      }
    }

    const userMsg: ChatMessage = { role: 'user', content: text, image: image?.preview }
    const assistantMsg: ChatMessage = { role: 'assistant', content: '', streaming: true, activities: [] }
    setMessages((prev) => [...prev, userMsg, assistantMsg])

    try {
      await streamChat(
        convId,
        text,
        (event: ChatEvent) => {
          if (abortRef.current) return false
          applyEvent(event)
        },
        image?.raw,
      )
    } catch (e) {
      appendAssistantError(e)
    } finally {
      setStreaming(false)
    }
  }, [input, streaming, activeId, pendingImage, refreshConversations, applyEvent])

  /** 清单提交（S2.3）：结构化消息走既有 /api/chat/stream 通道（不新增端点） */
  const submitForm = useCallback(
    async (action: 'submit' | 'skip' | 'cancel', values: Record<string, string>) => {
      if (streaming) return
      let convId = activeId
      if (convId == null) {
        try {
          const conv = await createConversation()
          convId = conv.id
          setActiveId(conv.id)
          await refreshConversations()
        } catch (e) {
          appendAssistantError(e)
          return
        }
      }
      setStreaming(true)
      abortRef.current = false

      const body = JSON.stringify({ __form__: { __action__: action, ...values } })
      const label =
        action === 'cancel' ? '（取消规划）' : action === 'skip' ? '（按推荐安排）' : '（提交需求清单）'
      // 旧卡片折叠为只读摘要，防重复提交；随后追加本轮用户/助手气泡
      setMessages((prev) => [
        ...prev.map((m) => (m.requirementForm ? { ...m, formSubmitted: true } : m)),
        { role: 'user', content: label },
        { role: 'assistant', content: '', streaming: true, activities: [] },
      ])

      try {
        await streamChat(convId, body, (event: ChatEvent) => {
          if (abortRef.current) return false
          applyEvent(event)
        })
      } catch (e) {
        appendAssistantError(e)
      } finally {
        setStreaming(false)
      }
    },
    [streaming, activeId, refreshConversations, applyEvent],
  )

  /** 尾部错误提示（当前最后一条 assistant 气泡） */
  const appendAssistantError = useCallback((e: unknown) => {
    setMessages((prev) => {
      const next = [...prev]
      const cur = { ...next[next.length - 1] }
      cur.content += `\n\n⚠️ ${e instanceof Error ? e.message : '网络错误'}`
      cur.streaming = false
      next[next.length - 1] = cur
      return next
    })
  }, [])

  const handleStop = useCallback(() => {
    abortRef.current = true
    setStreaming(false)
    setMessages((prev) => {
      const next = [...prev]
      const cur = { ...next[next.length - 1] }
      cur.streaming = false
      cur.content += '\n\n（已停止）'
      next[next.length - 1] = cur
      return next
    })
  }, [])

  return (
    <div className="app">
      <aside className="sidebar">
        <button className="new-chat-btn" onClick={handleNewConversation}>
          ＋ 新建会话
        </button>
        <div className="conversation-list">
          {conversations.map((conv) => (
            <div
              key={conv.id}
              className={`conversation-item ${conv.id === activeId ? 'active' : ''}`}
              onClick={() => loadHistory(conv.id)}
            >
              <div className="conversation-title">{conv.title}</div>
              <div className="conversation-time">{conv.updatedAt}</div>
            </div>
          ))}
        </div>
      </aside>

      <main className="chat-area">
        <header className="chat-header">TravelScope 智能旅游助手</header>

        <div className="message-list">
          {messages.length === 0 && (
            <div className="empty-hint">
              <h2>🌍 欢迎使用 TravelScope</h2>
              <p>问我旅游规划、天气、酒店、景点、火车票、机票，什么都可以～</p>
            </div>
          )}
          {messages.map((msg, idx) => (
            <div key={idx} className={`message-row ${msg.role}`}>
              <div className={`bubble ${msg.role}`}>
                {msg.intent && (
                  <span className="intent-badge">{INTENT_LABELS[msg.intent] ?? msg.intent}</span>
                )}
                {/* 正文：assistant 用 Markdown 渲染（最终方案含表格/标题）；PLANNING 流期间正文为空 →
                    R3② 实时预览（骨架先行/逐天填充）优先于阶段占位；done 全文到达后切换为终稿 */}
                {msg.role === 'assistant' ? (
                  msg.content ? (
                    <div className="bubble-content markdown-body">
                      <Md text={msg.content} />
                    </div>
                  ) : msg.streaming ? (
                    msg.intent === 'PLANNING' &&
                    (msg.itinerarySkeleton || (msg.itineraryDays && msg.itineraryDays.length > 0)) ? (
                      <div className="bubble-content itinerary-preview">
                        {msg.itinerarySkeleton && (
                          <div className="itinerary-preview-head">
                            🗺 行程骨架已就绪（{msg.itinerarySkeleton.dayCount} 天）
                            {msg.itinerarySkeleton.days.map((d) => (
                              <span key={d.day} className="itinerary-preview-day-names">
                                Day{d.day}：{d.poiNames.join('、') || '（待填充）'}
                              </span>
                            ))}
                          </div>
                        )}
                        {msg.itineraryDays?.map((d) => (
                          <div key={d.day} className="markdown-body itinerary-preview-day">
                            <Md text={d.markdown} />
                          </div>
                        ))}
                        <div className="itinerary-preview-hint">
                          ⏳ 内容量产中（预览），最终方案以完成为准并附质检标注
                        </div>
                      </div>
                    ) : (
                      <div className="bubble-content phase-hint">
                        {msg.intent === 'PLANNING' ? '🗺 正在为您规划行程，过程见下方活动流…' : '思考中…'}
                      </div>
                    )
                  ) : null
                ) : (
                  <div className="bubble-content">
                    {msg.image && <img className="user-image" src={msg.image} alt="用户图片" />}
                    {msg.content}
                  </div>
                )}
                {msg.streaming && <span className="cursor">▌</span>}
                {msg.requirementForm && (
                  <RequirementFormCard
                    payload={msg.requirementForm}
                    submitted={!!msg.formSubmitted}
                    disabled={streaming}
                    onAction={submitForm}
                  />
                )}
                {msg.reviewReport && <ReviewReportCard raw={msg.reviewReport} />}
              </div>
              {msg.role === 'assistant' && msg.activities && msg.activities.length > 0 && (
                <div className="activity-wrap">
                  <ActivityStream activities={msg.activities} />
                </div>
              )}
            </div>
          ))}
          <div ref={bottomRef} />
        </div>

        <div className="input-area">
          <div className="input-main">
            {/* 待发送图片预览（私人讲解员）：一次一张，可移除 */}
            {pendingImage && (
              <div className="image-preview">
                <img src={pendingImage.preview} alt="待发送图片" />
                <button className="image-remove" onClick={() => setPendingImage(null)} title="移除图片">
                  ✕
                </button>
                <span className="image-hint">发送后将由 AI 讲解这张图片（可附文字提问）</span>
              </div>
            )}
            <textarea
              value={input}
              placeholder={
                pendingImage ? '可附提问（如：这是哪里？帮我规划去这里的行程），留空则直接讲解…' : '输入你的问题，例如：帮我规划杭州三日游…'
              }
              rows={2}
              onChange={(e) => setInput(e.target.value)}
              onPaste={(e) => {
                const file = Array.from(e.clipboardData.files).find((f) => f.type.startsWith('image/'))
                if (file) {
                  e.preventDefault()
                  handleImageFile(file)
                }
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault()
                  handleSend()
                }
              }}
              disabled={streaming}
            />
          </div>
          <div className="input-actions">
            <button
              className="photo-btn"
              onClick={() => fileInputRef.current?.click()}
              disabled={streaming}
              title="添加照片提问"
            >
              📷
            </button>
            {streaming ? (
              <button className="stop-btn" onClick={handleStop}>
                停止
              </button>
            ) : (
              <button className="send-btn" onClick={handleSend} disabled={!input.trim() && !pendingImage}>
                发送
              </button>
            )}
          </div>
          {/* 隐藏文件选择（照片提问入口；粘贴截图走 textarea onPaste 同一压缩管线） */}
          <input
            ref={fileInputRef}
            type="file"
            accept="image/*"
            hidden
            onChange={(e) => {
              handleImageFile(e.target.files?.[0])
              e.target.value = '' // 允许重复选择同一文件
            }}
          />
        </div>
      </main>
    </div>
  )
}

export default ChatPage
