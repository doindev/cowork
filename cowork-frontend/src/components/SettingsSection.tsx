import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  addAgentToConversation,
  getAgents,
  patchConversation,
  type ConversationView,
  type PatchConversationRequest,
  type VoteMode,
} from '../api'
import { formatUsd2, nameColor } from '../utils'

interface Props {
  conversation: ConversationView
  alwaysOpen?: boolean
}

export default function SettingsSection({ conversation, alwaysOpen = false }: Props) {
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(alwaysOpen)
  const [confirmingArchive, setConfirmingArchive] = useState(false)
  const [rounds, setRounds] = useState(conversation.maxAgentRounds)
  const [budget, setBudget] = useState(
    conversation.budgetUsd != null ? String(conversation.budgetUsd) : '',
  )
  const [pasteThreshold, setPasteThreshold] = useState(conversation.pasteThreshold)
  const [agentToAdd, setAgentToAdd] = useState('')

  useEffect(() => {
    setRounds(conversation.maxAgentRounds)
  }, [conversation.maxAgentRounds])

  useEffect(() => {
    setBudget(conversation.budgetUsd != null ? String(conversation.budgetUsd) : '')
  }, [conversation.budgetUsd])

  useEffect(() => {
    setPasteThreshold(conversation.pasteThreshold)
  }, [conversation.pasteThreshold])

  const agentsQuery = useQuery({ queryKey: ['agents'], queryFn: getAgents, enabled: open })

  const patchMutation = useMutation({
    mutationFn: (req: PatchConversationRequest) => patchConversation(conversation.id, req),
    onSuccess: (updated) => {
      queryClient.setQueryData(['conversation', conversation.id], updated)
      queryClient.invalidateQueries({ queryKey: ['conversations'] })
    },
  })

  const addAgentMutation = useMutation({
    mutationFn: (agentId: string) => addAgentToConversation(conversation.id, agentId),
    onSuccess: () => {
      setAgentToAdd('')
      queryClient.invalidateQueries({ queryKey: ['conversation', conversation.id] })
      queryClient.invalidateQueries({ queryKey: ['conversations'] })
    },
  })

  const commitRounds = (value: number) => {
    const clamped = Math.max(0, Math.floor(value) || 0)
    setRounds(clamped)
    if (clamped !== conversation.maxAgentRounds) {
      patchMutation.mutate({ maxAgentRounds: clamped })
    }
  }

  const commitBudget = (raw: string) => {
    const trimmed = raw.trim()
    const value = trimmed === '' ? 0 : Number(trimmed)
    if (Number.isNaN(value) || value < 0) {
      setBudget(conversation.budgetUsd != null ? String(conversation.budgetUsd) : '')
      return
    }
    const current = conversation.budgetUsd ?? 0
    if (value !== current) {
      patchMutation.mutate({ budgetUsd: value })
    }
  }

  const commitPasteThreshold = (value: number) => {
    const clamped = Math.max(0, Math.floor(value) || 0)
    setPasteThreshold(clamped)
    if (clamped !== conversation.pasteThreshold) {
      patchMutation.mutate({ pasteThreshold: clamped })
    }
  }

  const availableAgents = (agentsQuery.data ?? []).filter(
    (a) => a.enabled && !conversation.participants.some((p) => p.agentId === a.id),
  )

  return (
    <div className="settings-section">
      {!alwaysOpen && (
        <button className="settings-toggle" onClick={() => setOpen((o) => !o)}>
          <span className={`chevron${open ? ' open' : ''}`}>▸</span>
          Conversation settings
        </button>
      )}

      {open && (
        <div className="settings-body">
          <label className="field">
            <span className="field-label">Vote mode</span>
            <select
              value={conversation.voteMode}
              disabled={patchMutation.isPending}
              onChange={(e) =>
                patchMutation.mutate({ voteMode: e.target.value as VoteMode })
              }
            >
              <option value="MAJORITY">Majority</option>
              <option value="UNANIMOUS">Unanimous</option>
            </select>
          </label>

          <label className="check">
            <input
              type="checkbox"
              checked={conversation.userVotes}
              disabled={patchMutation.isPending}
              onChange={(e) => patchMutation.mutate({ userVotes: e.target.checked })}
            />
            I vote on proposals too
          </label>

          <label className="check">
            <input
              type="checkbox"
              checked={conversation.autoContinue}
              disabled={patchMutation.isPending}
              onChange={(e) => patchMutation.mutate({ autoContinue: e.target.checked })}
            />
            Auto-continue stalled agents
          </label>

          <label className="field">
            <span className="field-label">Max agent rounds (0 = unlimited)</span>
            <input
              className="num-input"
              type="number"
              min={0}
              value={rounds}
              onChange={(e) => setRounds(Number(e.target.value))}
              onBlur={(e) => commitRounds(Number(e.target.value))}
              onKeyDown={(e) => {
                if (e.key === 'Enter') commitRounds(rounds)
              }}
            />
          </label>

          <label className="field">
            <span className="field-label">Budget (USD)</span>
            <input
              className="num-input"
              type="number"
              min={0}
              step={0.5}
              placeholder="none"
              value={budget}
              onChange={(e) => setBudget(e.target.value)}
              onBlur={(e) => commitBudget(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') commitBudget(budget)
              }}
            />
            <span className="field-hint">
              Spent so far: {formatUsd2(conversation.spentUsd ?? 0)}
              {conversation.budgetUsd == null ? ' · no budget set (0 clears)' : ''}
            </span>
          </label>

          <label className="field">
            <span className="field-label">Paste placeholder threshold (chars, 0 = off)</span>
            <input
              className="num-input"
              type="number"
              min={0}
              value={pasteThreshold}
              onChange={(e) => setPasteThreshold(Number(e.target.value))}
              onBlur={(e) => commitPasteThreshold(Number(e.target.value))}
              onKeyDown={(e) => {
                if (e.key === 'Enter') commitPasteThreshold(pasteThreshold)
              }}
            />
            <span className="field-hint">
              Longer pastes become an inline placeholder that is expanded back into the message when sent.
            </span>
          </label>

          {patchMutation.isError && (
            <div className="form-error">
              {(patchMutation.error as Error).message || 'Update failed.'}
            </div>
          )}

          <div className="field">
            <span className="field-label">Participants</span>
            <div className="participant-list">
              {conversation.participants.map((p) => (
                <div className="participant-row" key={p.id}>
                  <span
                    className={`active-dot${p.active ? ' on' : ''}`}
                    title={p.active ? 'Active' : 'Inactive'}
                  />
                  <span
                    className="avatar-dot tiny"
                    style={{ background: nameColor(p.displayName) }}
                  />
                  <span className="participant-name">{p.displayName}</span>
                  <span className="badge badge-muted">
                    {p.kind === 'USER' ? 'user' : 'agent'}
                  </span>
                </div>
              ))}
            </div>
          </div>

          <div className="field">
            <span className="field-label">Add agent</span>
            <div className="add-agent-row">
              <select
                value={agentToAdd}
                onChange={(e) => setAgentToAdd(e.target.value)}
                disabled={agentsQuery.isLoading || availableAgents.length === 0}
              >
                <option value="">
                  {agentsQuery.isLoading
                    ? 'Loading…'
                    : availableAgents.length === 0
                      ? 'No agents available'
                      : 'Select an agent…'}
                </option>
                {availableAgents.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.name} ({a.cliType}
                    {a.model ? ` · ${a.model}` : ''})
                  </option>
                ))}
              </select>
              <button
                className="btn btn-tiny"
                disabled={!agentToAdd || addAgentMutation.isPending}
                onClick={() => addAgentMutation.mutate(agentToAdd)}
              >
                {addAgentMutation.isPending ? '…' : 'Add'}
              </button>
            </div>
            {addAgentMutation.isError && (
              <div className="form-error">
                {(addAgentMutation.error as Error).message || 'Could not add agent.'}
              </div>
            )}
          </div>

          <div className="field archive-field">
            {conversation.status === 'ACTIVE' ? (
              confirmingArchive ? (
                <div className="archive-confirm">
                  <span className="phase-banner-text">
                    Archive this conversation? It moves to the archived list — nothing is
                    deleted.
                  </span>
                  <div className="archive-confirm-actions">
                    <button
                      className="btn btn-tiny btn-no"
                      disabled={patchMutation.isPending}
                      onClick={() => {
                        setConfirmingArchive(false)
                        patchMutation.mutate({ status: 'ARCHIVED' })
                      }}
                    >
                      Yes, archive
                    </button>
                    <button
                      className="btn btn-tiny"
                      onClick={() => setConfirmingArchive(false)}
                    >
                      Cancel
                    </button>
                  </div>
                </div>
              ) : (
                <button
                  className="btn btn-tiny archive-btn"
                  disabled={patchMutation.isPending}
                  onClick={() => setConfirmingArchive(true)}
                >
                  🗄 Archive conversation
                </button>
              )
            ) : (
              <button
                className="btn btn-tiny"
                disabled={patchMutation.isPending}
                onClick={() => patchMutation.mutate({ status: 'ACTIVE' })}
              >
                ↩ Unarchive conversation
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
