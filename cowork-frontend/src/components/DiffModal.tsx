import { useQuery } from '@tanstack/react-query'
import { getCommitDiff } from '../api'

interface Props {
  conversationId: string
  hash: string
  onClose: () => void
}

function lineClass(line: string): string {
  if (line.startsWith('@@')) return 'diff-hunk'
  if (
    line.startsWith('diff --git') ||
    line.startsWith('index ') ||
    line.startsWith('+++') ||
    line.startsWith('---') ||
    line.startsWith('new file') ||
    line.startsWith('deleted file') ||
    line.startsWith('similarity ') ||
    line.startsWith('rename ') ||
    line.startsWith('old mode') ||
    line.startsWith('new mode') ||
    line.startsWith('Binary files')
  ) {
    return 'diff-meta'
  }
  if (line.startsWith('+')) return 'diff-add'
  if (line.startsWith('-')) return 'diff-del'
  return 'diff-ctx'
}

export default function DiffModal({ conversationId, hash, onClose }: Props) {
  const diffQuery = useQuery({
    queryKey: ['diff', conversationId, hash],
    queryFn: () => getCommitDiff(conversationId, hash),
  })

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal modal-wide" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h2>
            Diff <span className="commit-hash">{hash.slice(0, 10)}</span>
          </h2>
          <button className="icon-btn" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>
        <div className="diff-body">
          {diffQuery.isLoading && <div className="chat-note">Loading diff…</div>}
          {diffQuery.isError && (
            <div className="form-error">
              {(diffQuery.error as Error).message || 'Could not load diff.'}
            </div>
          )}
          {diffQuery.isSuccess && (
            <pre className="diff-view">
              {diffQuery.data.split('\n').map((line, i) => (
                <div className={`diff-line ${lineClass(line)}`} key={i}>
                  {line === '' ? ' ' : line}
                </div>
              ))}
            </pre>
          )}
        </div>
      </div>
    </div>
  )
}
