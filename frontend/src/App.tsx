import { useCallback, useEffect, useRef, useState } from 'react'
import './App.css'
import { createConversation, listConversations, listMessages, streamChat } from './api/chat'
import type { ChatMessage, ChatEvent, Conversation, IntentPayload } from './types'

const INTENT_LABELS: Record<string, string> = {
  CHAT: '💬 闲聊咨询',
  TOOL_CALL: '🔧 单点查询',
  PLANNING: '🗺️ 行程规划',
  RAG: '📚 知识库问答',
  UNKNOWN: '❓ 未识别',
}

function App() {
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
    refreshConversations()
  }, [refreshConversations])

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

    const userMsg: ChatMessage = { role: 'user', content: text }
    const assistantMsg: ChatMessage = { role: 'assistant', content: '', streaming: true, toolCalls: [] }
    setMessages((prev) => [...prev, userMsg, assistantMsg])

    try {
      await streamChat(activeId, text, (event: ChatEvent) => {
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

export default App
