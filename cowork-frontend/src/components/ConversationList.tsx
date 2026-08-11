import { useEffect, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { deleteConversation, patchConversation, type ConversationView } from '../api'

interface Props {
  conversations: ConversationView[]
  selectedId: string | null
  loading: boolean
  error: boolean
  showArchived: boolean
  onToggleArchived: () => void
  onSelect: (id: string) => void
  onNewConversation: () => void
  onArchived: (id: string) => void
}

export default function ConversationList({
  conversations,
  selectedId,
  loading,
  error,
  showArchived,
  onToggleArchived,
  onSelect,
  onNewConversation,
  onArchived,
}: Props) {
  const queryClient = useQueryClient()
  const [deleteTarget, setDeleteTarget] = useState<ConversationView | null>(null)

  useEffect(() => {
    if (!deleteTarget) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setDeleteTarget(null)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [deleteTarget])

  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['conversations'] })

  const archiveMutation = useMutation({
    mutationFn: (id: string) => patchConversation(id, { status: 'ARCHIVED' }),
    onSuccess: (updated) => {
      queryClient.setQueryData(['conversation', updated.id], updated)
      refresh()
      onArchived(updated.id)
    },
  })

  const unarchiveMutation = useMutation({
    mutationFn: (id: string) => patchConversation(id, { status: 'ACTIVE' }),
    onSuccess: (updated) => {
      queryClient.setQueryData(['conversation', updated.id], updated)
      refresh()
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteConversation(id),
    onSuccess: (_data, id) => {
      setDeleteTarget(null)
      queryClient.removeQueries({ queryKey: ['conversation', id] })
      queryClient.removeQueries({ queryKey: ['messages', id] })
      queryClient.removeQueries({ queryKey: ['proposals', id] })
      refresh()
      onArchived(id)
    },
  })

  return (
    <div className="conv-list-wrap">
      <button className="btn btn-primary btn-block" onClick={onNewConversation}>
        + New conversation
      </button>
      <button
        className={`archived-toggle${showArchived ? ' on' : ''}`}
        onClick={onToggleArchived}
      >
        {showArchived ? '← Back to active' : 'View archived'}
      </button>
      <div className="conv-list">
        {loading && <div className="side-note">Loading conversations…</div>}
        {error && !loading && <div className="side-note">Could not load conversations.</div>}
        {!loading && !error && conversations.length === 0 && (
          <div className="empty-state small">
            <div className="empty-title">
              {showArchived ? 'No archived conversations' : 'No conversations yet'}
            </div>
            <div className="empty-sub">
              {showArchived
                ? 'Conversations you archive will appear here.'
                : 'Create one to get your agents talking.'}
            </div>
          </div>
        )}
        {conversations.map((c) => {
          const isSelected = c.id === selectedId
          const isArchived = c.status === 'ARCHIVED'
          return (
            <div
              key={c.id}
              role="button"
              tabIndex={0}
              className={`conv-item${isSelected ? ' selected' : ''}${isArchived ? ' archived' : ''}`}
              onClick={() => onSelect(c.id)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  onSelect(c.id)
                }
              }}
            >
              <div className="conv-item-top">
                <span className="conv-title" title={c.title}>
                  {c.title || 'Untitled'}
                </span>
                {!showArchived && !isArchived && (
                  <button
                    className="conv-archive-btn"
                    title="Archive conversation (kept in the database — see View archived)"
                    disabled={archiveMutation.isPending}
                    onClick={(e) => {
                      e.stopPropagation()
                      archiveMutation.mutate(c.id)
                    }}
                  >
                    🗑
                  </button>
                )}
                {showArchived && isArchived && (
                  <>
                    <button
                      className="conv-archive-btn conv-unarchive"
                      title="Unarchive — move back to active conversations"
                      disabled={unarchiveMutation.isPending}
                      onClick={(e) => {
                        e.stopPropagation()
                        unarchiveMutation.mutate(c.id)
                      }}
                    >
                      ↩
                    </button>
                    <button
                      className="conv-archive-btn conv-delete"
                      title="Permanently delete this conversation"
                      onClick={(e) => {
                        e.stopPropagation()
                        setDeleteTarget(c)
                      }}
                    >
                      🗑
                    </button>
                  </>
                )}
              </div>
              <div className="conv-item-bottom">
                <span className={`badge phase-badge phase-${c.phase.toLowerCase()}`}>
                  {c.phase === 'PLANNING' ? 'Planning' : 'Implementation'}
                </span>
                <span className="conv-meta">
                  {c.participants.length} participant{c.participants.length === 1 ? '' : 's'}
                </span>
                {isArchived && <span className="badge badge-muted">Archived</span>}
              </div>
            </div>
          )
        })}
      </div>

      {deleteTarget && (
        <div
          className="modal-overlay"
          onMouseDown={(e) => {
            if (e.target === e.currentTarget) setDeleteTarget(null)
          }}
        >
          <div className="modal" role="dialog" aria-modal="true" aria-label="Confirm permanent delete">
            <div className="modal-header">
              <h2>Delete conversation?</h2>
              <button className="icon-btn" onClick={() => setDeleteTarget(null)} aria-label="Close">
                ✕
              </button>
            </div>
            <div className="modal-body">
              <p className="delete-modal-text">
                Permanently delete <strong>“{deleteTarget.title || 'Untitled'}”</strong>?
              </p>
              <p className="delete-modal-text muted">
                All of its messages, proposals, and votes will be removed from the database.{' '}
                <strong>This cannot be undone.</strong> The project workspace on disk (code and
                uploaded files) is not affected.
              </p>
              {deleteMutation.isError && (
                <div className="form-error">
                  {(deleteMutation.error as Error).message || 'Delete failed.'}
                </div>
              )}
            </div>
            <div className="modal-footer">
              <button className="btn" onClick={() => setDeleteTarget(null)}>
                Cancel
              </button>
              <button
                className="btn btn-danger"
                disabled={deleteMutation.isPending}
                onClick={() => deleteMutation.mutate(deleteTarget.id)}
              >
                {deleteMutation.isPending ? 'Deleting…' : 'Delete forever'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
