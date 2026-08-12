import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getCommits, type ConversationView } from '../api'
import { formatRelative } from '../utils'
import DiffModal from './DiffModal'

interface Props {
  conversation: ConversationView
  alwaysOpen?: boolean
}

export default function CommitsSection({ conversation, alwaysOpen = false }: Props) {
  const [open, setOpen] = useState(alwaysOpen)
  const [diffHash, setDiffHash] = useState<string | null>(null)

  const commitsQuery = useQuery({
    queryKey: ['commits', conversation.id],
    queryFn: () => getCommits(conversation.id),
    enabled: open,
  })

  const commits = commitsQuery.data ?? []

  return (
    <div className="panel-section">
      {!alwaysOpen && (
        <button className="settings-toggle" onClick={() => setOpen((o) => !o)}>
          <span className={`chevron${open ? ' open' : ''}`}>▸</span>
          Changes
          {commits.length > 0 && <span className="panel-count">{commits.length}</span>}
        </button>
      )}

      {open && (
        <div className="panel-section-body">
          {commitsQuery.isLoading && <div className="side-note">Loading commits…</div>}
          {commitsQuery.isError && <div className="side-note">Could not load commits.</div>}
          {commitsQuery.isSuccess && commits.length === 0 && (
            <div className="side-note">No commits yet.</div>
          )}
          {commits.map((commit) => (
            <button
              className="commit-row"
              key={commit.hash}
              title="View diff"
              onClick={() => setDiffHash(commit.hash)}
            >
              <div className="commit-top">
                <span className="commit-hash">{commit.hash.slice(0, 7)}</span>
                <span className="commit-message">{commit.message}</span>
              </div>
              <div className="commit-meta">
                <span>{commit.author}</span>
                {commit.stat && <span className="commit-stat">{commit.stat}</span>}
                <span className="commit-when">{formatRelative(commit.at)}</span>
              </div>
            </button>
          ))}
        </div>
      )}

      {diffHash && (
        <DiffModal
          conversationId={conversation.id}
          hash={diffHash}
          onClose={() => setDiffHash(null)}
        />
      )}
    </div>
  )
}
