import { useEffect, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { switchPhase, type ConversationView } from '../api'
import { applyPhaseToCache } from '../hooks/useConversationEvents'

interface Props {
  conversation: ConversationView
}

export default function PhaseBanner({ conversation }: Props) {
  const queryClient = useQueryClient()
  // Inline two-step confirm — a native window.confirm would block browser automation
  // (including the agents' own chrome-devtools verification).
  const [confirming, setConfirming] = useState(false)

  useEffect(() => {
    setConfirming(false)
  }, [conversation.id])

  const phaseMutation = useMutation({
    mutationFn: () => switchPhase(conversation.id, 'IMPLEMENTATION'),
    onSuccess: (info) => {
      setConfirming(false)
      applyPhaseToCache(queryClient, conversation.id, info)
    },
  })

  if (conversation.phase === 'PLANNING') {
    return (
      <div className="phase-banner planning">
        <div className="phase-banner-left">
          <span className="badge phase-badge phase-planning">Planning</span>
          <span className="phase-banner-text">
            Agents are discussing and refining the plan.
          </span>
        </div>
        <div className="phase-banner-right">
          {phaseMutation.isError && (
            <span className="form-error inline">
              {(phaseMutation.error as Error).message || 'Phase switch failed.'}
            </span>
          )}
          {confirming ? (
            <>
              <span className="phase-banner-text">Switch to implementation now?</span>
              <button
                className="btn btn-accent"
                disabled={phaseMutation.isPending}
                onClick={() => phaseMutation.mutate()}
              >
                {phaseMutation.isPending ? 'Switching…' : 'Yes, start implementation'}
              </button>
              <button
                className="btn"
                disabled={phaseMutation.isPending}
                onClick={() => setConfirming(false)}
              >
                Cancel
              </button>
            </>
          ) : (
            <button
              className="btn btn-accent"
              disabled={phaseMutation.isPending || conversation.status === 'ARCHIVED'}
              onClick={() => setConfirming(true)}
            >
              Approve plan → start implementation
            </button>
          )}
        </div>
      </div>
    )
  }

  return (
    <div className="phase-banner implementation">
      <div className="phase-banner-left">
        <span className="badge phase-badge phase-implementation">Implementation</span>
        <span className="phase-banner-text">Agents are executing the approved plan.</span>
      </div>
      <div className="phase-banner-right">
        {conversation.workspacePath && (
          <span className="workspace-path" title={conversation.workspacePath}>
            {conversation.workspacePath}
          </span>
        )}
      </div>
    </div>
  )
}
