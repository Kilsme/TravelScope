import { useCallback, useEffect, useRef, useState } from 'react'
import Markdown from 'react-markdown'
import '../App.css'
import { createConversation, listConversations, listMessages, streamChat } from '../api/chat'
import type { ChatMessage, ChatEvent, Conversation, IntentPayload } from '../types'

const INTENT_LABELS: Record<string, string> = {
  CHAT: '💬 闲聊咨询',
  TOOL_CALL: '🔧 单点查询',
  PLANNING: '🗺️ 行程规划',
  RAG: '📚 知识库问答',
  UNKNOWN: '❓ 未识别',
}

function ChatPage() {
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [activeId, setActiveId] = useState<number | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const abortRef = useRef(false)
  const bottomRef = useRef<HTMLDivElement>(null)

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
          setMessages(history.map((m) => ({ id: m.id, role: m.role, content: m.content })))
        }
      } catch (e) {
        console.error('加载会话列表失败', e)
      }
    })()
  }, [])

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const loadHistory = useCallback(async (id: number) => {
    setActiveId(id)
    setMessages([])
    try {
      const history = await listMessages(id)
      setMessages(history.map((m) => ({ id: m.id, role: m.role, content: m.content })))
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

  const handleSend = useCallback(async () => {
    const text = input.trim()
    if (!text || streaming) return

    setInput('')
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

    const userMsg: ChatMessage = { role: 'user', content: text }
    const assistantMsg: ChatMessage = { role: 'assistant', content: '', streaming: true, toolCalls: [] }
    setMessages((prev) => [...prev, userMsg, assistantMsg])

    try {
      await streamChat(convId, text, (event: ChatEvent) => {
        if (abortRef.current) return false
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
              cur.toolCalls = [...(cur.toolCalls ?? []), event.data]
              break
            }
            case 'clarify_question': {
              // intake-agent 的反问（经 ask_user 工具）：作为提示行追加，后续 delta 仍是主 Agent 的整合输出
              cur.content += `${cur.content ? '\n\n' : ''}💬 ${event.data}`
              break
            }
            case 'review_report': {
              // reviewer 质检报告（FR-S08）：markdown 全文，消息尾部折叠区渲染
              cur.reviewReport = event.data
              break
            }
            case 'done': {
              if (!cur.content && event.data) cur.content = event.data
              cur.streaming = false
              break
            }
            case 'error': {
              cur.content += `\n\n⚠️ ${event.data}`
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
      })
    } catch (e) {
      setMessages((prev) => {
        const next = [...prev]
        const cur = { ...next[next.length - 1] }
        cur.content += `\n\n⚠️ ${e instanceof Error ? e.message : '网络错误'}`
        cur.streaming = false
        next[next.length - 1] = cur
        return next
      })
    } finally {
      setStreaming(false)
    }
  }, [input, streaming, activeId, refreshConversations])

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
                {msg.toolCalls && msg.toolCalls.length > 0 && (
                  <div className="tool-chips">
                    {msg.toolCalls.map((tool, i) => (
                      <span key={i} className={`tool-chip ${tool.includes(':') ? 'done' : 'running'}`}>
                        🛠 {tool.replace(':', ' · ')}
                      </span>
                    ))}
                  </div>
                )}
      <div className="bubble-content">{msg.content || (msg.streaming ? '思考中…' : '')}</div>
      {msg.reviewReport && (
        <details className="review-report">
          <summary>📋 质检评分明细</summary>
          <div className="review-report-body">
            <Markdown>{msg.reviewReport}</Markdown>
          </div>
        </details>
      )}
      {msg.streaming && <span className="cursor">▌</span>}
              </div>
            </div>
          ))}
          <div ref={bottomRef} />
        </div>

        <div className="input-area">
          <textarea
            value={input}
            placeholder="输入你的问题，例如：帮我规划杭州三日游…"
            rows={2}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault()
                handleSend()
              }
            }}
            disabled={streaming}
          />
          {streaming ? (
            <button className="stop-btn" onClick={handleStop}>
              停止
            </button>
          ) : (
            <button className="send-btn" onClick={handleSend} disabled={!input.trim()}>
              发送
            </button>
          )}
        </div>
      </main>
    </div>
  )
}

export default ChatPage
