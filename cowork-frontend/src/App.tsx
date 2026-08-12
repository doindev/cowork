import { useCallback, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { continueRounds, getConversation, getConversations, getMessages } from './api'
import { useConversationEvents } from './hooks/useConversationEvents'
import { formatUsd2 } from './utils'
import AgentManagerModal from './components/AgentManagerModal'
import ConversationList from './components/ConversationList'
import NewConversationModal from './components/NewConversationModal'
import PhaseBanner from './components/PhaseBanner'
import ChatViewer from './components/ChatViewer'
import MessageInput from './components/MessageInput'
import PanelModal from './components/PanelModal'
import ProposalPanel from './components/ProposalPanel'

const PANEL_MIN_WIDTH = 240
const PANEL_MAX_WIDTH = 640

function clampPanelWidth(w: number) {
  return Math.min(PANEL_MAX_WIDTH, Math.max(PANEL_MIN_WIDTH, Math.round(w)))
}

export default function App() {
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [showNewModal, setShowNewModal] = useState(false)
  const [showAgentManager, setShowAgentManager] = useState(false)
  const [showArchived, setShowArchived] = useState(false)
  const [sidebarCollapsed, setSidebarCollapsed] = useState(
    () => localStorage.getItem('cowork.sidebarCollapsed') === '1',
  )
  const [rightCollapsed, setRightCollapsed] = useState(
    () => localStorage.getItem('cowork.rightPanelCollapsed') === '1',
  )
  const [rightWidth, setRightWidth] = useState(() => {
    const stored = Number(localStorage.getItem('cowork.rightPanelWidth'))
    return clampPanelWidth(Number.isFinite(stored) && stored > 0 ? stored : 340)
  })
  const [draggingDivider, setDraggingDivider] = useState(false)
  const [showPanelModal, setShowPanelModal] = useState(false)

  const toggleSidebar = () =>
    setSidebarCollapsed((v) => {
      const next = !v
      localStorage.setItem('cowork.sidebarCollapsed', next ? '1' : '0')
      return next
    })

  const toggleRightPanel = () =>
    setRightCollapsed((v) => {
      const next = !v
      localStorage.setItem('cowork.rightPanelCollapsed', next ? '1' : '0')
      return next
    })

  const startDividerDrag = useCallback(
    (e: React.MouseEvent) => {
      e.preventDefault()
      const startX = e.clientX
      const startWidth = rightWidth
      setDraggingDivider(true)
      const widthAt = (clientX: number) => clampPanelWidth(startWidth + (startX - clientX))
      const onMove = (ev: MouseEvent) => setRightWidth(widthAt(ev.clientX))
      const onUp = (ev: MouseEvent) => {
        window.removeEventListener('mousemove', onMove)
        window.removeEventListener('mouseup', onUp)
        setDraggingDivider(false)
        const final = widthAt(ev.clientX)
        setRightWidth(final)
        localStorage.setItem('cowork.rightPanelWidth', String(final))
      }
      window.addEventListener('mousemove', onMove)
      window.addEventListener('mouseup', onUp)
    },
    [rightWidth],
  )

  const conversationsQuery = useQuery({
    queryKey: ['conversations', showArchived ? 'ARCHIVED' : 'ACTIVE'],
    queryFn: () => getConversations(showArchived ? 'ARCHIVED' : 'ACTIVE'),
  })

  const conversationQuery = useQuery({
    queryKey: ['conversation', selectedId],
    queryFn: () => getConversation(selectedId!),
    enabled: selectedId !== null,
  })

  const messagesQuery = useQuery({
    queryKey: ['messages', selectedId],
    queryFn: () => getMessages(selectedId!),
    enabled: selectedId !== null,
  })

  const { agentStatuses, partials, activities, roundLimit, clearRoundLimit } =
    useConversationEvents(selectedId)

  const continueMutation = useMutation({
    mutationFn: (conversationId: string) => continueRounds(conversationId),
    onSuccess: clearRoundLimit,
  })

  const conversation = selectedId !== null ? (conversationQuery.data ?? null) : null

  const spent = conversation?.spentUsd ?? 0
  const budget = conversation?.budgetUsd ?? null
  const overBudget = budget != null && spent >= budget

  return (
    <div
      className={`app${sidebarCollapsed ? ' sidebar-collapsed' : ''}${draggingDivider ? ' dragging' : ''}`}
      style={{
        gridTemplateColumns: `${sidebarCollapsed ? '56px' : '280px'} minmax(0, 1fr)${
          rightCollapsed ? ' 48px' : ` 6px ${rightWidth}px`
        }`,
      }}
    >
      <aside className="sidebar">
        {sidebarCollapsed ? (
          <>
            <div className="brand brand-mini" title="cowork">
              <span className="brand-mark" />
            </div>
            <button className="rail-btn" title="Expand sidebar" onClick={toggleSidebar}>
              »
            </button>
            <button
              className="rail-btn rail-new"
              title="New conversation"
              onClick={() => setShowNewModal(true)}
            >
              +
            </button>
            <div className="rail-conv-list">
              {(conversationsQuery.data ?? []).map((c) => (
                <button
                  key={c.id}
                  className={`rail-conv${c.id === selectedId ? ' selected' : ''}`}
                  title={c.title || 'Untitled'}
                  onClick={() => setSelectedId(c.id)}
                >
                  {(c.title || 'U').trim().charAt(0).toUpperCase() || 'U'}
                </button>
              ))}
            </div>
            <button
              className="rail-btn rail-footer"
              title="Manage agents"
              onClick={() => setShowAgentManager(true)}
            >
              ⚙
            </button>
          </>
        ) : (
          <>
            <div className="brand">
              <span className="brand-mark" />
              <span className="brand-name">cowork</span>
              <button
                className="icon-btn brand-collapse"
                title="Collapse sidebar"
                onClick={toggleSidebar}
              >
                «
              </button>
            </div>
            <ConversationList
              conversations={conversationsQuery.data ?? []}
              selectedId={selectedId}
              loading={conversationsQuery.isLoading}
              error={conversationsQuery.isError}
              showArchived={showArchived}
              onToggleArchived={() => setShowArchived((v) => !v)}
              onSelect={setSelectedId}
              onNewConversation={() => setShowNewModal(true)}
              onArchived={(id) => {
                if (selectedId === id) setSelectedId(null)
              }}
            />
            <button className="btn sidebar-footer-btn" onClick={() => setShowAgentManager(true)}>
              ⚙ Manage agents
            </button>
          </>
        )}
      </aside>

      <main className="main">
        {selectedId === null ? (
          <div className="main-empty">
            <div className="empty-state">
              <div className="empty-title">Welcome to cowork</div>
              <div className="empty-sub">
                Select a conversation on the left, or create a new one to put your agents
                to work.
              </div>
              <button className="btn btn-primary" onClick={() => setShowNewModal(true)}>
                + New conversation
              </button>
            </div>
          </div>
        ) : conversation === null ? (
          <div className="main-empty">
            <div className="empty-state">
              {conversationQuery.isError ? (
                <>
                  <div className="empty-title">Could not load conversation</div>
                  <div className="empty-sub">
                    {(conversationQuery.error as Error)?.message}
                  </div>
                </>
              ) : (
                <div className="empty-sub">Loading conversation…</div>
              )}
            </div>
          </div>
        ) : (
          <>
            <div className="main-header">
              <h1 className="conv-heading" title={conversation.title}>
                {conversation.title || 'Untitled'}
              </h1>
              {conversation.status === 'ARCHIVED' && (
                <span className="badge badge-muted">Archived</span>
              )}
              <span
                className={`spend-chip${overBudget ? ' over' : ''}`}
                title={budget != null ? 'Spent / budget' : 'Spent so far'}
              >
                {formatUsd2(spent)}
                {budget != null && ` / ${formatUsd2(budget)}`}
              </span>
            </div>
            <PhaseBanner conversation={conversation} />
            {roundLimit !== null && (
              <div className="round-limit-banner">
                <span className="round-limit-text">
                  Agent round limit reached
                  {roundLimit.length > 0 && ` (dropped: ${roundLimit.join(', ')})`}
                </span>
                <div className="btn-row">
                  <button
                    className="btn btn-tiny"
                    disabled={continueMutation.isPending}
                    onClick={() => continueMutation.mutate(conversation.id)}
                  >
                    {continueMutation.isPending ? 'Continuing…' : 'Continue rounds'}
                  </button>
                  <button className="btn btn-tiny" onClick={clearRoundLimit}>
                    Dismiss
                  </button>
                </div>
              </div>
            )}
            <ChatViewer
              conversationId={conversation.id}
              messages={messagesQuery.data ?? []}
              loading={messagesQuery.isLoading}
              agentStatuses={agentStatuses}
              partials={partials}
              activities={activities}
              participants={conversation.participants}
            />
            <MessageInput
              conversationId={conversation.id}
              participants={conversation.participants}
              disabled={conversation.status === 'ARCHIVED'}
              onSent={clearRoundLimit}
            />
          </>
        )}
      </main>

      {!rightCollapsed && (
        <div
          className="panel-divider"
          title="Drag to resize"
          onMouseDown={startDividerDrag}
        />
      )}

      {rightCollapsed ? (
        <aside className="right-panel right-rail">
          <button className="rail-btn" title="Expand panel" onClick={toggleRightPanel}>
            «
          </button>
          <button
            className="rail-btn"
            title="Open proposals, tasks & files"
            disabled={conversation === null}
            onClick={() => setShowPanelModal(true)}
          >
            🗳
          </button>
        </aside>
      ) : (
        <aside className="right-panel">
          <div className="right-panel-controls">
            <button
              className="icon-btn"
              title="Open as full-screen view"
              disabled={conversation === null}
              onClick={() => setShowPanelModal(true)}
            >
              ⤢
            </button>
            <button className="icon-btn" title="Collapse panel" onClick={toggleRightPanel}>
              »
            </button>
          </div>
          {conversation !== null ? (
            <ProposalPanel conversation={conversation} />
          ) : (
            <div className="panel-placeholder">
              <div className="empty-state small">
                <div className="empty-title">Proposals</div>
                <div className="empty-sub">Open a conversation to see its proposals.</div>
              </div>
            </div>
          )}
        </aside>
      )}

      {showNewModal && (
        <NewConversationModal
          onClose={() => setShowNewModal(false)}
          onCreated={(created) => {
            setShowNewModal(false)
            setSelectedId(created.id)
          }}
        />
      )}

      {showAgentManager && <AgentManagerModal onClose={() => setShowAgentManager(false)} />}

      {showPanelModal && conversation !== null && (
        <PanelModal conversation={conversation} onClose={() => setShowPanelModal(false)} />
      )}
    </div>
  )
}
