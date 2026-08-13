import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  browseDirs,
  createConversation,
  getAgents,
  type ConversationView,
  type DirListing,
  type VoteMode,
} from '../api'

interface Props {
  onClose: () => void
  onCreated: (conversation: ConversationView) => void
}

/** Inline server-backed directory picker for the optional workspace field. */
function DirBrowser({
  initialPath,
  onPick,
  onCancel,
}: {
  initialPath: string
  onPick: (path: string) => void
  onCancel: () => void
}) {
  const [listing, setListing] = useState<DirListing | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const navigate = (path?: string) => {
    setLoading(true)
    setError(null)
    browseDirs(path)
      .then(setListing)
      .catch((e) => {
        setError((e as Error).message || 'Could not read directory.')
        // Fall back to the roots so the user is never stuck.
        if (path) browseDirs().then(setListing).catch(() => {})
      })
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    navigate(initialPath.trim() || undefined)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div className="dir-browser">
      <div className="dir-browser-head">
        <span className="dir-browser-path" title={listing?.path ?? ''}>
          {listing?.path ?? 'Select a drive or folder'}
        </span>
      </div>
      <div className="dir-browser-list">
        {loading && <div className="side-note">Loading…</div>}
        {error && <div className="form-error">{error}</div>}
        {!loading && listing && (
          <>
            {listing.path !== null && (
              <button className="dir-row dir-up" onClick={() => navigate(listing.parent ?? undefined)}>
                ↰ ..
              </button>
            )}
            {listing.dirs.map((d) => (
              <button className="dir-row" key={d.path} title={d.path} onClick={() => navigate(d.path)}>
                🗀 {d.name}
              </button>
            ))}
            {listing.path !== null && listing.dirs.length === 0 && (
              <div className="side-note">No subfolders.</div>
            )}
          </>
        )}
      </div>
      <div className="dir-browser-actions">
        <button className="btn btn-tiny" onClick={onCancel}>
          Cancel
        </button>
        <button
          className="btn btn-tiny btn-primary"
          disabled={!listing?.path}
          onClick={() => listing?.path && onPick(listing.path)}
        >
          Use this folder
        </button>
      </div>
    </div>
  )
}

export default function NewConversationModal({ onClose, onCreated }: Props) {
  const queryClient = useQueryClient()
  const agentsQuery = useQuery({ queryKey: ['agents'], queryFn: getAgents })

  const [title, setTitle] = useState('')
  const [selectedAgents, setSelectedAgents] = useState<string[]>([])
  const [voteMode, setVoteMode] = useState<VoteMode>('MAJORITY')
  const [userVotes, setUserVotes] = useState(true)
  const [maxRounds, setMaxRounds] = useState(0)
  const [workspacePath, setWorkspacePath] = useState('')
  const [browsing, setBrowsing] = useState(false)

  const createMutation = useMutation({
    mutationFn: createConversation,
    onSuccess: (conversation) => {
      queryClient.invalidateQueries({ queryKey: ['conversations'] })
      onCreated(conversation)
    },
  })

  const toggleAgent = (id: string) => {
    setSelectedAgents((prev) =>
      prev.includes(id) ? prev.filter((a) => a !== id) : [...prev, id],
    )
  }

  const canCreate =
    title.trim().length > 0 && selectedAgents.length > 0 && !createMutation.isPending

  const submit = () => {
    if (!canCreate) return
    createMutation.mutate({
      title: title.trim(),
      voteMode,
      userVotes,
      maxAgentRounds: Math.max(0, maxRounds),
      agentIds: selectedAgents,
      workspacePath: workspacePath.trim() || undefined,
    })
  }

  return (
    <div className="modal-overlay">
      <div className="modal" role="dialog" aria-modal="true" aria-label="New conversation">
        <div className="modal-header">
          <h2>New conversation</h2>
        </div>

        <div className="modal-body">
          <label className="field">
            <span className="field-label">Title</span>
            <input
              autoFocus
              type="text"
              value={title}
              placeholder="e.g. Build the billing service"
              onChange={(e) => setTitle(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') submit()
              }}
            />
          </label>

          <div className="field">
            <span className="field-label">Agents</span>
            <div className="agent-picker">
              {agentsQuery.isLoading && <div className="side-note">Loading agents…</div>}
              {agentsQuery.isError && (
                <div className="side-note">Could not load agents.</div>
              )}
              {agentsQuery.data?.length === 0 && (
                <div className="side-note">No agents are registered.</div>
              )}
              {agentsQuery.data?.map((agent) => (
                <label
                  key={agent.id}
                  className={`agent-option${agent.enabled ? '' : ' disabled'}${
                    selectedAgents.includes(agent.id) ? ' checked' : ''
                  }`}
                >
                  <input
                    type="checkbox"
                    disabled={!agent.enabled}
                    checked={selectedAgents.includes(agent.id)}
                    onChange={() => toggleAgent(agent.id)}
                  />
                  <span className="agent-option-main">
                    <span className="agent-option-name">
                      {agent.name}
                      <span className="badge badge-muted">{agent.cliType}</span>
                      {agent.model && <span className="badge badge-model">{agent.model}</span>}
                      {!agent.enabled && <span className="badge badge-muted">disabled</span>}
                    </span>
                    {agent.description && (
                      <span className="agent-option-desc">{agent.description}</span>
                    )}
                  </span>
                </label>
              ))}
            </div>
          </div>

          <div className="field">
            <span className="field-label">Project workspace (optional)</span>
            <div className="workspace-row">
              <input
                type="text"
                value={workspacePath}
                placeholder="Existing folder the agents should work in"
                onChange={(e) => setWorkspacePath(e.target.value)}
              />
              <button className="btn" onClick={() => setBrowsing((v) => !v)}>
                {browsing ? 'Close' : 'Browse…'}
              </button>
            </div>
            {browsing && (
              <DirBrowser
                initialPath={workspacePath}
                onPick={(path) => {
                  setWorkspacePath(path)
                  setBrowsing(false)
                }}
                onCancel={() => setBrowsing(false)}
              />
            )}
            <span className="field-hint">
              When set, agents work only inside this directory and are instructed not to modify or
              create anything outside it. Leave empty for a managed workspace.
            </span>
          </div>

          <div className="field-row">
            <div className="field">
              <span className="field-label">Vote mode</span>
              <div className="radio-row">
                <label className="radio">
                  <input
                    type="radio"
                    name="voteMode"
                    checked={voteMode === 'MAJORITY'}
                    onChange={() => setVoteMode('MAJORITY')}
                  />
                  Majority
                </label>
                <label className="radio">
                  <input
                    type="radio"
                    name="voteMode"
                    checked={voteMode === 'UNANIMOUS'}
                    onChange={() => setVoteMode('UNANIMOUS')}
                  />
                  Unanimous
                </label>
              </div>
            </div>
            <div className="field">
              <span className="field-label">Max agent rounds (0 = unlimited)</span>
              <input
                className="num-input"
                type="number"
                min={0}
                value={maxRounds}
                onChange={(e) => setMaxRounds(Math.max(0, Number(e.target.value) || 0))}
              />
            </div>
          </div>

          <label className="check">
            <input
              type="checkbox"
              checked={userVotes}
              onChange={(e) => setUserVotes(e.target.checked)}
            />
            I vote on proposals too
          </label>

          {createMutation.isError && (
            <div className="form-error">
              {(createMutation.error as Error).message || 'Failed to create conversation.'}
            </div>
          )}
        </div>

        <div className="modal-footer">
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-primary" disabled={!canCreate} onClick={submit}>
            {createMutation.isPending ? 'Creating…' : 'Create conversation'}
          </button>
        </div>
      </div>
    </div>
  )
}
