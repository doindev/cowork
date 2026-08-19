import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getConversationSkills, getRtkSavings } from '../api'

interface Props {
  conversationId: string
}

function formatTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(n >= 10_000 ? 0 : 1)}k`
  return String(n)
}

/**
 * Token savings rtk realized in this conversation's workspace. Hidden unless the rtk
 * skill is on and rtk actually recorded something — an empty chip is just noise.
 */
export default function RtkChip({ conversationId }: Props) {
  const [open, setOpen] = useState(false)
  const wrapRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    setOpen(false)
  }, [conversationId])

  useEffect(() => {
    if (!open) return
    const onDocMouseDown = (e: MouseEvent) => {
      if (!wrapRef.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDocMouseDown)
    return () => document.removeEventListener('mousedown', onDocMouseDown)
  }, [open])

  const skillsQuery = useQuery({
    queryKey: ['conversation-skills', conversationId],
    queryFn: () => getConversationSkills(conversationId),
    staleTime: 30_000,
  })
  const rtkActive = (skillsQuery.data ?? []).some((s) => s.name === 'rtk' && s.active && s.available)

  const savingsQuery = useQuery({
    queryKey: ['rtk-savings', conversationId],
    queryFn: () => getRtkSavings(conversationId),
    enabled: rtkActive,
    staleTime: 60_000,
  })

  const savings = savingsQuery.data
  if (!rtkActive || !savings?.available || savings.commands === 0) return null

  const pct = Math.round(savings.savingsPct)

  return (
    <div className="rtk-chip-wrap" ref={wrapRef}>
      <button
        className="rtk-chip"
        title={`rtk compressed the output of ${savings.commands} command${
          savings.commands === 1 ? '' : 's'
        } in this workspace. Counts only commands the agents routed through rtk.`}
        onClick={() => setOpen((v) => !v)}
      >
        ⚡ {formatTokens(savings.savedTokens)} saved · {pct}%
      </button>

      {open && (
        <div className="rtk-popover">
          <div className="rtk-popover-title">rtk token savings</div>
          <dl className="rtk-stats">
            <div>
              <dt>Would have used</dt>
              <dd>{savings.inputTokens.toLocaleString()} tokens</dd>
            </div>
            <div>
              <dt>Actually used</dt>
              <dd>{savings.outputTokens.toLocaleString()} tokens</dd>
            </div>
            <div>
              <dt>Saved</dt>
              <dd className="rtk-saved">
                {savings.savedTokens.toLocaleString()} tokens ({pct}%)
              </dd>
            </div>
            <div>
              <dt>Commands wrapped</dt>
              <dd>{savings.commands.toLocaleString()}</dd>
            </div>
          </dl>

          {savings.daily.length > 0 && (
            <>
              <div className="rtk-popover-subtitle">By day</div>
              <div className="rtk-days">
                {savings.daily.slice(-7).map((d) => (
                  <div className="rtk-day" key={d.date}>
                    <span className="rtk-day-date">{d.date}</span>
                    <span className="rtk-day-bar">
                      <span
                        className="rtk-day-fill"
                        style={{ width: `${Math.max(2, Math.min(100, d.savingsPct))}%` }}
                      />
                    </span>
                    <span className="rtk-day-saved">{formatTokens(d.savedTokens)}</span>
                  </div>
                ))}
              </div>
            </>
          )}

          <div className="rtk-popover-note">
            Counts only commands agents routed through rtk — not what they could have saved.
          </div>
        </div>
      )}
    </div>
  )
}
