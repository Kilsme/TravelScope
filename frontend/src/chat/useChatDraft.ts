import { useCallback, useRef, useState } from 'react'
import type { ReplyDraft } from './messageActions'
import type { TurnRequest } from './turnTypes'

export interface DraftImage { raw:string; preview:string }
interface Draft { input:string; pendingImage:DraftImage | null; quote:ReplyDraft | null; error:boolean }
interface Submission { revision:number; epoch:number }
const emptyDraft:Draft = {input:'',pendingImage:null,quote:null,error:false}
const requestKey = (conversationId:number | null, message:string) => JSON.stringify([conversationId,message])

/** Editor state stays independent of the serialized Outbox prompt, including rejected requests. */
export function useChatDraft() {
  const [draft,setDraft] = useState<Draft>(emptyDraft)
  const current = useRef(draft)
  const revision = useRef(0), epoch = useRef(0)
  const localAttempts = useRef(new Set<string>())
  const update = useCallback((patch:Partial<Draft>, edit = true) => {
    current.current = {...current.current,...patch}
    if (edit) revision.current++
    setDraft(current.current)
  },[])
  const restoreDraft = useCallback((request:TurnRequest) => {
    if (localAttempts.current.has(requestKey(request.conversationId,request.message))) return
    const value = current.current
    if (value.input || value.quote || value.pendingImage) return
    // After reload the serialized request is restored as ordinary text, without a second quote.
    update({input:request.message,quote:null,pendingImage:request.imageBase64 ? {
      raw:request.imageBase64,preview:`data:image/jpeg;base64,${request.imageBase64}`,
    } : null})
  },[update])
  const beginSubmission = (conversationId:number | null, message:string):Submission => {
    localAttempts.current.add(requestKey(conversationId,message))
    update({error:false},false)
    return {revision:revision.current,epoch:epoch.current}
  }
  const acceptSubmission = (submission:Submission) => {
    if (submission.revision === revision.current && submission.epoch === epoch.current) update(emptyDraft)
  }
  const leaveConversation = () => {
    epoch.current++; localAttempts.current.clear(); update({quote:null,error:false})
  }
  return {draft,update,restoreDraft,beginSubmission,acceptSubmission,leaveConversation}
}
