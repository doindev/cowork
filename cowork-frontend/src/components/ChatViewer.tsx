import { Fragment, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  cancelTurn,
  getMessages,
  type AgentActivity,
  type MessageView,
  type ParticipantView,
} from '../api'
import { prependMessagesToCache, type ActivityEntry } from '../hooks/useConversationEvents'
import { formatCost, formatTime, nameColor } from '../utils'

interface Props {
  conversationId: string
  messages: MessageView[]
  loading: boolean
  agentStatuses: Record<string, AgentActivity>
  partials: Record<string, string>
  activities: Record<string, ActivityEntry[]>
  participants: ParticipantView[]
  /** Show only the user's own messages and messages mentioning @user. */
  filterUser: boolean
}

const MENTION_PATTERN = /(@[A-Za-z0-9_][A-Za-z0-9_.-]*)/g

function MessageContent({ content }: { content: string }) {
  const parts = content.split(MENTION_PATTERN)
  return (
    <>
      {parts.map((part, i) =>
        part.startsWith('@') ? (
          <span key={i} className="mention">
            {part}
          </span>
        ) : (
          <Fragment key={i}>{part}</Fragment>
        ),
      )}
    </>
  )
}

/** Defensively parse a MessageView.activity JSON string into tool-call entries. */
function parseActivity(raw: string): ActivityEntry[] {
  try {
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter(
      (e): e is ActivityEntry =>
        e != null &&
        typeof e === 'object' &&
        typeof (e as ActivityEntry).tool === 'string' &&
        typeof (e as ActivityEntry).summary === 'string',
    )
  } catch {
    return []
  }
}

function ActivityDrawer({ activity }: { activity: string }) {
  const [open, setOpen] = useState(false)
  const entries = useMemo(() => parseActivity(activity), [activity])
  if (entries.length === 0) return null
  return (
    <div className="msg-activity">
      <button className="msg-activity-toggle" onClick={() => setOpen((o) => !o)}>
        <span className={`chevron${open ? ' open' : ''}`}>▸</span>
        ⚙ {entries.length} tool call{entries.length === 1 ? '' : 's'}
      </button>
      {open && (
        <div className="msg-activity-list">
          {entries.map((e, i) => (
            <div className="msg-activity-row" key={i}>
              <span className="msg-activity-tool">{e.tool}</span>
              <span className="msg-activity-summary">{e.summary}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

const PIN_THRESHOLD_PX = 48
/** Max message elements kept in the DOM at once. */
const MAX_WINDOW = 150
/** How many messages the window slides by when the user reaches an edge. */
const WINDOW_STEP = 50
/** Distance from the top edge that triggers sliding up / fetching older. */
const TOP_EDGE_PX = 200
/** Distance from the bottom edge that triggers sliding back down. */
const BOTTOM_EDGE_PX = 200
/** Server page size when fetching older history. */
const OLDER_PAGE = 100

const USER_NAME = 'user'
const USER_MENTION = /@user\b/i

/** Messages that are the user's own, or that mention @user. */
function concernsUser(m: MessageView) {
  return (
    m.senderName === USER_NAME || m.mentions.includes(USER_NAME) || USER_MENTION.test(m.content)
  )
}

export default function ChatViewer({
  conversationId,
  messages,
  loading,
  agentStatuses,
  partials,
  activities,
  participants,
  filterUser,
}: Props) {
  const queryClient = useQueryClient()
  const listRef = useRef<HTMLDivElement>(null)
  const pinnedRef = useRef(true)
  const [pinned, setPinned] = useState(true)

  // Window end anchored by message id; null = follow the latest message.
  const [anchorId, setAnchorId] = useState<string | null>(null)
  // Older-history fetch state, reset per conversation.
  const olderRef = useRef({ loading: false, exhausted: false })
  const [loadingOlder, setLoadingOlder] = useState(false)
  // When set, restore this message's on-screen position after the next render.
  const preserveRef = useRef<{ id: string; top: number } | null>(null)

  const shown = useMemo(
    () => (filterUser ? messages.filter(concernsUser) : messages),
    [messages, filterUser],
  )

  let endIndex: number
  if (anchorId === null) {
    endIndex = shown.length
  } else {
    const i = shown.findIndex((m) => m.id === anchorId)
    // Anchor gone (cache trimmed / filter changed): stay near the oldest loaded.
    endIndex = i === -1 ? Math.min(MAX_WINDOW, shown.length) : i + 1
  }
  const startIndex = Math.max(0, endIndex - MAX_WINDOW)
  const windowed = shown.slice(startIndex, endIndex)

  // Mirrors for use inside the scroll handler without stale closures.
  const stateRef = useRef({ startIndex, endIndex, shown, anchorId })
  stateRef.current = { startIndex, endIndex, shown, anchorId }
  const fullListRef = useRef(messages)
  fullListRef.current = messages

  const thinkingAgents = useMemo(
    () =>
      Object.entries(agentStatuses)
        .filter(([, status]) => status === 'thinking')
        .map(([name]) => name)
        .sort(),
    [agentStatuses],
  )

  const cancelMutation = useMutation({
    mutationFn: (participantId: string) => cancelTurn(conversationId, participantId),
  })

  // A rough size of the streaming content, so the view keeps following growth.
  const streamSize = useMemo(
    () =>
      Object.values(partials).reduce((n, t) => n + t.length, 0) +
      Object.values(activities).reduce((n, list) => n + list.length, 0),
    [partials, activities],
  )

  const scrollToBottom = () => {
    const el = listRef.current
    if (el) el.scrollTop = el.scrollHeight
  }

  // Reset scroll, window, and history state when switching conversations.
  useLayoutEffect(() => {
    pinnedRef.current = true
    setPinned(true)
    setAnchorId(null)
    olderRef.current = { loading: false, exhausted: false }
    preserveRef.current = null
    scrollToBottom()
  }, [conversationId])

  // When the filter toggles, re-pin to the latest matching messages.
  useLayoutEffect(() => {
    pinnedRef.current = true
    setPinned(true)
    setAnchorId(null)
    scrollToBottom()
  }, [filterUser])

  // Follow new content while pinned to the bottom.
  useEffect(() => {
    if (pinnedRef.current) scrollToBottom()
  }, [messages.length, thinkingAgents.length, loading, streamSize, filterUser])

  // After prepending content above the viewport, keep the previously visible
  // message where it was so the view doesn't jump.
  useLayoutEffect(() => {
    const keep = preserveRef.current
    if (!keep) return
    preserveRef.current = null
    const el = listRef.current
    if (!el) return
    const node = el.querySelector<HTMLElement>(`[data-msg-id="${keep.id}"]`)
    if (node) el.scrollTop += node.getBoundingClientRect().top - keep.top
  })

  /** Remember the first rendered message's position before the window shifts. */
  const captureScrollAnchor = () => {
    const el = listRef.current
    if (!el) return
    const first = el.querySelector<HTMLElement>('[data-msg-id]')
    if (first?.dataset.msgId) {
      preserveRef.current = { id: first.dataset.msgId, top: first.getBoundingClientRect().top }
    }
  }

  const loadOlder = () => {
    if (olderRef.current.loading || olderRef.current.exhausted) return
    const oldest = fullListRef.current[0]
    if (!oldest) return
    olderRef.current.loading = true
    setLoadingOlder(true)
    getMessages(conversationId, OLDER_PAGE, oldest.createdAt)
      .then((older) => {
        if (older.length < OLDER_PAGE) olderRef.current.exhausted = true
        if (older.length > 0) {
          captureScrollAnchor()
          prependMessagesToCache(queryClient, conversationId, older)
          // If following the latest, pin the window so it doesn't swallow the
          // prepended page all at once.
          const s = stateRef.current
          if (s.anchorId === null && s.shown.length > 0) {
            setAnchorId(s.shown[s.shown.length - 1].id)
          }
        }
      })
      .catch(() => {
        /* transient; the user can scroll again to retry */
      })
      .finally(() => {
        olderRef.current.loading = false
        setLoadingOlder(false)
      })
  }

  const onScroll = () => {
    const el = listRef.current
    if (!el) return
    const fromBottom = el.scrollHeight - el.scrollTop - el.clientHeight
    const atBottom = fromBottom < PIN_THRESHOLD_PX
    const { startIndex: start, endIndex: end, shown: list, anchorId: anchor } = stateRef.current

    pinnedRef.current = atBottom && anchor === null
    setPinned(pinnedRef.current)

    if (el.scrollTop < TOP_EDGE_PX) {
      if (start > 0) {
        // Slide the window up over already-loaded messages.
        const newEnd = Math.max(MAX_WINDOW, end - WINDOW_STEP)
        if (newEnd < end) {
          captureScrollAnchor()
          setAnchorId(list[newEnd - 1].id)
        }
      } else {
        loadOlder()
      }
    } else if (anchor !== null && fromBottom < BOTTOM_EDGE_PX) {
      // Slide the window back down toward the latest.
      const newEnd = end + WINDOW_STEP
      if (newEnd >= list.length) {
        setAnchorId(null)
      } else {
        setAnchorId(list[newEnd - 1].id)
      }
    }
  }

  const jumpToLatest = () => {
    pinnedRef.current = true
    setPinned(true)
    setAnchorId(null)
    scrollToBottom()
  }


  return (
    <div className="chat-viewer">
      <div className="chat-scroll" ref={listRef} onScroll={onScroll}>
        {loading && <div className="chat-note">Loading messages…</div>}
        {loadingOlder && <div className="chat-note slim">Loading older messages…</div>}
        {!loading && messages.length === 0 && (
          <div className="empty-state">
            <div className="empty-title">No messages yet</div>
            <div className="empty-sub">Say something to kick off the discussion.</div>
          </div>
        )}
        {!loading && messages.length > 0 && filterUser && shown.length === 0 && (
          <div className="empty-state">
            <div className="empty-title">No matching messages</div>
            <div className="empty-sub">
              None of the loaded messages are yours or mention @user.
            </div>
          </div>
        )}

        {windowed.map((message) => {
          if (message.kind === 'CHAT') {
            return (
              <div className="msg-row" key={message.id} data-msg-id={message.id}>
                <span
                  className="avatar-dot"
                  style={{ background: nameColor(message.senderName) }}
                />
                <div className="msg-main">
                  <div className="msg-head">
                    <span className="msg-sender" style={{ color: nameColor(message.senderName) }}>
                      {message.senderName}
                    </span>
                    {message.round != null && (
                      <span className="msg-round">round {message.round}</span>
                    )}
                    <span className="msg-time">{formatTime(message.createdAt)}</span>
                    {message.costUsd != null && (
                      <span className="cost-chip">{formatCost(message.costUsd)}</span>
                    )}
                  </div>
                  <div className="msg-content">
                    <MessageContent content={message.content} />
                  </div>
                  {message.activity != null && <ActivityDrawer activity={message.activity} />}
                </div>
              </div>
            )
          }

          if (message.kind === 'PROPOSAL') {
            return (
              <div className="msg-proposal-card" key={message.id} data-msg-id={message.id}>
                <div className="msg-proposal-head">
                  <span className="badge badge-proposal">Proposal</span>
                  <span className="msg-proposal-by">by {message.senderName}</span>
                  <span className="msg-time">{formatTime(message.createdAt)}</span>
                </div>
                <div className="msg-content">
                  <MessageContent content={message.content} />
                </div>
              </div>
            )
          }

          // SYSTEM / VOTE / PHASE → centered muted line
          return (
            <div className="msg-system" key={message.id} data-msg-id={message.id}>
              <span className="msg-system-text">
                {message.kind !== 'SYSTEM' && (
                  <span className="msg-system-kind">{message.kind.toLowerCase()} · </span>
                )}
                <MessageContent content={message.content} />
              </span>
            </div>
          )
        })}

        {thinkingAgents.map((name) => {
          const partial = partials[name]
          const recent = (activities[name] ?? []).slice(-3)
          const participantId = participants.find((p) => p.displayName === name)?.id
          const cancelling =
            cancelMutation.isPending && cancelMutation.variables === participantId
          return (
            <div className="msg-row thinking" key={`thinking-${name}`}>
              <span className="avatar-dot" style={{ background: nameColor(name) }} />
              <div className="msg-main">
                <div className="msg-head">
                  <span className="msg-sender" style={{ color: nameColor(name) }}>
                    {name}
                  </span>
                  {participantId && (
                    <button
                      className="cancel-turn-btn"
                      disabled={cancelling}
                      title="Cancel this agent's turn"
                      onClick={() => cancelMutation.mutate(participantId)}
                    >
                      {cancelling ? 'Cancelling…' : 'Cancel'}
                    </button>
                  )}
                </div>
                <div className="thinking-indicator">
                  thinking
                  <span className="tdot" />
                  <span className="tdot" />
                  <span className="tdot" />
                </div>
                {partial && <pre className="partial-text">{partial}</pre>}
                {recent.length > 0 && (
                  <div className="activity-ticker">
                    {recent.map((a, i) => (
                      <div className="activity-line" key={i}>
                        ⚙ {a.tool} · {a.summary}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          )
        })}
      </div>

      {!pinned && (
        <button className="jump-chip" onClick={jumpToLatest}>
          ↓ Jump to latest
        </button>
      )}
    </div>
  )
}
