import { useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { deleteDoc, docUrl, getDocs, uploadDoc } from '../api'
import { formatRelative } from '../utils'

interface Props {
  conversationId: string
  alwaysOpen?: boolean
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/** User-uploaded reference files stored in the workspace's implementation_docs/. */
export default function FilesSection({ conversationId, alwaysOpen = false }: Props) {
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(alwaysOpen)
  const [error, setError] = useState<string | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)

  const docsQuery = useQuery({
    queryKey: ['docs', conversationId],
    queryFn: () => getDocs(conversationId),
    enabled: open,
  })

  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['docs', conversationId] })

  const uploadMutation = useMutation({
    mutationFn: (file: File) => uploadDoc(conversationId, file),
    onSuccess: () => {
      setError(null)
      refresh()
    },
    onError: (e) => setError((e as Error).message),
  })

  const deleteMutation = useMutation({
    mutationFn: (name: string) => deleteDoc(conversationId, name),
    onSuccess: () => {
      setError(null)
      refresh()
    },
    onError: (e) => setError((e as Error).message),
  })

  const docs = docsQuery.data ?? []

  return (
    <div className="panel-section">
      {!alwaysOpen && (
        <button className="settings-toggle" onClick={() => setOpen((o) => !o)}>
          <span className={`chevron${open ? ' open' : ''}`}>▸</span>
          Files
          {docs.length > 0 && <span className="badge badge-muted">{docs.length}</span>}
        </button>
      )}

      {open && (
        <div className="files-body">
          <input
            ref={fileInput}
            type="file"
            style={{ display: 'none' }}
            onChange={(e) => {
              const file = e.target.files?.[0]
              if (file) uploadMutation.mutate(file)
              e.target.value = ''
            }}
          />
          <button
            className="btn btn-tiny"
            disabled={uploadMutation.isPending}
            onClick={() => fileInput.current?.click()}
          >
            {uploadMutation.isPending ? 'Uploading…' : '⇧ Upload file'}
          </button>
          <div className="files-hint">
            Specs, mockups, and screenshots for the agents — stored in the workspace's{' '}
            <code>implementation_docs/</code>.
          </div>

          {docsQuery.isLoading && <div className="side-note">Loading…</div>}
          {docs.length === 0 && !docsQuery.isLoading && (
            <div className="side-note">No files uploaded yet.</div>
          )}
          {docs.map((doc) => (
            <div className="file-row" key={doc.name}>
              <a
                className="file-name"
                href={docUrl(conversationId, doc.name)}
                target="_blank"
                rel="noreferrer"
                title={doc.name}
              >
                {doc.name}
              </a>
              <span className="file-meta">
                {formatSize(doc.size)} · {formatRelative(doc.modifiedAt)}
              </span>
              <button
                className="icon-btn file-delete"
                title="Delete"
                disabled={deleteMutation.isPending}
                onClick={() => deleteMutation.mutate(doc.name)}
              >
                ✕
              </button>
            </div>
          ))}

          {error && <div className="form-error">{error}</div>}
        </div>
      )}
    </div>
  )
}
