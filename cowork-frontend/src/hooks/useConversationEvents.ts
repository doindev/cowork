import { useCallback, useEffect, useMemo, useState } from 'react'
import { useQueryClient, type QueryClient } from '@tanstack/react-query'
import type {
  ActivityEvent,
  AgentActivity,
  AgentStatusEvent,
  CommitView,
  ConversationView,
  IdleStateView,
  MessageView,
  PartialEvent,
  PhaseInfo,
  ProposalView,
  RoundLimitEvent,
  SpendEvent,
} from '../api'

export interface ActivityEntry {
  tool: string
  summary: string
}

export interface ConversationEventsState {
  /** Live agent-status map (agent name -> 'thinking' | 'idle'). */
  agentStatuses: Record<string, AgentActivity>
  /** Streaming reply text per busy agent name (latest wins). */
  partials: Record<string, string>
  /** Tool-call activity per busy agent name, in arrival order. */
  activities: Record<string, ActivityEntry[]>
  /** Dropped agent names from the last round-limit event, or null when no banner. */
  roundLimit: string[] | null
  clearRoundLimit: () => void
  /** Why the agents are idle, or null when they are not. */
  idleState: IdleStateView | null
  setIdleState: (state: IdleStateView | null) => void
}

function withoutKey<T>(map: Record<string, T>, key: string): Record<string, T> {
  if (!(key in map)) return map
  const next = { ...map }
  delete next[key]
  return next
}

/**
 * Cap on cached messages per conversation. The oldest are dropped on append —
 * they stay reachable through the `before` cursor (ChatViewer re-fetches them
 * when the user scrolls back up).
 */
const MESSAGE_CACHE_MAX = 600

/** Append a message to the cache for a conversation, deduplicating by id. */
export function appendMessageToCache(
  queryClient: QueryClient,
  conversationId: string,
  message: MessageView,
): void {
  queryClient.setQueryData<MessageView[]>(['messages', conversationId], (old) => {
    const list = old ?? []
    if (list.some((m) => m.id === message.id)) return list
    const next = [...list, message]
    return next.length > MESSAGE_CACHE_MAX ? next.slice(next.length - MESSAGE_CACHE_MAX) : next
  })
}

/** Prepend an older page of messages (already sorted ascending), deduplicating by id. */
export function prependMessagesToCache(
  queryClient: QueryClient,
  conversationId: string,
  older: MessageView[],
): void {
  queryClient.setQueryData<MessageView[]>(['messages', conversationId], (old) => {
    const list = old ?? []
    const known = new Set(list.map((m) => m.id))
    const fresh = older.filter((m) => !known.has(m.id))
    return fresh.length === 0 ? list : [...fresh, ...list]
  })
}

/** Insert or replace a proposal in the cache for a conversation. */
export function upsertProposalInCache(
  queryClient: QueryClient,
  conversationId: string,
  proposal: ProposalView,
): void {
  queryClient.setQueryData<ProposalView[]>(['proposals', conversationId], (old) => {
    const list = old ?? []
    const idx = list.findIndex((p) => p.id === proposal.id)
    if (idx === -1) return [...list, proposal]
    const next = [...list]
    next[idx] = proposal
    return next
  })
}

/** Merge phase info into the conversation caches (detail + list). */
export function applyPhaseToCache(
  queryClient: QueryClient,
  conversationId: string,
  info: PhaseInfo,
): void {
  queryClient.setQueryData<ConversationView>(['conversation', conversationId], (old) =>
    old
      ? {
          ...old,
          phase: info.phase,
          projectId: info.projectId ?? old.projectId,
          workspacePath: info.workspacePath ?? old.workspacePath,
        }
      : old,
  )
  queryClient.setQueryData<ConversationView[]>(['conversations'], (old) =>
    old?.map((c) => (c.id === conversationId ? { ...c, phase: info.phase } : c)),
  )
}

/**
 * Opens one EventSource for the given conversation and routes named events into
 * the TanStack Query caches. Reconnects with a small exponential backoff on
 * error. Returns live per-agent state (statuses, streaming partials, activity)
 * plus round-limit banner state.
 */
export function useConversationEvents(conversationId: string | null): ConversationEventsState {
  const queryClient = useQueryClient()
  const [agentStatuses, setAgentStatuses] = useState<Record<string, AgentActivity>>({})
  const [partials, setPartials] = useState<Record<string, string>>({})
  const [activities, setActivities] = useState<Record<string, ActivityEntry[]>>({})
  const [roundLimit, setRoundLimit] = useState<string[] | null>(null)
  const [idleState, setIdleState] = useState<IdleStateView | null>(null)

  const clearRoundLimit = useCallback(() => setRoundLimit(null), [])

  useEffect(() => {
    setAgentStatuses({})
    setPartials({})
    setActivities({})
    setRoundLimit(null)
    setIdleState(null)
    if (!conversationId) return

    let source: EventSource | null = null
    let reconnectTimer: number | undefined
    let attempt = 0
    let disposed = false

    const parse = <T,>(event: MessageEvent): T | null => {
      try {
        return JSON.parse(event.data as string) as T
      } catch {
        return null
      }
    }

    const connect = () => {
      if (disposed) return
      source = new EventSource(`/api/conversations/${conversationId}/events`)

      source.onopen = () => {
        // After a reconnect, events emitted while disconnected are gone —
        // refetch so the caches backfill anything missed.
        if (attempt > 0) {
          void queryClient.invalidateQueries({ queryKey: ['messages', conversationId] })
          void queryClient.invalidateQueries({ queryKey: ['proposals', conversationId] })
          void queryClient.invalidateQueries({ queryKey: ['conversation', conversationId] })
        }
        attempt = 0
      }

      source.addEventListener('message', (event) => {
        const message = parse<MessageView>(event)
        if (message) {
          appendMessageToCache(queryClient, conversationId, message)
          // The agent's final message arrived — drop its streaming state.
          if (message.senderName) {
            setPartials((prev) => withoutKey(prev, message.senderName))
            setActivities((prev) => withoutKey(prev, message.senderName))
          }
        }
      })

      source.addEventListener('proposal', (event) => {
        const proposal = parse<ProposalView>(event)
        if (proposal) upsertProposalInCache(queryClient, conversationId, proposal)
      })

      source.addEventListener('phase', (event) => {
        const info = parse<PhaseInfo>(event)
        if (info) applyPhaseToCache(queryClient, conversationId, info)
      })

      source.addEventListener('agent-status', (event) => {
        const status = parse<AgentStatusEvent>(event)
        if (status) {
          setAgentStatuses((prev) => ({ ...prev, [status.name]: status.status }))
          if (status.status === 'idle') {
            setPartials((prev) => withoutKey(prev, status.name))
            setActivities((prev) => withoutKey(prev, status.name))
          }
        }
      })

      source.addEventListener('partial', (event) => {
        const partial = parse<PartialEvent>(event)
        if (partial && partial.name) {
          setPartials((prev) => ({ ...prev, [partial.name]: partial.text }))
        }
      })

      source.addEventListener('activity', (event) => {
        const activity = parse<ActivityEvent>(event)
        if (activity && activity.name) {
          setActivities((prev) => ({
            ...prev,
            [activity.name]: [
              ...(prev[activity.name] ?? []),
              { tool: activity.tool, summary: activity.summary },
            ],
          }))
        }
      })

      source.addEventListener('commit', (event) => {
        const commit = parse<Partial<CommitView>>(event)
        if (commit) {
          void queryClient.invalidateQueries({ queryKey: ['commits', conversationId] })
        }
      })

      source.addEventListener('spend', (event) => {
        const spend = parse<SpendEvent>(event)
        if (spend) {
          queryClient.setQueryData<ConversationView>(['conversation', conversationId], (old) =>
            old
              ? {
                  ...old,
                  spentUsd: spend.spentUsd,
                  budgetUsd: spend.budgetUsd < 0 ? null : spend.budgetUsd,
                }
              : old,
          )
        }
      })

      source.addEventListener('task', (event) => {
        if (event.data != null) {
          void queryClient.invalidateQueries({ queryKey: ['tasks', conversationId] })
        }
      })

      source.addEventListener('round-limit', (event) => {
        const info = parse<RoundLimitEvent>(event)
        if (info) setRoundLimit(Array.isArray(info.dropped) ? info.dropped : [])
      })

      source.addEventListener('doc', (event) => {
        if (event.data != null) {
          void queryClient.invalidateQueries({ queryKey: ['docs', conversationId] })
        }
      })

      source.addEventListener('idle-state', (event) => {
        const state = parse<IdleStateView>(event)
        if (state) setIdleState(state.reason ? state : null)
      })

      source.onerror = () => {
        source?.close()
        source = null
        if (disposed) return
        attempt += 1
        const delay = Math.min(1000 * 2 ** Math.min(attempt - 1, 4), 15000)
        reconnectTimer = window.setTimeout(connect, delay)
      }
    }

    connect()

    return () => {
      disposed = true
      if (reconnectTimer !== undefined) window.clearTimeout(reconnectTimer)
      source?.close()
    }
  }, [conversationId, queryClient])

  return useMemo(
    () => ({ agentStatuses, partials, activities, roundLimit, clearRoundLimit, idleState, setIdleState }),
    [agentStatuses, partials, activities, roundLimit, clearRoundLimit, idleState],
  )
}
