import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getTasks,
  patchTask,
  type ConversationView,
  type TaskStatus,
  type TaskView,
} from '../api'
import { formatDateTime, nameColor } from '../utils'

interface Props {
  conversation: ConversationView
}

const STATUS_ORDER: TaskStatus[] = ['PROPOSED', 'APPROVED', 'IN_PROGRESS', 'IN_REVIEW', 'DONE']

const STATUS_LABELS: Record<TaskStatus, string> = {
  PROPOSED: 'Proposed',
  APPROVED: 'Approved',
  IN_PROGRESS: 'In progress',
  IN_REVIEW: 'In review',
  DONE: 'Done',
}

/** Tasks not yet worked (before IN_PROGRESS) may still be edited. */
function isEditable(task: TaskView) {
  return task.status === 'PROPOSED' || task.status === 'APPROVED'
}

function TaskEditor({ conversation, task }: { conversation: ConversationView; task: TaskView }) {
  const queryClient = useQueryClient()
  const [title, setTitle] = useState(task.title)
  const [description, setDescription] = useState(task.description)

  useEffect(() => {
    setTitle(task.title)
    setDescription(task.description)
  }, [task.id, task.title, task.description])

  const saveMutation = useMutation({
    mutationFn: () => patchTask(conversation.id, task.id, { title, description }),
    onSuccess: (updated) => {
      queryClient.setQueryData<TaskView[]>(['tasks', conversation.id], (old) =>
        (old ?? []).map((t) => (t.id === updated.id ? updated : t)),
      )
    },
  })

  const editable = isEditable(task)
  const dirty = title !== task.title || description !== task.description

  return (
    <div className="task-detail">
      <div className="task-detail-head">
        <span className={`badge task-status-badge task-status-${task.status.toLowerCase()}`}>
          {STATUS_LABELS[task.status]}
        </span>
        {task.assignee && (
          <span className="badge badge-muted task-assignee">
            <span className="avatar-dot tiny" style={{ background: nameColor(task.assignee) }} />
            {task.assignee}
          </span>
        )}
        <span className="task-detail-meta">
          #{task.ordinal + 1} · created {formatDateTime(task.createdAt)}
        </span>
      </div>

      {!editable && (
        <div className="task-detail-note">
          This task has already been started and can no longer be edited.
        </div>
      )}

      <label className="field">
        <span className="field-label">Title</span>
        <input
          value={title}
          disabled={!editable || saveMutation.isPending}
          onChange={(e) => setTitle(e.target.value)}
        />
      </label>

      <label className="field task-detail-desc-field">
        <span className="field-label">Description</span>
        <textarea
          className="task-detail-desc"
          value={description}
          disabled={!editable || saveMutation.isPending}
          onChange={(e) => setDescription(e.target.value)}
        />
      </label>

      {saveMutation.isError && (
        <div className="form-error">{(saveMutation.error as Error).message || 'Save failed.'}</div>
      )}

      {editable && (
        <div className="btn-row task-detail-actions">
          <button
            className="btn btn-primary"
            disabled={!dirty || title.trim() === '' || saveMutation.isPending}
            onClick={() => saveMutation.mutate()}
          >
            {saveMutation.isPending ? 'Saving…' : 'Save changes'}
          </button>
          <button
            className="btn"
            disabled={!dirty || saveMutation.isPending}
            onClick={() => {
              setTitle(task.title)
              setDescription(task.description)
              saveMutation.reset()
            }}
          >
            Discard
          </button>
        </div>
      )}
    </div>
  )
}

/** Master-detail tasks view for the panel modal: list on the left, view/edit pane on the right. */
export default function TasksDetailView({ conversation }: Props) {
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const tasksQuery = useQuery({
    queryKey: ['tasks', conversation.id],
    queryFn: () => getTasks(conversation.id),
  })

  const tasks = tasksQuery.data ?? []
  const grouped = STATUS_ORDER.map((status) => ({
    status,
    tasks: tasks.filter((t) => t.status === status).sort((a, b) => a.ordinal - b.ordinal),
  })).filter((g) => g.tasks.length > 0)

  const selected = tasks.find((t) => t.id === selectedId) ?? null

  return (
    <div className="tasks-detail-view">
      <div className="tasks-detail-list">
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
            {group.tasks.map((task) => (
              <button
                key={task.id}
                className={`task-row task-row-btn${task.id === selectedId ? ' selected' : ''}`}
                onClick={() => setSelectedId(task.id)}
              >
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
              </button>
            ))}
          </div>
        ))}
      </div>
      <div className="tasks-detail-pane">
        {selected ? (
          <TaskEditor conversation={conversation} task={selected} />
        ) : (
          <div className="empty-state small">
            <div className="empty-title">Select a task</div>
            <div className="empty-sub">
              Click a task on the left to view its details. Tasks that have not been started yet
              can be edited.
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
