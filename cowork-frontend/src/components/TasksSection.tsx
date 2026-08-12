import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getTasks, type ConversationView, type TaskStatus, type TaskView } from '../api'
import { nameColor } from '../utils'

interface Props {
  conversation: ConversationView
  alwaysOpen?: boolean
}

const STATUS_ORDER: TaskStatus[] = ['PROPOSED', 'APPROVED', 'IN_PROGRESS', 'IN_REVIEW', 'DONE']

const STATUS_LABELS: Record<TaskStatus, string> = {
  PROPOSED: 'Proposed',
  APPROVED: 'Approved',
  IN_PROGRESS: 'In progress',
  IN_REVIEW: 'In review',
  DONE: 'Done',
}

export default function TasksSection({ conversation, alwaysOpen = false }: Props) {
  const [open, setOpen] = useState(alwaysOpen)

  const tasksQuery = useQuery({
    queryKey: ['tasks', conversation.id],
    queryFn: () => getTasks(conversation.id),
    enabled: open,
  })

  const tasks = tasksQuery.data ?? []
  const grouped = STATUS_ORDER.map((status) => ({
    status,
    tasks: tasks
      .filter((t) => t.status === status)
      .sort((a, b) => a.ordinal - b.ordinal),
  })).filter((g) => g.tasks.length > 0)

  return (
    <div className="panel-section">
      {!alwaysOpen && (
        <button className="settings-toggle" onClick={() => setOpen((o) => !o)}>
          <span className={`chevron${open ? ' open' : ''}`}>▸</span>
          Tasks
          {tasks.length > 0 && <span className="panel-count">{tasks.length}</span>}
        </button>
      )}

      {open && (
        <div className="panel-section-body">
          {tasksQuery.isLoading && <div className="side-note">Loading tasks…</div>}
          {tasksQuery.isError && <div className="side-note">Could not load tasks.</div>}
          {tasksQuery.isSuccess && tasks.length === 0 && (
            <div className="empty-state small">
              <div className="empty-title">No tasks yet</div>
              <div className="empty-sub">Tasks agreed on during planning will show up here.</div>
            </div>
          )}
          {grouped.map((group) => (
            <div className="task-group" key={group.status}>
              <div className={`task-status-head task-status-${group.status.toLowerCase()}`}>
                {STATUS_LABELS[group.status]}
                <span className="task-count">{group.tasks.length}</span>
              </div>
              {group.tasks.map((task: TaskView) => (
                <div className="task-row" key={task.id} title={task.description || undefined}>
                  <span className="task-title">{task.title}</span>
                  {task.assignee && (
                    <span className="badge badge-muted task-assignee">
                      <span
                        className="avatar-dot tiny"
                        style={{ background: nameColor(task.assignee) }}
                      />
                      {task.assignee}
                    </span>
                  )}
                </div>
              ))}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
