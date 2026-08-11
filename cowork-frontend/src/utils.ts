/** Deterministic, pleasant color for a sender name (used for avatar dots). */
export function nameColor(name: string): string {
  let hash = 0
  for (let i = 0; i < name.length; i++) {
    hash = (hash * 31 + name.charCodeAt(i)) >>> 0
  }
  const hue = hash % 360
  return `hsl(${hue} 62% 62%)`
}

export function formatTime(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

/** Compact cost like "$0.0123" — 4 decimals max, trailing zeros trimmed. */
export function formatCost(usd: number): string {
  const s = usd.toFixed(4).replace(/0+$/, '').replace(/\.$/, '')
  return `$${s === '' || s === '-' ? '0' : s}`
}

/** Two-decimal USD amount like "$1.50". */
export function formatUsd2(usd: number): string {
  return `$${usd.toFixed(2)}`
}

/** Short relative time like "3m ago". */
export function formatRelative(iso: string): string {
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return ''
  const sec = Math.round((Date.now() - t) / 1000)
  if (sec < 60) return 'just now'
  const min = Math.round(sec / 60)
  if (min < 60) return `${min}m ago`
  const hr = Math.round(min / 60)
  if (hr < 24) return `${hr}h ago`
  const day = Math.round(hr / 24)
  return `${day}d ago`
}

export function formatDateTime(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return d.toLocaleString([], {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}
