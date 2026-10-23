import { useUiNotice } from '../preferences/useUiNotice'
import { resolveMessage } from '../preferences/i18n'
import { getPreferences } from '../preferences/store'
import { useCallback, useEffect, useRef, useState } from 'react'
import { listConversations, listMessages } from '../api/chat'
import type { ChatMessage, Conversation } from '../types'
import {
  cancelTurn,
  conversationTurns,
  currentIdentity,
  getTurn,
  TurnApiError,
} from './api'
import { followTurn, recoverRequest, sendOutbox } from './recovery'
import {
  deleteOutbox,
  listOutbox,
  listTurns,
  saveOutbox,
  saveTurn,
} from './storage'
import {
  activeStatuses,
  type Outbox,
  type TurnRequest,
  type TurnView,
} from './turnTypes'

function label(text: string) {
  try {
    const form = JSON.parse(text).__form__
    if (form)
      return form.__action__ === 'cancel'
        ? '（取消规划）'
        : form.__action__ === 'skip'
          ? '（按推荐安排）'
          : '（提交需求清单）'
  } catch {
    /* ordinary text */
  }
  return text
}
export function mergeMessages(
  history: ChatMessage[],
  turns: TurnView[],
): ChatMessage[] {
  const messages = [...history]
  for (const turn of [...turns].sort((a, b) =>
    a.createdAt.localeCompare(b.createdAt),
  )) {
    const user: ChatMessage = {
      id: turn.userMessageId ?? undefined,
      turnId: turn.turnId,
      role: 'user',
      content: label(turn.inputText),
      image: turn.image,
      createdAt: turn.createdAt,
    }
    const assistant: ChatMessage = {
      ...turn.snapshot,
      id: turn.assistantMessageId ?? undefined,
      turnId: turn.turnId,
      role: 'assistant',
      content: turn.snapshot.content,
      turnStatus: turn.status,
      streaming: activeStatuses.has(turn.status),
      notice: turn.snapshot.terminal?.error?.message,
    }
    const u = messages.findIndex(
      (m) => m.turnId === turn.turnId && m.role === 'user',
    )
    if (u >= 0)
      messages[u] = {
        ...messages[u],
        ...user,
        image: user.image ?? messages[u].image,
      }
    else messages.push(user)
    const a = messages.findIndex(
      (m) => m.turnId === turn.turnId && m.role === 'assistant',
    )
    if (a >= 0) messages[a] = assistant
    else
      messages.splice(
        messages.findIndex(
          (m) => m.turnId === turn.turnId && m.role === 'user',
        ) + 1,
        0,
        assistant,
      )
  }
  // Only the newest form may be used, including when an earlier form was never submitted.
  const latest = turns.reduce<TurnView | undefined>(
    (a, b) => (!a || b.createdAt > a.createdAt ? b : a),
    undefined,
  )
  return messages.map((m) =>
    m.requirementForm && m.turnId !== latest?.turnId
      ? { ...m, formSubmitted: true }
      : m,
  )
}

export function useDurableChat(restoreDraft: (request: TurnRequest) => void) {
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [activeId, setActiveId] = useState<number | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [notice, setNotice] = useUiNotice('正在恢复会话…')
  const [sendError, setSendError] = useUiNotice('')
  const rejectedDrafts = useRef(new Map<number | null, TurnRequest>())
  const [ready, setReady] = useState(false)
  const [sending, setSending] = useState(false)
  const owner = useRef<number | null>(null),
    active = useRef<number | null>(null)
  const views = useRef(new Map<string, TurnView>()),
    histories = useRef(new Map<number, ChatMessage[]>())
  const observer = useRef<AbortController | null>(null),
    generation = useRef(0)
  const submissions = useRef(
    new Map<string, { abort: AbortController; promise: Promise<boolean> }>(),
  )
  const mounted = useRef(true)
  const saving = useRef(false)
  const render = useCallback(() => {
    if (!mounted.current) return
    setMessages(
      mergeMessages(
        histories.current.get(active.current ?? -1) ?? [],
        [...views.current.values()].filter(
          (t) => t.conversationId === (active.current ?? -1),
        ),
      ),
    )
    setSending(
      [...submissions.current.keys()].some(
        (id) =>
          views.current.get(id)?.conversationId === (active.current ?? -1),
      ),
    )
  }, [])
  const refresh = useCallback(async () => {
    const list = await listConversations()
    if (mounted.current) setConversations(list)
  }, [])
  const publish = useCallback(
    async (view: TurnView) => {
      if (!mounted.current) return
      // Cache snapshot and cursor together. A failed cache write never fabricates a newer cursor.
      if (owner.current != null) {
        try {
          await saveTurn(owner.current, view)
        } catch {
          setNotice('本地恢复缓存不可用；重新打开时将从服务器恢复。')
        }
      }
      if (!mounted.current) return
      views.current.set(view.turnId, view)
      render()
    },
    [render],
  )
  const observe = useCallback(
    (view: TurnView) => {
      observer.current?.abort()
      const abort = new AbortController()
      observer.current = abort
      const version = generation.current
      void followTurn(
        view,
        abort.signal,
        async (next) => {
          if (!abort.signal.aborted && version === generation.current)
            await publish(next)
        },
        (text) => {
          if (!abort.signal.aborted && version === generation.current)
            setNotice(text)
        },
      )
        .then(() => {
          if (!abort.signal.aborted) void refresh().catch(() => {})
        })
        .catch((e) => {
          if (!abort.signal.aborted && version === generation.current)
            setNotice(e instanceof Error ? e.message : '暂时无法恢复任务')
        })
    },
    [publish, refresh],
  )
  const selectConversation = useCallback(
    async (id: number | null) => {
      const version = ++generation.current
      observer.current?.abort()
      active.current = id
      setActiveId(id)
      setNotice('')
      setSendError('')
      const draft = rejectedDrafts.current.get(id)
      if (draft) restoreDraft(draft)
      render()
      if (owner.current != null)
        localStorage.setItem(
          `travelscope:last-chat:${owner.current}`,
          String(id ?? 'new'),
        )
      if (id == null) return
      try {
        const [history, turns] = await Promise.all([
          listMessages(id),
          conversationTurns(id),
        ])
        if (version !== generation.current || !mounted.current) return
        histories.current.set(id, history)
        for (const turn of turns) views.current.set(turn.turnId, turn)
        render()
        const running = turns.find((t) => activeStatuses.has(t.status))
        if (running) observe(running)
      } catch (e) {
        if (version === generation.current && mounted.current)
          setNotice(e instanceof Error ? e.message : '加载会话失败')
      }
    },
    [observe, render, restoreDraft],
  )

  const resume = useCallback(
    (entry: Outbox): Promise<boolean> => {
      const existing = submissions.current.get(entry.request.turnId)
      if (existing) return existing.promise
      const abort = new AbortController()
      const conversationId = entry.request.conversationId ?? -1
      if (!views.current.has(entry.request.turnId))
        views.current.set(entry.request.turnId, {
          turnId: entry.request.turnId,
          conversationId,
          status: 'PREPARING',
          userMessageId: null,
          assistantMessageId: null,
          lastSeq: 0,
          snapshotSeq: 0,
          snapshot: { content: '' },
          outcome: null,
          inputText: entry.request.message,
          image: entry.request.imageBase64
            ? `data:image/jpeg;base64,${entry.request.imageBase64}`
            : undefined,
          createdAt: entry.createdAt,
        })
      const promise = (async () => {
        try {
          const turn = await sendOutbox(entry, abort.signal, (text) => {
            if (
              active.current === entry.request.conversationId &&
              mounted.current
            )
              setNotice(text)
          })
          await publish(turn)
          if (entry.request.sourceTurnId) {
            const source = views.current.get(entry.request.sourceTurnId)
            if (source)
              await publish({
                ...source,
                snapshot: { ...source.snapshot, formSubmitted: true },
              })
            void getTurn(entry.request.sourceTurnId, abort.signal)
              .then(publish)
              .catch(() => {})
          }
          if (
            !abort.signal.aborted &&
            active.current === entry.request.conversationId
          ) {
            active.current = turn.conversationId
            setActiveId(turn.conversationId)
            localStorage.setItem(
              `travelscope:last-chat:${entry.userId}`,
              String(turn.conversationId),
            )
            setNotice('')
            render()
            if (activeStatuses.has(turn.status)) observe(turn)
          }
          void refresh().catch(() => {})
          return true
        } catch (e) {
          if (abort.signal.aborted) return false
          if (
            e instanceof TurnApiError &&
            e.status !== 401 &&
            e.status < 500 &&
            e.status !== 429
          ) {
            await deleteOutbox(entry)
            if (!entry.request.sourceTurnId) {
              rejectedDrafts.current.set(
                entry.request.conversationId,
                entry.request,
              )
              if (active.current === entry.request.conversationId)
                restoreDraft(entry.request)
            }
            views.current.delete(entry.request.turnId)
            if (
              e.code === 'CONVERSATION_BUSY' &&
              e.activeTurnId &&
              active.current === entry.request.conversationId
            ) {
              try {
                const running = await getTurn(e.activeTurnId, abort.signal)
                await publish(running)
                if (!abort.signal.aborted && active.current === running.conversationId) observe(running)
              } catch { /* Keep the rejected draft; opening the conversation retries recovery. */ }
            }
          }
          if (
            mounted.current &&
            active.current === entry.request.conversationId
          )
            setSendError(
              e instanceof Error ? e.message : '发送失败，输入已保留',
            )
          return false
        } finally {
          submissions.current.delete(entry.request.turnId)
          render()
        }
      })()
      submissions.current.set(entry.request.turnId, { abort, promise })
      render()
      return promise
    },
    [observe, publish, refresh, render, restoreDraft],
  )

  useEffect(() => {
    mounted.current = true
    let disposed = false
    const boot = new AbortController()
    const pendingSubmissions = submissions.current
    const stopRecovery = () => {
      disposed = true
      ++generation.current
      boot.abort()
      observer.current?.abort()
      for (const item of pendingSubmissions.values()) item.abort.abort()
    }
    window.addEventListener('travelscope:logout', stopRecovery)
    void (async () => {
      try {
        const identity = await recoverRequest(
          () => currentIdentity(boot.signal),
          boot.signal,
          setNotice,
        )
        if (disposed) return
        owner.current = identity.userId
        localStorage.setItem('travelscope_user_id', String(identity.userId))
        const [cached, outbox, list] = await Promise.all([
          listTurns(identity.userId).catch(() => []),
          listOutbox(identity.userId).catch(() => []),
          recoverRequest(listConversations, boot.signal, setNotice),
        ])
        if (disposed) return
        for (const item of cached) views.current.set(item.turnId, item)
        setConversations(list)
        const saved = localStorage.getItem(
          `travelscope:last-chat:${identity.userId}`,
        )
        const selected =
          saved === 'new'
            ? null
            : (list.find((c) => c.id === Number(saved))?.id ??
              list[0]?.id ??
              null)
        await selectConversation(selected)
        if (disposed) return
        setReady(true)
        for (const entry of outbox) if (mounted.current) void resume(entry)
      } catch (e) {
        if (!disposed)
          setNotice(e instanceof Error ? e.message : '无法确认账户，请重新登录')
      }
    })()
    return () => {
      disposed = true
      boot.abort()
      window.removeEventListener('travelscope:logout', stopRecovery)
      mounted.current = false
      stopRecovery()
      pendingSubmissions.clear()
    }
  }, [resume, selectConversation])

  const streaming = sending || messages.some((m) => m.streaming)
  const send = useCallback(
    async (
      message: string,
      imageBase64: string | null = null,
      sourceTurnId: string | null = null,
    ) => {
      if (!ready || streaming || saving.current || owner.current == null)
        return false
      setSendError('')
      rejectedDrafts.current.delete(active.current)
      saving.current = true
      const request: TurnRequest = {
        turnId: crypto.randomUUID(),
        locale: getPreferences().locale,
        conversationId: active.current,
        message,
        imageBase64,
        sourceTurnId,
      }
      let entry: Outbox
      try {
        entry = await saveOutbox(owner.current, request)
      } catch {
        saving.current = false
        setNotice('无法安全保存待发送消息，请保留输入并检查浏览器存储空间。')
        return false
      }
      try {
        return await resume(entry)
      } finally {
        saving.current = false
      }
    },
    [ready, streaming, resume],
  )
  const stop = useCallback(async () => {
    const turn = [...views.current.values()].find(
      (t) =>
        t.conversationId === active.current && activeStatuses.has(t.status),
    )
    if (!turn) {
      setNotice('正在确认发送结果，请联网后再停止。')
      return
    }
    try {
      const next = await cancelTurn(turn.turnId)
      await publish(next)
      if (active.current === next.conversationId) observe(next)
    } catch (e) {
      setNotice(e instanceof Error ? e.message : '取消请求未确认，请重试停止')
    }
  }, [publish, observe])
  return {
    conversations,
    activeId,
    messages,
    streaming: streaming || !ready,
    notice: resolveMessage(sendError.fallback ? sendError : notice),
    send,
    stop,
    selectConversation,
  }
}
