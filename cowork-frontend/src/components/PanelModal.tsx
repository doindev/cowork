import { useEffect, useState } from 'react'
import { type ConversationView } from '../api'
import { ProposalList } from './ProposalPanel'
import CommitsSection from './CommitsSection'
import FilesSection from './FilesSection'
import SettingsSection from './SettingsSection'
import TasksDetailView from './TasksDetailView'

interface Props {
  conversation: ConversationView
  onClose: () => void
}

type SectionKey = 'proposals' | 'changes' | 'tasks' | 'files' | 'settings'

const SECTIONS: { key: SectionKey; label: string; icon: string }[] = [
  { key: 'proposals', label: 'Proposals', icon: '🗳' },
  { key: 'changes', label: 'Changes', icon: '↯' },
  { key: 'tasks', label: 'Tasks', icon: '☰' },
  { key: 'files', label: 'Files', icon: '🗎' },
  { key: 'settings', label: 'Settings', icon: '⚙' },
]

/** Full-viewport master-detail view of the right panel's sections. */
export default function PanelModal({ conversation, onClose }: Props) {
  const [section, setSection] = useState<SectionKey>('proposals')

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div
      className="modal-overlay"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
    >
      <div
        className="modal modal-full panel-modal"
        role="dialog"
        aria-modal="true"
        aria-label="Conversation workspace"
      >
        <div className="modal-header">
          <h2 title={conversation.title}>{conversation.title || 'Untitled'}</h2>
          <button className="icon-btn" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>
        <div className="panel-modal-body">
          <nav className="panel-modal-nav">
            {SECTIONS.map((s) => (
              <button
                key={s.key}
                className={`panel-modal-nav-item${section === s.key ? ' selected' : ''}`}
                onClick={() => setSection(s.key)}
              >
                <span className="panel-modal-nav-icon">{s.icon}</span>
                {s.label}
              </button>
            ))}
          </nav>
          <div className="panel-modal-content">
            {section === 'proposals' && <ProposalList conversation={conversation} />}
            {section === 'changes' && <CommitsSection conversation={conversation} alwaysOpen />}
            {section === 'tasks' && <TasksDetailView conversation={conversation} />}
            {section === 'files' && <FilesSection conversationId={conversation.id} alwaysOpen />}
            {section === 'settings' && <SettingsSection conversation={conversation} alwaysOpen />}
          </div>
        </div>
      </div>
    </div>
  )
}
