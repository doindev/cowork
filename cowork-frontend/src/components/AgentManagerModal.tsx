import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  assistAgent,
  createAgent,
  deleteAgent,
  getAgentDefinition,
  getAgents,
  getModelCatalog,
  saveAgentDefinition,
} from '../api'

interface Props {
  onClose: () => void
}

interface AssistMessage {
  role: 'user' | 'assistant'
  text: string
  updated?: boolean
}

const ASSIST_WIDTH_KEY = 'cowork.agentAssistWidth'
const ASSIST_WIDTH_DEFAULT = 360
const ASSIST_WIDTH_MIN = 260
const ASSIST_WIDTH_MAX = 720

function clampAssistWidth(width: number): number {
  return Math.min(ASSIST_WIDTH_MAX, Math.max(ASSIST_WIDTH_MIN, Math.round(width)))
}

function loadAssistWidth(): number {
  const stored = Number(window.localStorage.getItem(ASSIST_WIDTH_KEY))
  return Number.isFinite(stored) && stored > 0 ? clampAssistWidth(stored) : ASSIST_WIDTH_DEFAULT
}

// ---------- Ctrl+Space completion for frontmatter fields ----------

interface CompletionOption {
  value: string
  hint: string
}

const CLI_OPTIONS: CompletionOption[] = [
  { value: 'claude', hint: 'Claude Code CLI' },
  { value: 'codex', hint: 'OpenAI Codex CLI' },
  { value: 'copilot', hint: 'GitHub Copilot CLI (experimental)' },
]

// Hand-curated: the CLIs expose no model-listing API. The field stays free text,
// so anything not listed here can still be typed manually.
const MODEL_OPTIONS: Record<string, CompletionOption[]> = {
  claude: [
    { value: 'claude-fable-5', hint: 'Claude Fable 5 · most capable' },
    { value: 'claude-opus-5', hint: 'Claude Opus 5' },
    { value: 'claude-sonnet-5', hint: 'Claude Sonnet 5' },
    { value: 'claude-opus-4-1', hint: 'Claude Opus 4.1' },
    { value: 'claude-sonnet-4-5', hint: 'Claude Sonnet 4.5' },
    { value: 'claude-haiku-4-5', hint: 'Claude Haiku 4.5 · fast/cheap' },
    { value: 'opus', hint: 'alias · latest Opus' },
    { value: 'sonnet', hint: 'alias · latest Sonnet' },
    { value: 'haiku', hint: 'alias · latest Haiku' },
  ],
  codex: [
    { value: 'gpt-5-codex', hint: 'GPT-5 Codex (default)' },
    { value: 'gpt-5', hint: 'GPT-5' },
    { value: 'gpt-5-mini', hint: 'GPT-5 mini · faster/cheaper' },
  ],
  copilot: [
    { value: 'gpt-5', hint: 'GPT-5' },
    { value: 'claude-sonnet-4.5', hint: 'Claude Sonnet 4.5' },
    { value: 'gemini-2.5-pro', hint: 'Gemini 2.5 Pro' },
  ],
}

// Value completions for keys inside the `options:` frontmatter map.
const OPTION_VALUE_OPTIONS: Record<string, CompletionOption[]> = {
  effort: [
    { value: 'low', hint: 'minimal thinking · fastest' },
    { value: 'medium', hint: 'balanced' },
    { value: 'high', hint: 'more thinking' },
    { value: 'xhigh', hint: 'deep thinking (Claude 5 / Opus 4.7+)' },
    { value: 'max', hint: 'maximum thinking' },
  ],
  'permission-mode': [
    { value: 'acceptEdits', hint: 'auto-accept file edits (default)' },
    { value: 'auto', hint: 'auto-approve permitted tools' },
    { value: 'dontAsk', hint: 'never prompt' },
    { value: 'bypassPermissions', hint: 'skip all permission checks' },
    { value: 'plan', hint: 'plan mode · no changes' },
    { value: 'manual', hint: 'ask for everything' },
  ],
  'turn-timeout-seconds': [
    { value: '300', hint: 'default · 5 minutes' },
    { value: '900', hint: '15 minutes' },
    { value: '1800', hint: '30 minutes' },
  ],
  sandbox: [
    { value: 'workspace-write', hint: 'codex default · write inside the workspace' },
    { value: 'read-only', hint: 'codex · no writes' },
    { value: 'danger-full-access', hint: 'codex · unrestricted' },
  ],
  autocompact: [
    { value: 'auto', hint: 'claude decides when to compact' },
    { value: '150000', hint: 'compact at 150k tokens' },
    { value: '300000', hint: 'compact at 300k tokens' },
  ],
}

// Key completions inside `options: { … }`; the inserted `key: ` is then
// value-completable with another Ctrl+Space.
const OPTION_KEY_OPTIONS: CompletionOption[] = [
  { value: 'effort: ', hint: 'claude thinking level (low…max)' },
  { value: 'permission-mode: ', hint: 'claude tool-permission handling' },
  { value: 'turn-timeout-seconds: ', hint: 'per-turn timeout (default 300)' },
  { value: 'autocompact: ', hint: 'claude auto-compact window (auto or 100k–1M)' },
  { value: 'sandbox: ', hint: 'codex sandbox mode' },
]

// Field-name completions for bare lines between the frontmatter --- markers.
const FRONTMATTER_KEY_OPTIONS: CompletionOption[] = [
  { value: 'name: ', hint: 'required · agent name (alphanumeric/_/-)' },
  { value: 'cli: ', hint: 'required · claude | codex | copilot' },
  { value: 'model: ', hint: 'model id or alias' },
  { value: 'options: { }', hint: 'CLI options map (effort, permission-mode, …)' },
  { value: 'description: ', hint: 'one-line summary shown in lists' },
]

interface CompletionState {
  options: CompletionOption[]
  index: number
  top: number
  left: number
  replaceStart: number
  replaceEnd: number
}

let measureCanvas: HTMLCanvasElement | null = null

function measureCharWidth(font: string): number {
  measureCanvas ??= document.createElement('canvas')
  const ctx = measureCanvas.getContext('2d')
  if (!ctx) return 8
  ctx.font = font
  return ctx.measureText('M').width
}

export default function AgentManagerModal({ onClose }: Props) {
  const queryClient = useQueryClient()
  const agentsQuery = useQuery({ queryKey: ['agents'], queryFn: getAgents })

  const [selectedName, setSelectedName] = useState<string | null>(null)
  const [content, setContent] = useState('')
  const [dirty, setDirty] = useState(false)
  const [newName, setNewName] = useState('')
  const [creating, setCreating] = useState(false)
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // ✨ assistant chat state (per selected agent; reset on switch)
  const [chatOpen, setChatOpen] = useState(false)
  const [chatMessages, setChatMessages] = useState<AssistMessage[]>([])
  const [chatInput, setChatInput] = useState('')
  const [assistSession, setAssistSession] = useState<string | null>(null)
  const chatEndRef = useRef<HTMLDivElement>(null)

  // Resizable chat column; the width survives sessions via localStorage.
  const [chatWidth, setChatWidth] = useState<number>(() => loadAssistWidth())
  const [resizing, setResizing] = useState(false)

  // Ctrl+Space completion popup for cli:/model: frontmatter fields.
  const [completion, setCompletion] = useState<CompletionState | null>(null)
  const editorRef = useRef<HTMLTextAreaElement>(null)

  // Live model catalogs (provider APIs, when the server has credentials); the
  // curated MODEL_OPTIONS constants are the fallback. Cached for the session.
  const currentCli = /^\s*cli:\s*(\w+)/m.exec(content)?.[1]?.toLowerCase() ?? null
  const liveModelsQuery = useQuery({
    queryKey: ['model-catalog', currentCli],
    queryFn: () => getModelCatalog(currentCli!),
    enabled: currentCli !== null && selectedName !== null,
    staleTime: 60 * 60 * 1000,
    retry: false,
  })

  const modelOptionsFor = (cli: string): CompletionOption[] => {
    const live = liveModelsQuery.data
    if (cli === currentCli && live?.live && live.models.length > 0) {
      return live.models.map((m) => ({ value: m.value, hint: m.hint || 'from provider API' }))
    }
    return MODEL_OPTIONS[cli] ?? Object.values(MODEL_OPTIONS).flat()
  }

  const openCompletion = () => {
    const ta = editorRef.current
    if (!ta) return
    const caret = ta.selectionStart
    const lineStart = content.lastIndexOf('\n', caret - 1) + 1
    const lineEndIdx = content.indexOf('\n', caret)
    const lineEnd = lineEndIdx === -1 ? content.length : lineEndIdx
    const line = content.slice(lineStart, lineEnd)

    const caretInLine = caret - lineStart
    const cliMatch = /^(\s*cli:\s*)(.*)$/.exec(line)
    const modelMatch = /^(\s*model:\s*)(.*)$/.exec(line)
    let all: CompletionOption[]
    let valueStart: number
    let valueEnd: number
    if (cliMatch) {
      all = CLI_OPTIONS
      valueStart = cliMatch[1].length
      valueEnd = line.length
    } else if (modelMatch) {
      all = modelOptionsFor(currentCli ?? '')
      valueStart = modelMatch[1].length
      valueEnd = line.length
    } else if (/^\s*options:/.test(line)) {
      // Inside the options map: complete the VALUE of the key just before the caret
      // (`options: { effort: hi█ }`), or the KEY name itself when the caret sits in a
      // fresh segment after `{` or `,` (`options: { █` / `options: { effort: high, █`).
      const before = line.slice(0, caretInLine)
      const keyMatch = /([A-Za-z][A-Za-z0-9-]*)\s*:\s*([^,{}]*)$/.exec(before)
      const values = keyMatch ? OPTION_VALUE_OPTIONS[keyMatch[1]] : undefined
      if (keyMatch && values) {
        all = values
        valueStart = caretInLine - keyMatch[2].length
        valueEnd = caretInLine
        while (valueEnd < line.length && line[valueEnd] !== ',' && line[valueEnd] !== '}') valueEnd++
        while (valueEnd > valueStart && line[valueEnd - 1] === ' ') valueEnd--
      } else {
        const segMatch = /[{,]\s*([A-Za-z0-9-]*)$/.exec(before)
        if (!segMatch) return
        all = OPTION_KEY_OPTIONS
        valueStart = caretInLine - segMatch[1].length
        valueEnd = caretInLine
        while (valueEnd < line.length && /[A-Za-z0-9-]/.test(line[valueEnd])) valueEnd++
      }
    } else {
      // A bare (or partially typed) line between the --- markers completes the
      // frontmatter field name itself.
      const fmEnd = content.startsWith('---') ? content.indexOf('\n---', 3) : -1
      const inFrontmatter = fmEnd !== -1 && lineStart >= 4 && lineStart <= fmEnd
      const bareMatch = /^\s*([A-Za-z0-9-]*)$/.exec(line.slice(0, caretInLine))
      if (!inFrontmatter || !bareMatch) return
      all = FRONTMATTER_KEY_OPTIONS
      valueStart = caretInLine - bareMatch[1].length
      valueEnd = caretInLine
      while (valueEnd < line.length && /[A-Za-z0-9-]/.test(line[valueEnd])) valueEnd++
    }
    const typed = line.slice(valueStart, valueEnd).trim()
    const filtered = all.filter((o) => o.value.toLowerCase().startsWith(typed.toLowerCase()))
    // No match, or the value is already a complete option → the user wants to switch: show all.
    const options =
      filtered.length === 0 || (filtered.length === 1 && filtered[0].value === typed)
        ? all
        : filtered

    const style = window.getComputedStyle(ta)
    const charW = measureCharWidth(style.font)
    const lineH = parseFloat(style.lineHeight) || 19.5
    const padTop = parseFloat(style.paddingTop) || 0
    const padLeft = parseFloat(style.paddingLeft) || 0
    const row = (content.slice(0, lineStart).match(/\n/g) ?? []).length
    setCompletion({
      options,
      index: 0,
      top: ta.offsetTop + padTop + (row + 1) * lineH - ta.scrollTop,
      left: ta.offsetLeft + padLeft + valueStart * charW - ta.scrollLeft,
      replaceStart: lineStart + valueStart,
      replaceEnd: lineStart + valueEnd,
    })
  }

  const applyCompletion = (option: CompletionOption) => {
    if (!completion) return
    const next =
      content.slice(0, completion.replaceStart) + option.value + content.slice(completion.replaceEnd)
    setContent(next)
    setDirty(true)
    setCompletion(null)
    const caret = completion.replaceStart + option.value.length
    window.setTimeout(() => {
      const ta = editorRef.current
      if (ta) {
        ta.focus()
        ta.setSelectionRange(caret, caret)
      }
    }, 0)
  }

  const onEditorKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (completion) {
      if (e.key === 'ArrowDown') {
        e.preventDefault()
        setCompletion((c) => c && { ...c, index: (c.index + 1) % c.options.length })
        return
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault()
        setCompletion(
          (c) => c && { ...c, index: (c.index - 1 + c.options.length) % c.options.length },
        )
        return
      }
      if (e.key === 'Enter' || e.key === 'Tab') {
        e.preventDefault()
        applyCompletion(completion.options[completion.index])
        return
      }
      if (e.key === 'Escape') {
        e.preventDefault()
        e.stopPropagation()
        setCompletion(null)
        return
      }
      if (e.key.length === 1 || e.key === 'Backspace') {
        setCompletion(null)
      }
    }
    if (e.ctrlKey && (e.key === ' ' || e.code === 'Space')) {
      e.preventDefault()
      openCompletion()
    }
  }

  // Listeners are attached synchronously in the mousedown handler (not via an
  // effect) so no mousemove is lost to a React render race.
  const startResize = (e: React.MouseEvent) => {
    e.preventDefault()
    const startX = e.clientX
    const startWidth = chatWidth
    setResizing(true)
    const prevCursor = document.body.style.cursor
    const prevSelect = document.body.style.userSelect
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'

    let latest = startWidth
    const onMove = (ev: MouseEvent) => {
      // Dragging left widens the chat column, dragging right narrows it.
      latest = clampAssistWidth(startWidth + (startX - ev.clientX))
      setChatWidth(latest)
    }
    const onUp = () => {
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
      document.body.style.cursor = prevCursor
      document.body.style.userSelect = prevSelect
      setResizing(false)
      window.localStorage.setItem(ASSIST_WIDTH_KEY, String(latest))
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }

  const definitionQuery = useQuery({
    queryKey: ['agent-definition', selectedName],
    queryFn: () => getAgentDefinition(selectedName!),
    enabled: selectedName !== null,
  })

  useEffect(() => {
    if (definitionQuery.data) {
      setContent(definitionQuery.data.content)
      setDirty(false)
      setError(null)
    }
  }, [definitionQuery.data])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [chatMessages])

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['agents'] })
    void queryClient.invalidateQueries({ queryKey: ['agent-definition'] })
  }

  const saveMutation = useMutation({
    mutationFn: () => saveAgentDefinition(selectedName!, content),
    onSuccess: () => {
      setDirty(false)
      setError(null)
      refresh()
    },
    onError: (e) => setError((e as Error).message),
  })

  const createMutation = useMutation({
    mutationFn: (name: string) => createAgent(name),
    onSuccess: (definition) => {
      setCreating(false)
      setNewName('')
      setError(null)
      refresh()
      selectAgent(definition.name)
    },
    onError: (e) => setError((e as Error).message),
  })

  const deleteMutation = useMutation({
    mutationFn: () => deleteAgent(selectedName!),
    onSuccess: () => {
      setConfirmingDelete(false)
      setSelectedName(null)
      setContent('')
      setError(null)
      refresh()
    },
    onError: (e) => setError((e as Error).message),
  })

  const assistMutation = useMutation({
    mutationFn: (message: string) => assistAgent(selectedName, content, message, assistSession),
    onSuccess: (response) => {
      setAssistSession(response.sessionId)
      const updated = response.updatedContent != null && response.updatedContent.trim().length > 0
      if (updated) {
        setContent(response.updatedContent!)
        setDirty(true)
      }
      setChatMessages((prev) => [
        ...prev,
        { role: 'assistant', text: response.reply || '(no reply)', updated },
      ])
    },
    onError: (e) =>
      setChatMessages((prev) => [
        ...prev,
        { role: 'assistant', text: `Something went wrong: ${(e as Error).message}` },
      ]),
  })

  const selectAgent = (name: string) => {
    setSelectedName(name)
    setConfirmingDelete(false)
    setError(null)
    setChatMessages([])
    setAssistSession(null)
  }

  const sendChat = () => {
    const message = chatInput.trim()
    if (!message || assistMutation.isPending) return
    setChatMessages((prev) => [...prev, { role: 'user', text: message }])
    setChatInput('')
    assistMutation.mutate(message)
  }

  return (
    <div
      className="modal-overlay"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
    >
      <div className="modal modal-full" role="dialog" aria-modal="true" aria-label="Manage agents">
        <div className="modal-header">
          <h2>Manage agents</h2>
          <button className="icon-btn" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>

        <div
          className={`agent-manager-body${chatOpen && selectedName ? ' with-chat' : ''}`}
          style={
            chatOpen && selectedName
              ? { gridTemplateColumns: `240px minmax(0, 1fr) 6px ${chatWidth}px` }
              : undefined
          }
        >
          <div className="agent-manager-list">
            {agentsQuery.data?.map((agent) => (
              <button
                key={agent.id}
                className={`agent-manager-item${selectedName === agent.name ? ' selected' : ''}${
                  agent.enabled ? '' : ' disabled-agent'
                }`}
                onClick={() => selectAgent(agent.name)}
              >
                <span className="agent-manager-item-name">{agent.name}</span>
                <span className="agent-manager-item-meta">
                  {agent.cliType}
                  {agent.model ? ` · ${agent.model}` : ''}
                  {agent.enabled ? '' : ' · disabled'}
                </span>
              </button>
            ))}
            {creating ? (
              <div className="agent-manager-create">
                <input
                  autoFocus
                  type="text"
                  placeholder="agent-name"
                  value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && newName.trim()) createMutation.mutate(newName.trim())
                  }}
                />
                <button
                  className="btn btn-tiny btn-primary"
                  disabled={!newName.trim() || createMutation.isPending}
                  onClick={() => createMutation.mutate(newName.trim())}
                >
                  {createMutation.isPending ? '…' : 'Create'}
                </button>
                <button className="btn btn-tiny" onClick={() => setCreating(false)}>
                  ✕
                </button>
              </div>
            ) : (
              <button className="btn agent-manager-new" onClick={() => setCreating(true)}>
                + New agent
              </button>
            )}
          </div>

          <div className="agent-manager-editor">
            {selectedName === null ? (
              <div className="empty-state small">
                <div className="empty-title">Agent definition files</div>
                <div className="empty-sub">
                  Select an agent to edit its <code>*-agent.md</code> file, or create a new one.
                  Changes hot-reload — they apply from the agent's next turn.
                </div>
              </div>
            ) : (
              <>
                <div className="agent-manager-editor-head">
                  <span className="workspace-path">{selectedName}-agent.md</span>
                  <div className="agent-manager-editor-actions">
                    {confirmingDelete ? (
                      <>
                        <span className="phase-banner-text">Delete this agent file?</span>
                        <button
                          className="btn btn-tiny btn-no"
                          disabled={deleteMutation.isPending}
                          onClick={() => deleteMutation.mutate()}
                        >
                          {deleteMutation.isPending ? '…' : 'Yes, delete'}
                        </button>
                        <button className="btn btn-tiny" onClick={() => setConfirmingDelete(false)}>
                          Cancel
                        </button>
                      </>
                    ) : (
                      <>
                        <button
                          className={`btn btn-tiny sparkle-btn${chatOpen ? ' active' : ''}`}
                          title="Chat with an AI assistant about this agent"
                          onClick={() => setChatOpen((o) => !o)}
                        >
                          ✨
                        </button>
                        <button className="btn btn-tiny" onClick={() => setConfirmingDelete(true)}>
                          Delete
                        </button>
                        <button
                          className="btn btn-tiny btn-primary"
                          disabled={!dirty || saveMutation.isPending}
                          onClick={() => saveMutation.mutate()}
                        >
                          {saveMutation.isPending ? 'Saving…' : dirty ? 'Save' : 'Saved'}
                        </button>
                      </>
                    )}
                  </div>
                </div>
                <textarea
                  ref={editorRef}
                  className="agent-manager-textarea"
                  spellCheck={false}
                  value={content}
                  onChange={(e) => {
                    setContent(e.target.value)
                    setDirty(true)
                  }}
                  onKeyDown={onEditorKeyDown}
                  onScroll={() => setCompletion(null)}
                  onBlur={() => window.setTimeout(() => setCompletion(null), 150)}
                />
                {completion && (
                  <div
                    className="completion-popup"
                    style={{ top: completion.top, left: completion.left }}
                  >
                    {completion.options.map((option, i) => (
                      <button
                        key={option.value}
                        className={`completion-option${i === completion.index ? ' selected' : ''}`}
                        onMouseDown={(e) => {
                          e.preventDefault()
                          applyCompletion(option)
                        }}
                        onMouseEnter={() =>
                          setCompletion((c) => c && { ...c, index: i })
                        }
                      >
                        <span className="completion-value">{option.value}</span>
                        <span className="completion-hint">{option.hint}</span>
                      </button>
                    ))}
                  </div>
                )}
              </>
            )}
            {error && <div className="form-error">{error}</div>}
          </div>

          {chatOpen && selectedName && (
            <div
              className={`assist-divider${resizing ? ' dragging' : ''}`}
              role="separator"
              aria-orientation="vertical"
              aria-label="Resize assistant panel"
              title="Drag to resize"
              onMouseDown={startResize}
            />
          )}

          {chatOpen && selectedName && (
            <div className="assist-chat">
              <div className="assist-chat-head">
                <span className="assist-chat-title">✨ Agent assistant</span>
                <button className="icon-btn" onClick={() => setChatOpen(false)} aria-label="Close chat">
                  ✕
                </button>
              </div>
              <div className="assist-chat-messages">
                {chatMessages.length === 0 && (
                  <div className="assist-chat-hint">
                    Describe what you want this agent to be — its specialty, tone, model, or CLI —
                    and I'll update the file for you. Review and Save when you're happy.
                  </div>
                )}
                {chatMessages.map((message, i) => (
                  <div key={i} className={`assist-msg ${message.role}`}>
                    <div className="assist-msg-text">{message.text}</div>
                    {message.updated && (
                      <div className="assist-msg-updated">✎ Draft updated in the editor</div>
                    )}
                  </div>
                ))}
                {assistMutation.isPending && (
                  <div className="assist-msg assistant">
                    <div className="assist-msg-text thinking-dots">
                      thinking<span>.</span>
                      <span>.</span>
                      <span>.</span>
                    </div>
                  </div>
                )}
                <div ref={chatEndRef} />
              </div>
              <div className="assist-chat-input">
                <textarea
                  rows={2}
                  placeholder="e.g. Make this agent a security-focused reviewer…"
                  value={chatInput}
                  onChange={(e) => setChatInput(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && !e.shiftKey) {
                      e.preventDefault()
                      sendChat()
                    }
                  }}
                />
                <button
                  className="btn btn-tiny btn-primary"
                  disabled={!chatInput.trim() || assistMutation.isPending}
                  onClick={sendChat}
                >
                  Send
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
