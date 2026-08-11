// Typed API layer for the cowork backend (Spring Boot at /api, proxied by Vite in dev).

export type Phase = 'PLANNING' | 'IMPLEMENTATION'
export type VoteMode = 'MAJORITY' | 'UNANIMOUS'
export type ConversationStatus = 'ACTIVE' | 'ARCHIVED'
export type ParticipantKind = 'USER' | 'AGENT'
export type MessageKind = 'CHAT' | 'SYSTEM' | 'PROPOSAL' | 'VOTE' | 'PHASE'
export type ProposalType = 'PLAN_APPROVAL' | 'TASK_ASSIGNMENT' | 'CODE_CHANGE'
export type ProposalStatus = 'OPEN' | 'PASSED' | 'REJECTED' | 'CANCELLED' | 'NEEDS_USER'
export type VoteValue = 'YES' | 'NO' | 'ABSTAIN'
export type AgentActivity = 'thinking' | 'idle'
export type TaskStatus = 'PROPOSED' | 'APPROVED' | 'IN_PROGRESS' | 'IN_REVIEW' | 'DONE'

export interface AgentView {
  id: string
  name: string
  cliType: string
  model: string
  description: string
  enabled: boolean
}

export interface ParticipantView {
  id: string
  kind: ParticipantKind
  agentId: string | null
  displayName: string
  active: boolean
}

export interface ConversationView {
  id: string
  title: string
  phase: Phase
  voteMode: VoteMode
  maxAgentRounds: number
  userVotes: boolean
  projectId: string | null
  status: ConversationStatus
  createdAt: string
  participants: ParticipantView[]
  budgetUsd: number | null
  spentUsd: number
  /** Frontend-only: filled in from phase events / phase switch responses. */
  workspacePath?: string | null
}

export interface MessageView {
  id: string
  conversationId: string
  senderParticipantId: string | null
  senderName: string
  kind: MessageKind
  content: string
  mentions: string[]
  round: number | null
  refId: string | null
  createdAt: string
  costUsd: number | null
  /** JSON string: array of {tool, summary} entries, or null. */
  activity: string | null
}

export interface VoteView {
  voter: string
  value: VoteValue
  rationale: string | null
  at: string
}

export interface ProposalView {
  id: string
  conversationId: string
  proposer: string
  type: ProposalType
  title: string
  body: string
  status: ProposalStatus
  taskId: string | null
  createdAt: string
  decidedAt: string | null
  decidedBy: string | null
  votes: VoteView[]
  commitHash: string | null
}

export interface PhaseInfo {
  phase: Phase
  projectId: string | null
  workspacePath: string | null
}

export interface AgentStatusEvent {
  name: string
  status: AgentActivity
}

export interface PartialEvent {
  name: string
  text: string
}

export interface ActivityEvent {
  name: string
  tool: string
  summary: string
}

export interface SpendEvent {
  spentUsd: number
  /** -1 means no budget set. */
  budgetUsd: number
}

export interface RoundLimitEvent {
  dropped: string[]
}

export interface CommitView {
  hash: string
  author: string
  at: string
  message: string
  stat: string | null
}

export interface TaskView {
  id: string
  title: string
  description: string
  status: TaskStatus
  assignee: string | null
  ordinal: number
  createdAt: string
}

export interface TurnView {
  participantId: string
  agentName: string
}

export interface CreateConversationRequest {
  title: string
  voteMode: VoteMode
  userVotes: boolean
  maxAgentRounds: number
  agentIds: string[]
}

export interface PatchConversationRequest {
  voteMode?: VoteMode
  userVotes?: boolean
  maxAgentRounds?: number
  status?: ConversationStatus
  /** 0 clears the budget. */
  budgetUsd?: number
}

async function http<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api${path}`, {
    ...init,
    headers: {
      ...(init?.body != null ? { 'Content-Type': 'application/json' } : {}),
      ...(init?.headers ?? {}),
    },
  })
  if (!res.ok) {
    let detail = ''
    try {
      detail = await res.text()
    } catch {
      /* ignore */
    }
    throw new Error(`Request failed (${res.status})${detail ? `: ${detail.slice(0, 300)}` : ''}`)
  }
  if (res.status === 204) return undefined as T
  const contentType = res.headers.get('content-type') ?? ''
  if (!contentType.includes('json')) return undefined as T
  return (await res.json()) as T
}

async function httpText(path: string): Promise<string> {
  const res = await fetch(`/api${path}`)
  if (!res.ok) {
    let detail = ''
    try {
      detail = await res.text()
    } catch {
      /* ignore */
    }
    throw new Error(`Request failed (${res.status})${detail ? `: ${detail.slice(0, 300)}` : ''}`)
  }
  return res.text()
}

export interface AgentDefinition {
  name: string
  content: string
}

export interface DocView {
  name: string
  size: number
  modifiedAt: string
}

export interface AssistResponse {
  reply: string
  updatedContent: string | null
  sessionId: string | null
}

export function getDocs(conversationId: string): Promise<DocView[]> {
  return http(`/conversations/${conversationId}/files`)
}

export async function uploadDoc(conversationId: string, file: File): Promise<DocView> {
  const form = new FormData()
  form.append('file', file)
  const res = await fetch(`/api/conversations/${conversationId}/files`, {
    method: 'POST',
    body: form,
  })
  if (!res.ok) {
    const detail = await res.text().catch(() => '')
    throw new Error(`Upload failed (${res.status})${detail ? `: ${detail.slice(0, 300)}` : ''}`)
  }
  return (await res.json()) as DocView
}

export function deleteDoc(conversationId: string, name: string): Promise<DocView[]> {
  return http(`/conversations/${conversationId}/files/${encodeURIComponent(name)}`, {
    method: 'DELETE',
  })
}

export function docUrl(conversationId: string, name: string): string {
  return `/api/conversations/${conversationId}/files/${encodeURIComponent(name)}`
}

export interface ModelInfo {
  value: string
  hint: string
}

export interface ModelCatalog {
  live: boolean
  models: ModelInfo[]
}

export function getModelCatalog(cli: string): Promise<ModelCatalog> {
  return http(`/models/${encodeURIComponent(cli)}`)
}

export function assistAgent(
  name: string | null,
  content: string,
  message: string,
  sessionId: string | null,
): Promise<AssistResponse> {
  return http('/agents/assist', {
    method: 'POST',
    body: JSON.stringify({ name, content, message, sessionId }),
  })
}

export function getAgents(): Promise<AgentView[]> {
  return http('/agents')
}

export function getAgentDefinition(name: string): Promise<AgentDefinition> {
  return http(`/agents/${encodeURIComponent(name)}/definition`)
}

export function saveAgentDefinition(name: string, content: string): Promise<AgentDefinition> {
  return http(`/agents/${encodeURIComponent(name)}/definition`, {
    method: 'PUT',
    body: JSON.stringify({ content }),
  })
}

export function createAgent(name: string, content?: string): Promise<AgentDefinition> {
  return http('/agents', {
    method: 'POST',
    body: JSON.stringify({ name, content: content ?? '' }),
  })
}

export function deleteAgent(name: string): Promise<AgentView[]> {
  return http(`/agents/${encodeURIComponent(name)}`, { method: 'DELETE' })
}

export function getConversations(
  status: ConversationStatus = 'ACTIVE',
): Promise<ConversationView[]> {
  return http(`/conversations?status=${status}`)
}

export function getConversation(id: string): Promise<ConversationView> {
  return http(`/conversations/${id}`)
}

export function createConversation(req: CreateConversationRequest): Promise<ConversationView> {
  return http('/conversations', { method: 'POST', body: JSON.stringify(req) })
}

export function patchConversation(id: string, req: PatchConversationRequest): Promise<ConversationView> {
  return http(`/conversations/${id}`, { method: 'PATCH', body: JSON.stringify(req) })
}

/** Permanently deletes an ARCHIVED conversation (messages, proposals, votes — irreversible). */
export function deleteConversation(id: string): Promise<void> {
  return http(`/conversations/${id}`, { method: 'DELETE' })
}

export function addAgentToConversation(conversationId: string, agentId: string): Promise<void> {
  return http(`/conversations/${conversationId}/agents/${agentId}`, { method: 'POST' })
}

export function getMessages(conversationId: string, limit = 100): Promise<MessageView[]> {
  return http(`/conversations/${conversationId}/messages?limit=${limit}`)
}

export function sendMessage(conversationId: string, content: string): Promise<MessageView> {
  return http(`/conversations/${conversationId}/messages`, {
    method: 'POST',
    body: JSON.stringify({ content }),
  })
}

export function getProposals(conversationId: string): Promise<ProposalView[]> {
  return http(`/conversations/${conversationId}/proposals`)
}

export function voteOnProposal(proposalId: string, value: VoteValue, rationale?: string): Promise<void> {
  return http(`/proposals/${proposalId}/votes`, {
    method: 'POST',
    body: JSON.stringify({ value, rationale: rationale ?? null }),
  })
}

export function overrideProposal(proposalId: string, decision: 'PASS' | 'REJECT'): Promise<void> {
  return http(`/proposals/${proposalId}/override`, {
    method: 'POST',
    body: JSON.stringify({ decision }),
  })
}

export function switchPhase(conversationId: string, phase: Phase): Promise<PhaseInfo> {
  return http(`/conversations/${conversationId}/phase`, {
    method: 'POST',
    body: JSON.stringify({ phase }),
  })
}

export function getCommits(conversationId: string): Promise<CommitView[]> {
  return http(`/conversations/${conversationId}/commits`)
}

export function getCommitDiff(conversationId: string, hash: string): Promise<string> {
  return httpText(`/conversations/${conversationId}/commits/${encodeURIComponent(hash)}/diff`)
}

export function getTasks(conversationId: string): Promise<TaskView[]> {
  return http(`/conversations/${conversationId}/tasks`)
}

export function getTurns(conversationId: string): Promise<TurnView[]> {
  return http(`/conversations/${conversationId}/turns`)
}

export function cancelTurn(
  conversationId: string,
  participantId: string,
): Promise<{ cancelled: boolean }> {
  return http(`/conversations/${conversationId}/turns/${participantId}`, { method: 'DELETE' })
}

export function continueRounds(conversationId: string): Promise<{ resumed: number }> {
  return http(`/conversations/${conversationId}/continue`, { method: 'POST' })
}
