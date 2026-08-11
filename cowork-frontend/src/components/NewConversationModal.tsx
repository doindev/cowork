import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createConversation, getAgents, type ConversationView, type VoteMode } from '../api'

interface Props {
  onClose: () => void
  onCreated: (conversation: ConversationView) => void
}

export default function NewConversationModal({ onClose, onCreated }: Props) {
  const queryClient = useQueryClient()
  const agentsQuery = useQuery({ queryKey: ['agents'], queryFn: getAgents })

  const [title, setTitle] = useState('')
  const [selectedAgents, setSelectedAgents] = useState<string[]>([])
  const [voteMode, setVoteMode] = useState<VoteMode>('MAJORITY')
  const [userVotes, setUserVotes] = useState(true)
  const [maxRounds, setMaxRounds] = useState(4)

  const createMutation = useMutation({
    mutationFn: createConversation,
    onSuccess: (conversation) => {
      queryClient.invalidateQueries({ queryKey: ['conversations'] })
      onCreated(conversation)
    },
  })

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

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
      maxAgentRounds: Math.max(1, maxRounds),
      agentIds: selectedAgents,
    })
  }

  return (
    <div
      className="modal-overlay"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
    >
      <div className="modal" role="dialog" aria-modal="true" aria-label="New conversation">
        <div className="modal-header">
          <h2>New conversation</h2>
          <button className="icon-btn" onClick={onClose} aria-label="Close">
            ✕
          </button>
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
              <span className="field-label">Max agent rounds</span>
              <input
                className="num-input"
                type="number"
                min={1}
                value={maxRounds}
                onChange={(e) => setMaxRounds(Number(e.target.value) || 1)}
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
