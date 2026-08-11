import { Fragment, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { cancelTurn, type AgentActivity, type MessageView, type ParticipantView } from '../api'
import type { ActivityEntry } from '../hooks/useConversationEvents'
import { formatCost, formatTime, nameColor } from '../utils'

interface Props {
  conversationId: string
  messages: MessageView[]
  loading: boolean
  agentStatuses: Record<string, AgentActivity>
  partials: Record<string, string>
  activities: Record<string, ActivityEntry[]>
  participants: ParticipantView[]
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

export default function ChatViewer({
  conversationId,
  messages,
  loading,
  agentStatuses,
  partials,
  activities,
  participants,
}: Props) {
  const listRef = useRef<HTMLDivElement>(null)
  const pinnedRef = useRef(true)
  const [pinned, setPinned] = useState(true)

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

  // Reset to bottom when switching conversations.
  useLayoutEffect(() => {
    pinnedRef.current = true
    setPinned(true)
    scrollToBottom()
  }, [conversationId])

  // Follow new content while pinned to the bottom.
  useEffect(() => {
    if (pinnedRef.current) scrollToBottom()
  }, [messages.length, thinkingAgents.length, loading, streamSize])

  const onScroll = () => {
    const el = listRef.current
    if (!el) return
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < PIN_THRESHOLD_PX
    pinnedRef.current = atBottom
    setPinned(atBottom)
  }

  const jumpToLatest = () => {
    pinnedRef.current = true
    setPinned(true)
    scrollToBottom()
  }

  return (
    <div className="chat-viewer">
      <div className="chat-scroll" ref={listRef} onScroll={onScroll}>
        {loading && <div className="chat-note">Loading messages…</div>}
        {!loading && messages.length === 0 && (
          <div className="empty-state">
            <div className="empty-title">No messages yet</div>
            <div className="empty-sub">Say something to kick off the discussion.</div>
          </div>
        )}

        {messages.map((message) => {
          if (message.kind === 'CHAT') {
            return (
              <div className="msg-row" key={message.id}>
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
              <div className="msg-proposal-card" key={message.id}>
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
            <div className="msg-system" key={message.id}>
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
