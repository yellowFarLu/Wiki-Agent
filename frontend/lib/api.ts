'use client';

import { getSettings } from './settings';
import type {
  AnswerEvalSampleItem,
  BackendErrorBody,
  ChatMessageItem,
  ChatSessionItem,
  ConflictDiff,
  ConflictItem,
  DocumentView,
  DocVersion,
  EventView,
  FeedbackAuditItem,
  FeedbackRequest,
  FieldLineageView,
  GatewayAuditItem,
  HumanTaskResolveRequest,
  HumanTaskView,
  IdentityProfile,
  IdentityUpdateAck,
  IdentityUpdateRequest,
  KnowledgeMetaItem,
  LineageView,
  MetricsAggregation,
  ObservabilityDashboard,
  RagasRun,
  RagasRunDetail,
  ReviewCase,
  ReviewCaseResolveRequest,
  Source,
  StepView,
  TaskView,
  TraceSpanItem,
  VersionDiff,
} from './types';

function authHeaders(): Record<string, string> {
  const settings = getSettings();
  const headers: Record<string, string> = {
    'X-User-Id': settings.userId || 'anonymous',
  };
  if (settings.identity) {
    headers['X-Business-Identity'] = settings.identity;
  }
  return headers;
}

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let message = `请求失败（HTTP ${res.status}）`;
    try {
      const body = (await res.json()) as BackendErrorBody;
      if (body && typeof body.message === 'string' && body.message) {
        message = body.message;
      }
    } catch {
      // 忽略解析失败，使用默认消息
    }
    throw new Error(message);
  }
  return (await res.json()) as T;
}

async function request<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  const headers: Record<string, string> = {
    ...authHeaders(),
    ...((init?.headers as Record<string, string> | undefined) ?? {}),
  };
  const res = await fetch(path, { ...init, headers });
  return handleResponse<T>(res);
}

function jsonBody(body: unknown): RequestInit {
  return {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  };
}

// ============ 文档 ============
export async function uploadDocument(
  file: File,
  domain?: string,
  subDomain?: string,
  effectiveDate?: string,
): Promise<DocumentView> {
  const form = new FormData();
  form.append('file', file);
  if (domain) form.append('domain', domain);
  if (subDomain) form.append('subDomain', subDomain);
  if (effectiveDate) form.append('effectiveDate', effectiveDate);
  const res = await fetch('/api/documents', {
    method: 'POST',
    headers: authHeaders(),
    body: form,
  });
  return handleResponse<DocumentView>(res);
}

export function listDocuments(): Promise<DocumentView[]> {
  return request<DocumentView[]>('/api/documents');
}

export function getDocument(id: string): Promise<DocumentView> {
  return request<DocumentView>(`/api/documents/${encodeURIComponent(id)}`);
}

export async function deleteDocument(id: string): Promise<void> {
  const res = await fetch(`/api/documents/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) {
    await handleResponse<unknown>(res);
  }
}

// ============ 任务 ============
export function listTasks(params?: { status?: string; mine?: boolean }): Promise<TaskView[]> {
  const search = new URLSearchParams();
  if (params?.status) search.set('status', params.status);
  if (params?.mine) search.set('mine', 'true');
  const qs = search.toString();
  return request<TaskView[]>(`/api/tasks${qs ? `?${qs}` : ''}`);
}

export function getTask(taskId: string): Promise<TaskView> {
  return request<TaskView>(`/api/tasks/${encodeURIComponent(taskId)}`);
}

export function getTaskSteps(taskId: string): Promise<StepView[]> {
  return request<StepView[]>(`/api/tasks/${encodeURIComponent(taskId)}/steps`);
}

export function getTaskEvents(taskId: string): Promise<EventView[]> {
  return request<EventView[]>(`/api/tasks/${encodeURIComponent(taskId)}/events`);
}

export function getTaskHumanTasks(taskId: string): Promise<HumanTaskView[]> {
  return request<HumanTaskView[]>(`/api/tasks/${encodeURIComponent(taskId)}/human-tasks`);
}

function taskAction(taskId: string, action: string, body?: unknown): Promise<TaskView> {
  return request<TaskView>(
    `/api/tasks/${encodeURIComponent(taskId)}/${action}`,
    body === undefined ? { method: 'POST' } : jsonBody(body),
  );
}

export function suspendTask(taskId: string, expectedVersion?: number): Promise<TaskView> {
  return taskAction(taskId, 'suspend', expectedVersion === undefined ? {} : { expectedVersion });
}

export function resumeTask(taskId: string): Promise<TaskView> {
  return taskAction(taskId, 'resume');
}

export function cancelTask(taskId: string, expectedVersion?: number): Promise<TaskView> {
  return taskAction(taskId, 'cancel', expectedVersion === undefined ? {} : { expectedVersion });
}

export function replayTask(taskId: string): Promise<TaskView> {
  return taskAction(taskId, 'replay');
}

// ============ 人工任务 ============
export function claimHumanTask(id: string, lockVersion?: number): Promise<HumanTaskView> {
  return request<HumanTaskView>(
    `/api/human-tasks/${encodeURIComponent(id)}/claim`,
    jsonBody(lockVersion === undefined ? {} : { lockVersion }),
  );
}

export function resolveHumanTask(id: string, body: HumanTaskResolveRequest): Promise<TaskView> {
  return request<TaskView>(`/api/human-tasks/${encodeURIComponent(id)}/resolve`, jsonBody(body));
}

// ============ 对话 ============
export function listChatSessions(): Promise<ChatSessionItem[]> {
  return request<ChatSessionItem[]>('/api/chat/sessions');
}

export function getChatMessages(sessionId: string): Promise<ChatMessageItem[]> {
  return request<ChatMessageItem[]>(`/api/chat/sessions/${encodeURIComponent(sessionId)}/messages`);
}

// ============ 血缘 / 版本 ============
export function getLineage(docId: string): Promise<LineageView> {
  return request<LineageView>(`/api/documents/${encodeURIComponent(docId)}/lineage`);
}

export function getFieldLineage(docId: string, fieldKey: string): Promise<FieldLineageView> {
  return request<FieldLineageView>(
    `/api/documents/${encodeURIComponent(docId)}/fields/${encodeURIComponent(fieldKey)}/lineage`,
  );
}

export function getVersions(docId: string): Promise<DocVersion[]> {
  return request<DocVersion[]>(`/api/documents/${encodeURIComponent(docId)}/versions`);
}

export function getVersionDiff(docId: string, a: number, b: number): Promise<VersionDiff> {
  return request<VersionDiff>(
    `/api/documents/${encodeURIComponent(docId)}/versions/${a}/diff/${b}`,
  );
}

// ============ 复核案件 ============
export function listReviewCases(params?: { status?: string; docId?: string }): Promise<ReviewCase[]> {
  const search = new URLSearchParams();
  if (params?.status) search.set('status', params.status);
  if (params?.docId) search.set('docId', params.docId);
  const qs = search.toString();
  return request<ReviewCase[]>(`/api/review-cases${qs ? `?${qs}` : ''}`);
}

export function getReviewCase(id: string): Promise<ReviewCase> {
  return request<ReviewCase>(`/api/review-cases/${encodeURIComponent(id)}`);
}

export function resolveReviewCase(id: number, body: ReviewCaseResolveRequest): Promise<ReviewCase> {
  return request<ReviewCase>(`/api/review-cases/${encodeURIComponent(String(id))}/resolve`, jsonBody(body));
}

// ============ 冲突 ============
export function listConflicts(status?: string): Promise<ConflictItem[]> {
  const qs = status ? `?status=${encodeURIComponent(status)}` : '';
  return request<ConflictItem[]>(`/api/conflicts${qs}`);
}

export function getConflictDiff(id: string): Promise<ConflictDiff> {
  return request<ConflictDiff>(`/api/conflicts/${encodeURIComponent(id)}/diff`);
}

export function scanConflicts(): Promise<{ newConflicts?: number }> {
  return request<{ newConflicts?: number }>('/api/conflicts/scan', { method: 'POST' });
}

export type ConflictResolution =
  | 'KEEP_A'
  | 'KEEP_B'
  | 'KEEP_BOTH'
  | 'MERGE'
  | 'DELETE_A'
  | 'DELETE_B';

export function resolveConflict(
  id: string,
  body: { resolution: ConflictResolution; resolvedBy: string; comment?: string },
): Promise<unknown> {
  return request<unknown>(`/api/conflicts/${encodeURIComponent(id)}/resolve`, jsonBody(body));
}

export function ignoreConflict(
  id: string,
  body: { resolvedBy: string; comment?: string },
): Promise<unknown> {
  return request<unknown>(`/api/conflicts/${encodeURIComponent(id)}/ignore`, jsonBody(body));
}

// ============ 用户反馈 ============
export function submitFeedback(body: FeedbackRequest): Promise<unknown> {
  return request<unknown>('/api/feedback', jsonBody(body));
}

// ============ 知识治理 ============
/** refresh=true 手动触发一次聚合后再返回快照。 */
export function getMetricsAggregation(refresh = false): Promise<MetricsAggregation> {
  return request<MetricsAggregation>(`/api/metrics/aggregation${refresh ? '?refresh=true' : ''}`);
}

/** 最近 RAG 回答评测样本明细。 */
export function getAnswerEvalSamples(limit = 20): Promise<AnswerEvalSampleItem[]> {
  return request<AnswerEvalSampleItem[]>(`/api/metrics/answer-eval/samples?limit=${limit}`);
}

/** 手动触发一批 LLM 评判；未启用评测时后端返回 409。 */
export function triggerAnswerEvalJudge(): Promise<{ enabled: boolean; judged?: number; failed?: number; message?: string }> {
  return request<{ enabled: boolean; judged?: number; failed?: number; message?: string }>(
    '/api/metrics/answer-eval/judge',
    { method: 'POST' },
  );
}

// ============ RAGAS 离线评测 ============
/** RAGAS 历史执行列表（最新在前）。 */
export function getRagasRuns(): Promise<RagasRun[]> {
  return request<RagasRun[]>('/api/metrics/ragas/runs');
}

/** 单次执行详情（run + 评测用例集）。 */
export function getRagasRunDetail(runId: string): Promise<RagasRunDetail> {
  return request<RagasRunDetail>(`/api/metrics/ragas/runs/${encodeURIComponent(runId)}`);
}

/** 触发一次 RAGAS 评测（202 已受理；预检/单飞失败后端返回 409，由调用方 catch 提示）。 */
export function triggerRagasRun(): Promise<{ runId: string | null; message: string }> {
  return request<{ runId: string | null; message: string }>('/api/metrics/ragas/run', {
    method: 'POST',
  });
}

/**
 * 阶段二：导出近 N 天生产日志评测候选（NDJSON 附件，reviewStatus=pending、
 * contexts/reference 留空待专家补标）。响应非 JSON，单独处理文本下载。
 */
export async function exportProductionCandidates(
  days = 30,
  limit = 50,
): Promise<{ filename: string; text: string }> {
  const res = await fetch(
    `/api/metrics/ragas/dataset/export-production?days=${days}&limit=${limit}`,
    { method: 'POST', headers: authHeaders() },
  );
  if (!res.ok) {
    let message = `导出失败（HTTP ${res.status}）`;
    try {
      const body = (await res.json()) as BackendErrorBody;
      if (body && typeof body.message === 'string' && body.message) message = body.message;
    } catch {
      // 保留默认消息
    }
    throw new Error(message);
  }
  const text = await res.text();
  const disposition = res.headers.get('Content-Disposition') ?? '';
  const match = disposition.match(/filename="?([^"]+)"?/);
  return { filename: match?.[1] ?? 'candidates-production.jsonl', text };
}

// ============ 统一可观测 ============
export function getObservabilityDashboard(): Promise<ObservabilityDashboard> {
  return request<ObservabilityDashboard>('/api/observability/dashboard');
}

export function listKnowledgeMeta(): Promise<KnowledgeMetaItem[]> {
  return request<KnowledgeMetaItem[]>('/api/observability/knowledge');
}

export function listFeedbackAudit(): Promise<FeedbackAuditItem[]> {
  return request<FeedbackAuditItem[]>('/api/observability/feedback');
}

export function listGatewayAudit(): Promise<GatewayAuditItem[]> {
  return request<GatewayAuditItem[]>('/api/observability/gateway');
}

export function getSessionTrace(sessionId: string): Promise<TraceSpanItem[]> {
  return request<TraceSpanItem[]>(`/api/trace/session/${encodeURIComponent(sessionId)}`);
}

// ============ 身份管理 ============
export function getIdentityProfile(userId: string): Promise<IdentityProfile> {
  return request<IdentityProfile>(`/api/admin/identity/${encodeURIComponent(userId)}`);
}

export function updateIdentityProfile(
  userId: string,
  body: IdentityUpdateRequest,
): Promise<IdentityUpdateAck> {
  return request<IdentityUpdateAck>(`/api/admin/identity/${encodeURIComponent(userId)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

export type { Source };
