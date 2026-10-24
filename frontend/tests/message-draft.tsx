import { act, createElement, useLayoutEffect } from 'react'
import { createRoot } from 'react-dom/client'
import { useChatDraft } from '../src/chat/useChatDraft'
import { composeReply, createReplyDraft } from '../src/chat/messageActions'
import type { TurnRequest } from '../src/chat/turnTypes'

/** Exercise real hook updates in a browser, including async acceptance and recovery races. */
export async function verifyDrafts() {
  const check = (ok:boolean, message:string) => { if (!ok) throw new Error(message) }
  const target = document.createElement('div')
  document.body.append(target)
  const root = createRoot(target)
  let editor:ReturnType<typeof useChatDraft> | undefined
  function Probe() {
    const value = useChatDraft()
    useLayoutEffect(() => { editor = value },[value])
    return null
  }
  const env = globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?:boolean }
  const previous = env.IS_REACT_ACT_ENVIRONMENT
  env.IS_REACT_ACT_ENVIRONMENT = true
  try {
    await act(async () => { root.render(createElement(Probe)) })
    const get = () => editor!
    const quote = createReplyDraft('assistant','原始正文')
    const image = {raw:'sample-image',preview:'data:image/jpeg;base64,sample-image'}
    await act(async () => { get().update({quote,pendingImage:image}) })
    const message = composeReply('',quote,{assistant:'引用 AI：',user:'引用我：',question:'问题：',truncated:'截取'})
    const request:TurnRequest = {turnId:'rejected',conversationId:1,message,imageBase64:image.raw,sourceTurnId:null}
    let pending!:ReturnType<ReturnType<typeof useChatDraft>['beginSubmission']>
    await act(async () => { pending = get().beginSubmission(1,message); get().restoreDraft(request) })
    check(get().draft.input==='' && get().draft.quote===quote && get().draft.pendingImage===image,'In-memory rejection duplicated a quote or lost the photo')
    await act(async () => { get().acceptSubmission(pending) })
    check(!get().draft.input && !get().draft.quote && !get().draft.pendingImage,'Unchanged accepted draft was not cleared')

    await act(async () => { get().update({input:'同样的文字',quote}); pending=get().beginSubmission(1,'serialized') })
    await act(async () => { get().update({input:'编辑后又改回'}); get().update({input:'同样的文字'}); get().acceptSubmission(pending) })
    check(get().draft.input==='同样的文字' && get().draft.quote===quote,'Acceptance cleared a newer edit that had the same text')
    await act(async () => { pending=get().beginSubmission(1,'serialized');get().leaveConversation();get().update({input:'另一个会话'});get().acceptSubmission(pending) })
    check(get().draft.input==='另一个会话' && !get().draft.quote,'Old conversation acceptance cleared the new editor')

    await act(async () => { get().update({input:'',quote:null,pendingImage:null});get().restoreDraft(request) })
    check(get().draft.input===message && !get().draft.quote && get().draft.pendingImage?.raw===image.raw,'Reload recovery did not restore the serialized request exactly once')
    await act(async () => { get().leaveConversation();get().update({input:'',quote,pendingImage:null});get().restoreDraft(request) })
    check(get().draft.input==='' && get().draft.quote===quote,'Recovered rejection overwrote a newly selected quote')
    await act(async () => { get().update({input:'',quote:null,pendingImage:image});get().restoreDraft(request) })
    check(get().draft.input==='' && get().draft.pendingImage===image,'Recovered rejection mixed old text into a new photo draft')
    return {passed:true,checks:7}
  } finally {
    await act(async () => { root.unmount() })
    target.remove()
    env.IS_REACT_ACT_ENVIRONMENT = previous
  }
}
