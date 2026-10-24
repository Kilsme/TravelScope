import { useState, type ReactNode } from 'react'
import type { ChatMessage } from '../types'
import { messageText } from '../chat/messageActions'
import MessageActions from './MessageActions'

/** Shares the actual typewriter busy state with the toolbar; no duplicate animation timer. */
export default function ChatMessageItem({ message, streaming, onReply, children, activity }: {
  message:ChatMessage; streaming:boolean; onReply:() => void
  children:(onBusyChange:(busy:boolean) => void) => ReactNode; activity:ReactNode
}) {
  const [displayBusy,setDisplayBusy] = useState(message.role === 'assistant')
  const text = messageText(message)
  const disabled = !text.trim() || Boolean(message.streaming) || (message.role === 'assistant' && displayBusy)
  return <div className={`message-row ${message.role}`} data-turn-id={message.turnId} data-message-id={message.id}>
    {children(setDisplayBusy)}
    <MessageActions text={text} disabled={disabled} replyDisabled={streaming} onReply={onReply} />
    {activity}
  </div>
}
