import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { sendMessage, uploadDoc, type ParticipantView } from '../api'
import { appendMessageToCache } from '../hooks/useConversationEvents'

interface Props {
  conversationId: string
  participants: ParticipantView[]
  disabled?: boolean
  /** Pasted text longer than this becomes a file attachment; 0 disables. */
  pasteThreshold: number
  /** Called after a message was sent successfully (e.g. to clear banners). */
  onSent?: () => void
}

interface PendingAttachment {
  id: number
  file: File
}

/**
 * Pasted text held out of the textarea. A placeholder token sits inline in the message at
 * the paste position and is swapped for the full text on send; deleting the token from
 * the message drops the paste.
 */
interface PendingPaste {
  id: number
  token: string
  text: string
}

/** Pastes above this many characters ask for confirmation before being included. */
const LARGE_PASTE_WARN = 25_000

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

interface MentionState {
  /** Index of the '@' character in the textarea value. */
  start: number
  query: string
}

/** Total entries retained for arrow-key recall, counting the unsubmitted draft slot. */
const HISTORY_LIMIT = 10

const historyKey = (conversationId: string) => `cowork.inputHistory.${conversationId}`
const draftKey = (conversationId: string) => `cowork.inputDraft.${conversationId}`

interface PersistedDraft {
  text: string
  pastes: PendingPaste[]
}

/** Load the persisted unsent draft (text + paste placeholders) for a conversation. */
function loadDraft(conversationId: string): PersistedDraft {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(draftKey(conversationId)) ?? 'null')
    if (parsed !== null && typeof parsed === 'object' && typeof (parsed as PersistedDraft).text === 'string') {
      const raw = (parsed as PersistedDraft).pastes
      const pastes = Array.isArray(raw)
        ? raw.filter(
            (p): p is PendingPaste =>
              p != null &&
              typeof p.id === 'number' &&
              typeof p.token === 'string' &&
              typeof p.text === 'string',
          )
        : []
      return { text: (parsed as PersistedDraft).text, pastes }
    }
  } catch {
    /* fall through to an empty draft */
  }
  return { text: '', pastes: [] }
}

/** Persist the draft; pastes whose token was deleted from the text are dropped. */
function saveDraft(conversationId: string, text: string, pastes: PendingPaste[]): void {
  try {
    const live = pastes.filter((p) => text.includes(p.token))
    if (text === '' && live.length === 0) {
      localStorage.removeItem(draftKey(conversationId))
    } else {
      localStorage.setItem(draftKey(conversationId), JSON.stringify({ text, pastes: live }))
    }
  } catch {
    /* quota/serialization issues just lose persistence, not the draft */
  }
}

/** Load the persisted submitted-message history for a conversation. */
function loadHistory(conversationId: string): string[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(historyKey(conversationId)) ?? '[]')
    return Array.isArray(parsed) ? parsed.filter((e): e is string => typeof e === 'string') : []
  } catch {
    return []
  }
}

function detectMention(value: string, caret: number): MentionState | null {
  const before = value.slice(0, caret)
  const at = before.lastIndexOf('@')
  if (at === -1) return null
  // '@' must be at the start, or preceded by whitespace or a comma.
  if (at > 0 && !/[\s,]/.test(before[at - 1])) return null
  const query = before.slice(at + 1)
  // Cancel once the query spans whitespace, a comma, or another '@'.
  if (/[\s,@]/.test(query)) return null
  return { start: at, query }
}

export default function MessageInput({ conversationId, participants, disabled, pasteThreshold, onSent }: Props) {
  const queryClient = useQueryClient()
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const attachmentSeq = useRef(0)
  const pasteSeq = useRef(0)
  const [pending, setPending] = useState<PendingAttachment[]>([])
  const [pastes, setPastes] = useState<PendingPaste[]>([])
  const [confirmPaste, setConfirmPaste] = useState<string | null>(null)
  const [dragActive, setDragActive] = useState(false)
  const [text, setText] = useState('')
  const [mention, setMention] = useState<MentionState | null>(null)
  const [highlightIndex, setHighlightIndex] = useState(0)
  const [history, setHistory] = useState<string[]>(() => loadHistory(conversationId))
  /** Index into history while browsing with arrows; null while editing the draft. */
  const [navIndex, setNavIndex] = useState<number | null>(null)
  /** True after arrowing down past the draft cleared the field; ArrowUp restores the draft. */
  const [belowDraft, setBelowDraft] = useState(false)
  const draftRef = useRef('')

  // Each conversation has its own persisted history and unsent draft.
  useEffect(() => {
    setHistory(loadHistory(conversationId))
    const draft = loadDraft(conversationId)
    setText(draft.text)
    draftRef.current = draft.text
    setPastes(draft.pastes)
    // New paste tokens must not collide with restored ones.
    pasteSeq.current = draft.pastes.reduce((max, p) => Math.max(max, p.id), 0)
    setPending([])
    setConfirmPaste(null)
    setNavIndex(null)
    setBelowDraft(false)
    setMention(null)
    requestAnimationFrame(autosize)
  }, [conversationId])

  const names = useMemo(
    () => Array.from(new Set(participants.map((p) => p.displayName))).sort(),
    [participants],
  )

  const suggestions = useMemo(() => {
    if (!mention) return []
    const q = mention.query.toLowerCase()
    return names.filter((n) => n.toLowerCase().includes(q))
  }, [mention, names])

  const sendMutation = useMutation({
    mutationFn: async ({
      content,
      attachments,
      pastedBlocks,
    }: {
      content: string
      attachments: PendingAttachment[]
      pastedBlocks: PendingPaste[]
    }) => {
      // Pasted text is part of the message itself: each inline placeholder token is swapped
      // for its full text (tokens the user deleted are dropped). File attachments are
      // uploaded silently and travel as metadata only; agents read them via read_file.
      let full = content
      for (const p of pastedBlocks) {
        if (full.includes(p.token)) {
          full = full.split(p.token).join(p.text)
        }
      }
      const metaLines: string[] = []
      for (const att of attachments) {
        const doc = await uploadDoc(conversationId, att.file, { silent: true })
        metaLines.push(
          `- implementation_docs/${doc.name} (${formatSize(doc.size)}` +
            (att.file.type ? `, ${att.file.type}` : '') +
            ')',
        )
      }
      if (metaLines.length > 0) {
        full +=
          (full ? '\n\n' : '') +
          '[attached files — metadata only; use the read_file tool or read the path to view contents]\n' +
          metaLines.join('\n')
      }
      return sendMessage(conversationId, full)
    },
    onSuccess: (message) => {
      appendMessageToCache(queryClient, conversationId, message)
    },
  })

  const addFiles = (files: FileList | File[] | null) => {
    if (!files || disabled) return
    const items = Array.from(files).map((file) => ({
      id: ++attachmentSeq.current,
      file,
    }))
    if (items.length > 0) setPending((p) => [...p, ...items])
  }

  /** Inserts a placeholder token for the pasted text at the caret position. */
  const addPaste = (pasted: string) => {
    const id = ++pasteSeq.current
    const lines = pasted.split('\n').length
    const token = `[pasted #${id}: ${lines.toLocaleString()} lines, ${pasted.length.toLocaleString()} chars]`
    const nextPastes = [...pastes, { id, token, text: pasted }]
    setPastes(nextPastes)
    const el = textareaRef.current
    const caret = el?.selectionStart ?? text.length
    const caretEnd = el?.selectionEnd ?? caret
    const nextValue = text.slice(0, caret) + token + text.slice(caretEnd)
    setText(nextValue)
    draftRef.current = nextValue
    saveDraft(conversationId, nextValue, nextPastes)
    setNavIndex(null)
    setBelowDraft(false)
    const nextCaret = caret + token.length
    requestAnimationFrame(() => {
      const node = textareaRef.current
      if (node) {
        node.focus()
        node.setSelectionRange(nextCaret, nextCaret)
      }
      autosize()
    })
  }

  const removePending = (id: number) => setPending((p) => p.filter((a) => a.id !== id))

  const autosize = () => {
    const el = textareaRef.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, 180)}px`
  }

  const refreshMention = () => {
    const el = textareaRef.current
    if (!el) return
    const next = detectMention(el.value, el.selectionStart ?? el.value.length)
    setMention(next)
    if (next) setHighlightIndex(0)
  }

  const acceptMention = (name: string) => {
    const el = textareaRef.current
    if (!el || !mention) return
    const caret = el.selectionStart ?? text.length
    const nextValue = `${text.slice(0, mention.start)}@${name} ${text.slice(caret)}`
    const nextCaret = mention.start + name.length + 2
    setText(nextValue)
    setMention(null)
    requestAnimationFrame(() => {
      const node = textareaRef.current
      if (node) {
        node.focus()
        node.setSelectionRange(nextCaret, nextCaret)
      }
      autosize()
    })
  }

  const doSend = () => {
    const content = text.trim()
    if ((!content && pending.length === 0) || sendMutation.isPending || disabled) return
    sendMutation.mutate({ content, attachments: pending, pastedBlocks: pastes }, {
      onSuccess: () => {
        if (content) {
          setHistory((h) => {
            const next = [...h, content].slice(-(HISTORY_LIMIT - 1))
            try {
              localStorage.setItem(historyKey(conversationId), JSON.stringify(next))
            } catch {
              /* quota/serialization issues just lose persistence, not the send */
            }
            return next
          })
        }
        draftRef.current = ''
        setNavIndex(null)
        setBelowDraft(false)
        setText('')
        setMention(null)
        setPending([])
        setPastes([])
        setConfirmPaste(null)
        saveDraft(conversationId, '', [])
        requestAnimationFrame(autosize)
        onSent?.()
      },
    })
  }

  /** Show a history entry (or the saved draft when index is null) in the textarea. */
  const showEntry = (value: string, index: number | null, below = false) => {
    setNavIndex(index)
    setBelowDraft(below)
    setText(value)
    setMention(null)
    requestAnimationFrame(() => {
      const node = textareaRef.current
      if (node) {
        node.focus()
        node.setSelectionRange(value.length, value.length)
      }
      autosize()
    })
  }

  const onKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (mention && suggestions.length > 0) {
      if (e.key === 'ArrowDown') {
        e.preventDefault()
        setHighlightIndex((i) => (i + 1) % suggestions.length)
        return
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault()
        setHighlightIndex((i) => (i - 1 + suggestions.length) % suggestions.length)
        return
      }
      if (e.key === 'Enter' || e.key === 'Tab') {
        e.preventDefault()
        acceptMention(suggestions[Math.min(highlightIndex, suggestions.length - 1)])
        return
      }
      if (e.key === 'Escape') {
        e.preventDefault()
        setMention(null)
        return
      }
    }
    if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
      const el = e.currentTarget
      const noSelection = el.selectionStart === el.selectionEnd
      const caret = el.selectionStart ?? 0
      // Only leave the arrow keys to the caret when there are lines to move across.
      if (e.key === 'ArrowUp' && noSelection && !el.value.slice(0, caret).includes('\n')) {
        if (belowDraft) {
          // Recover whatever was cleared by arrowing down past it, sent or not.
          e.preventDefault()
          showEntry(draftRef.current, null)
        } else if (navIndex === null) {
          if (history.length > 0) {
            e.preventDefault()
            draftRef.current = text
            showEntry(history[history.length - 1], history.length - 1)
          }
        } else if (navIndex > 0) {
          e.preventDefault()
          showEntry(history[navIndex - 1], navIndex - 1)
        }
        return
      }
      if (e.key === 'ArrowDown' && noSelection && !el.value.slice(el.selectionEnd ?? 0).includes('\n')) {
        if (navIndex !== null) {
          e.preventDefault()
          if (navIndex < history.length - 1) {
            showEntry(history[navIndex + 1], navIndex + 1)
          } else {
            showEntry(draftRef.current, null)
          }
        } else if (!belowDraft && text.length > 0) {
          // Arrowing down past the draft clears the field; ArrowUp brings it back.
          e.preventDefault()
          draftRef.current = text
          showEntry('', null, true)
        }
        return
      }
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      doSend()
    }
  }

  return (
    <div
      className={`message-input-wrap${dragActive ? ' drag-active' : ''}`}
      onDragOver={(e) => {
        if (disabled || !e.dataTransfer.types.includes('Files')) return
        e.preventDefault()
        setDragActive(true)
      }}
      onDragLeave={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node)) setDragActive(false)
      }}
      onDrop={(e) => {
        if (disabled) return
        e.preventDefault()
        setDragActive(false)
        addFiles(e.dataTransfer.files)
      }}
    >
      {mention && suggestions.length > 0 && (
        <div className="mention-dropdown" role="listbox">
          {suggestions.map((name, i) => (
            <button
              key={name}
              role="option"
              aria-selected={i === highlightIndex}
              className={`mention-option${i === highlightIndex ? ' active' : ''}`}
              onMouseDown={(e) => {
                e.preventDefault()
                acceptMention(name)
              }}
              onMouseEnter={() => setHighlightIndex(i)}
            >
              @{name}
            </button>
          ))}
        </div>
      )}

      {confirmPaste !== null && (
        <div className="paste-confirm">
          <span className="paste-confirm-text">
            Large paste: {confirmPaste.split('\n').length.toLocaleString()} lines,{' '}
            {confirmPaste.length.toLocaleString()} characters. Include it with your message?
          </span>
          <div className="btn-row">
            <button
              className="btn btn-tiny"
              onClick={() => {
                addPaste(confirmPaste)
                setConfirmPaste(null)
              }}
            >
              Include
            </button>
            <button className="btn btn-tiny" onClick={() => setConfirmPaste(null)}>
              Discard
            </button>
          </div>
        </div>
      )}

      {pending.length > 0 && (
        <div className="pending-files">
          {pending.map((att) => (
            <span className="pending-chip" key={att.id} title={att.file.name}>
              <span className="pending-chip-icon">📎</span>
              <span className="pending-chip-name">{att.file.name}</span>
              <span className="pending-chip-size">{formatSize(att.file.size)}</span>
              <button
                className="pending-chip-remove"
                title="Remove attachment"
                disabled={sendMutation.isPending}
                onClick={() => removePending(att.id)}
              >
                ✕
              </button>
            </span>
          ))}
        </div>
      )}

      <div className="message-input">
        <input
          ref={fileInputRef}
          type="file"
          multiple
          hidden
          onChange={(e) => {
            addFiles(e.target.files)
            e.target.value = ''
          }}
        />
        <button
          className="attach-btn"
          title="Attach files"
          disabled={disabled || sendMutation.isPending}
          onClick={() => fileInputRef.current?.click()}
        >
          📎
        </button>
        <textarea
          ref={textareaRef}
          rows={1}
          value={text}
          disabled={disabled}
          placeholder={
            disabled
              ? 'This conversation is archived.'
              : 'Message the team…  (@ to mention, Enter to send, Shift+Enter for newline, ↑/↓ for history)'
          }
          onChange={(e) => {
            setText(e.target.value)
            // Any edit becomes the live draft — the one unsubmitted entry in history.
            draftRef.current = e.target.value
            saveDraft(conversationId, e.target.value, pastes)
            if (navIndex !== null) setNavIndex(null)
            if (belowDraft) setBelowDraft(false)
            autosize()
            requestAnimationFrame(refreshMention)
          }}
          onClick={refreshMention}
          onPaste={(e) => {
            if (pasteThreshold <= 0 || disabled) return
            const pasted = e.clipboardData.getData('text/plain')
            if (pasted.length > pasteThreshold) {
              e.preventDefault()
              if (pasted.length > LARGE_PASTE_WARN) {
                setConfirmPaste(pasted)
              } else {
                addPaste(pasted)
              }
            }
          }}
          onKeyDown={onKeyDown}
          onKeyUp={(e) => {
            if (e.key === 'ArrowLeft' || e.key === 'ArrowRight' || e.key === 'Home' || e.key === 'End') {
              refreshMention()
            }
          }}
          onBlur={() => {
            // Delay so mousedown on a suggestion can still fire.
            window.setTimeout(() => setMention(null), 150)
          }}
        />
      </div>
      {sendMutation.isError && (
        <div className="form-error">
          {(sendMutation.error as Error).message || 'Failed to send message.'}
        </div>
      )}
    </div>
  )
}
