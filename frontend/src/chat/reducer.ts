import type {
  AgentActivity,
  IntentPayload,
  ItineraryDaySection,
  ItinerarySkeleton,
  RequirementFormPayload,
} from '../types'
import type { Terminal, TurnEvent, TurnView } from './turnTypes'

export class SequenceGap extends Error {}
export function applyTurnEvent(view: TurnView, event: TurnEvent): TurnView {
  if (event.turnId !== view.turnId) throw new SequenceGap('事件不属于当前任务')
  if (event.seq <= view.snapshotSeq) return view
  if (event.seq !== view.snapshotSeq + 1)
    throw new SequenceGap('事件缺失，需要重新同步')
  const snapshot = structuredClone(view.snapshot)
  let status = view.status
  let assistantMessageId = view.assistantMessageId
  let outcome = view.outcome
  switch (event.type) {
    case 'delta':
      snapshot.content += event.payload as string
      break
    case 'clarify_question':
      snapshot.content += `\n\n💬 ${event.payload as string}`
      break
    case 'intent':
      snapshot.intent = (event.payload as IntentPayload).intent
      break
    case 'agent_status':
      snapshot.activities = [
        ...(snapshot.activities ?? []),
        event.payload as AgentActivity,
      ]
      break
    case 'requirement_form':
      snapshot.requirementForm = event.payload as RequirementFormPayload
      snapshot.formSubmitted ??= false
      break
    case 'review_report':
      snapshot.reviewReport = JSON.stringify(event.payload)
      break
    case 'itinerary_skeleton':
      snapshot.itinerarySkeleton = event.payload as ItinerarySkeleton
      break
    case 'itinerary_day': {
      const day = event.payload as ItineraryDaySection
      const days = snapshot.itineraryDays ?? []
      const old = days.find((d) => d.day === day.day)
      if (!old || old.version < day.version)
        snapshot.itineraryDays = [
          ...days.filter((d) => d.day !== day.day),
          day,
        ].sort((a, b) => a.day - b.day)
      break
    }
    case 'terminal': {
      const terminal = event.payload as Terminal
      snapshot.content = terminal.content
      snapshot.terminal = terminal
      status = terminal.status
      assistantMessageId = terminal.assistantMessageId
      outcome = terminal.outcome
      break
    }
  }
  return {
    ...view,
    status,
    assistantMessageId,
    outcome,
    snapshot,
    snapshotSeq: event.seq,
    lastSeq: Math.max(view.lastSeq, event.seq),
  }
}
