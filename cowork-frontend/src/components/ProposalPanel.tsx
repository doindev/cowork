import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getProposals,
  overrideProposal,
  voteOnProposal,
  type ConversationView,
  type ProposalView,
  type VoteValue,
} from '../api'
import { formatDateTime, nameColor } from '../utils'
import CommitsSection from './CommitsSection'
import DiffModal from './DiffModal'
import FilesSection from './FilesSection'
import SettingsSection from './SettingsSection'
import TasksSection from './TasksSection'

interface Props {
  conversation: ConversationView
  /** When set, clicking the panel header collapses the panel. */
  onHeaderClick?: () => void
}

const TYPE_LABELS: Record<ProposalView['type'], string> = {
  PLAN_APPROVAL: 'Plan approval',
  TASK_ASSIGNMENT: 'Task assignment',
  CODE_CHANGE: 'Code change',
}

const STATUS_LABELS: Record<ProposalView['status'], string> = {
  OPEN: 'Open',
  PASSED: 'Passed',
  REJECTED: 'Rejected',
  CANCELLED: 'Cancelled',
  NEEDS_USER: 'Needs you',
}

function VoteTally({ votes }: { votes: ProposalView['votes'] }) {
  const counts = { YES: 0, NO: 0, ABSTAIN: 0 }
  for (const v of votes) counts[v.value] += 1
  return (
    <div className="vote-tally">
      <span className="tally tally-yes">YES {counts.YES}</span>
      <span className="tally tally-no">NO {counts.NO}</span>
      <span className="tally tally-abstain">ABSTAIN {counts.ABSTAIN}</span>
    </div>
  )
}

function ProposalCard({
  proposal,
  conversation,
}: {
  proposal: ProposalView
  conversation: ConversationView
}) {
  const queryClient = useQueryClient()
  const [showDiff, setShowDiff] = useState(false)
  const refresh = () =>
    queryClient.invalidateQueries({ queryKey: ['proposals', conversation.id] })

  const voteMutation = useMutation({
    mutationFn: (value: VoteValue) => voteOnProposal(proposal.id, value),
    onSuccess: refresh,
  })

  const overrideMutation = useMutation({
    mutationFn: (decision: 'PASS' | 'REJECT') => overrideProposal(proposal.id, decision),
    onSuccess: refresh,
  })

  const canUserVote = proposal.status === 'OPEN' && conversation.userVotes
  const canOverride = proposal.status === 'OPEN' || proposal.status === 'NEEDS_USER'
  const busy = voteMutation.isPending || overrideMutation.isPending

  return (
    <div className={`proposal-card status-border-${proposal.status.toLowerCase()}`}>
      <div className="proposal-top">
        <span className={`badge type-badge type-${proposal.type.toLowerCase()}`}>
          {TYPE_LABELS[proposal.type]}
        </span>
        <span className={`badge status-badge status-${proposal.status.toLowerCase()}`}>
          {STATUS_LABELS[proposal.status]}
        </span>
      </div>

      <div className="proposal-title">{proposal.title}</div>
      <div className="proposal-meta">
        <span className="proposal-proposer">
          <span className="avatar-dot tiny" style={{ background: nameColor(proposal.proposer) }} />
          {proposal.proposer}
        </span>
        <span className="proposal-date">{formatDateTime(proposal.createdAt)}</span>
      </div>

      {proposal.body && <div className="proposal-body">{proposal.body}</div>}

      {proposal.commitHash && (
        <div className="proposal-diff-row">
          <button className="btn btn-tiny" onClick={() => setShowDiff(true)}>
            View diff ⧉
          </button>
          {showDiff && (
            <DiffModal
              conversationId={conversation.id}
              hash={proposal.commitHash}
              onClose={() => setShowDiff(false)}
            />
          )}
        </div>
      )}

      <VoteTally votes={proposal.votes} />

      {proposal.votes.length > 0 && (
        <div className="vote-list">
          {proposal.votes.map((vote, i) => (
            <div
              className="vote-row"
              key={`${vote.voter}-${i}`}
              title={vote.rationale ?? undefined}
            >
              <span className="avatar-dot tiny" style={{ background: nameColor(vote.voter) }} />
              <span className="vote-voter">{vote.voter}</span>
              <span className={`vote-value vote-${vote.value.toLowerCase()}`}>{vote.value}</span>
              {vote.rationale && <span className="vote-rationale">{vote.rationale}</span>}
            </div>
          ))}
        </div>
      )}

      {proposal.decidedBy && proposal.status !== 'OPEN' && (
        <div className="proposal-decided">
          Decided by {proposal.decidedBy}
          {proposal.decidedAt ? ` · ${formatDateTime(proposal.decidedAt)}` : ''}
        </div>
      )}

      {(canUserVote || canOverride) && (
        <div className="proposal-actions">
          {canUserVote && (
            <div className="action-group">
              <span className="action-label">Your vote</span>
              <div className="btn-row">
                <button
                  className="btn btn-tiny btn-yes"
                  disabled={busy}
                  onClick={() => voteMutation.mutate('YES')}
                >
                  YES
                </button>
                <button
                  className="btn btn-tiny btn-no"
                  disabled={busy}
                  onClick={() => voteMutation.mutate('NO')}
                >
                  NO
                </button>
                <button
                  className="btn btn-tiny"
                  disabled={busy}
                  onClick={() => voteMutation.mutate('ABSTAIN')}
                >
                  ABSTAIN
                </button>
              </div>
            </div>
          )}
          {canOverride && (
            <div className="action-group">
              <span className="action-label">Override</span>
              <div className="btn-row">
                <button
                  className="btn btn-tiny btn-yes"
                  disabled={busy}
                  onClick={() => overrideMutation.mutate('PASS')}
                >
                  PASS
                </button>
                <button
                  className="btn btn-tiny btn-no"
                  disabled={busy}
                  onClick={() => overrideMutation.mutate('REJECT')}
                >
                  REJECT
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {(voteMutation.isError || overrideMutation.isError) && (
        <div className="form-error">
          {((voteMutation.error ?? overrideMutation.error) as Error)?.message ||
            'Action failed.'}
        </div>
      )}
    </div>
  )
}

export function ProposalList({ conversation }: Props) {
  const proposalsQuery = useQuery({
    queryKey: ['proposals', conversation.id],
    queryFn: () => getProposals(conversation.id),
  })

  const proposals = useMemo(() => {
    const list = proposalsQuery.data ?? []
    return [...list].sort(
      (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
    )
  }, [proposalsQuery.data])

  return (
    <div className="proposal-list">
      {proposalsQuery.isLoading && <div className="side-note">Loading proposals…</div>}
      {proposalsQuery.isError && <div className="side-note">Could not load proposals.</div>}
      {!proposalsQuery.isLoading && !proposalsQuery.isError && proposals.length === 0 && (
        <div className="empty-state small">
          <div className="empty-title">No proposals yet</div>
          <div className="empty-sub">Agents will raise proposals here as they plan.</div>
        </div>
      )}
      {proposals.map((p) => (
        <ProposalCard key={p.id} proposal={p} conversation={conversation} />
      ))}
    </div>
  )
}

export function useProposalCount(conversationId: string) {
  const proposalsQuery = useQuery({
    queryKey: ['proposals', conversationId],
    queryFn: () => getProposals(conversationId),
  })
  return proposalsQuery.data?.length ?? 0
}

export default function ProposalPanel({ conversation, onHeaderClick }: Props) {
  const count = useProposalCount(conversation.id)

  return (
    <div className="proposal-panel">
      <div
        className={`panel-header${onHeaderClick ? ' clickable' : ''}`}
        title={onHeaderClick ? 'Collapse panel' : undefined}
        role={onHeaderClick ? 'button' : undefined}
        onClick={onHeaderClick}
      >
        <h3>Proposals</h3>
        {count > 0 && <span className="panel-count">{count}</span>}
      </div>

      <ProposalList conversation={conversation} />

      <div className="panel-sections">
        <CommitsSection conversation={conversation} />
        <TasksSection conversation={conversation} />
        <FilesSection conversationId={conversation.id} />
        <SettingsSection conversation={conversation} />
      </div>
    </div>
  )
}
