import { useMemo, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { sendMessage, type ParticipantView } from '../api'
import { appendMessageToCache } from '../hooks/useConversationEvents'

interface Props {
  conversationId: string
  participants: ParticipantView[]
  disabled?: boolean
  /** Called after a message was sent successfully (e.g. to clear banners). */
  onSent?: () => void
}

interface MentionState {
  /** Index of the '@' character in the textarea value. */
  start: number
  query: string
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

export default function MessageInput({ conversationId, participants, disabled, onSent }: Props) {
  const queryClient = useQueryClient()
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const [text, setText] = useState('')
  const [mention, setMention] = useState<MentionState | null>(null)
  const [highlightIndex, setHighlightIndex] = useState(0)

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
    mutationFn: (content: string) => sendMessage(conversationId, content),
    onSuccess: (message) => {
      appendMessageToCache(queryClient, conversationId, message)
    },
  })

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
    if (!content || sendMutation.isPending || disabled) return
    sendMutation.mutate(content, {
      onSuccess: () => {
        setText('')
        setMention(null)
        requestAnimationFrame(autosize)
        onSent?.()
      },
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
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      doSend()
    }
  }

  return (
    <div className="message-input-wrap">
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

      <div className="message-input">
        <textarea
          ref={textareaRef}
          rows={1}
          value={text}
          disabled={disabled}
          placeholder={
            disabled
              ? 'This conversation is archived.'
              : 'Message the team…  (@ to mention, Enter to send, Shift+Enter for newline)'
          }
          onChange={(e) => {
            setText(e.target.value)
            autosize()
            requestAnimationFrame(refreshMention)
          }}
          onClick={refreshMention}
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
        <button
          className="btn btn-primary send-btn"
          disabled={disabled || sendMutation.isPending || text.trim().length === 0}
          onClick={doSend}
        >
          {sendMutation.isPending ? '…' : 'Send'}
        </button>
      </div>
      {sendMutation.isError && (
        <div className="form-error">
          {(sendMutation.error as Error).message || 'Failed to send message.'}
        </div>
      )}
    </div>
  )
}
