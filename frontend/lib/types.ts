// 后端 API 精确类型定义

export type Domain =
  | 'industry_solution'
  | 'merchant_center'
  | 'pms'
  | 'service_provider'
  | 'trunk_line'
  | 'customs'
  | 'settlement'
  | 'first_mile'
  | 'trajectory';

export type SubDomain =
  | 'product_doc'
  | 'operation_manual'
  | 'faq'
  | 'case_library'
  | 'rule_config'
  | 'api_doc';

export type Identity = 'admin' | 'business' | 'product' | 'technology' | 'test';

export type JsonValue = unknown;
export type JsonObject = Record<string, unknown>;

export interface BackendErrorBody {
  timestamp?: string;
  status?: number;
  error?: string;
  message?: string;
}

// ============ 文档 ============
export type DocumentStatus = 'PARSING' | 'READY' | 'FAILED' | 'EXTRACTING' | 'AI_SKIPPED' | string;

export interface DocumentView {
  id: string;
  filename: string;
  docType: string;
  sizeBytes: number;
  status: DocumentStatus;
  parentCount: number;
  childCount: number;
  error: string | null;
  createdAt: string;
  taskId?: string;
  duplicate?: boolean;
}

// ============ 任务 ============
export type TaskStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'WAITING_HUMAN'
  | 'SUSPENDED'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED';

export interface TaskView {
  taskId: string;
  taskType: string;
  bizKey: string;
  status: TaskStatus;
  attempt: number;
  maxAttempts: number;
  progressPercent: number;
  resultRef: string | null;
  errorCode: string | null;
  errorMsg: string | null;
  suspendReason: string | null;
  submittedBy: string;
  leaseOwner: string | null;
  createdAt: string;
  updatedAt: string;
}

export type StepStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'SKIPPED';

export interface StepView {
  stepNo: number;
  stepType: string;
  stepName: string;
  status: StepStatus;
  startedAt: string | null;
  endedAt: string | null;
  errorMsg: string | null;
}

export interface EventView {
  eventType: string;
  actorType: string;
  actorId: string;
  detail: unknown;
  createdAt: string;
}

export type HumanTaskKind = 'INPUT' | 'REVIEW' | 'TOOL_APPROVAL' | 'DECRYPT';
export type HumanTaskStatus = 'OPEN' | 'CLAIMED' | 'RESOLVED' | 'EXPIRED';

export interface HumanTaskView {
  id: string;
  taskId: string;
  stepNo: number;
  kind: HumanTaskKind;
  title: string;
  instruction: string;
  formSchema: unknown;
  formValue: unknown;
  status: HumanTaskStatus;
  claimedBy: string | null;
  claimedAt: string | null;
  resolvedBy: string | null;
  resolvedAt: string | null;
}

// ============ 对话 ============
export interface Source {
  index: number;
  docId: string;
  versionNo: number;
  pageNo: number | null;
  snippet: string;
  artifactId: string;
  score: number;
  filename: string;
}

export type ChatStage = 'routing' | 'rewriting' | 'retrieving' | 'grading' | 'generating' | 'fallback';

export interface ChatSessionItem extends Record<string, unknown> {
  sessionId?: string;
}

export interface ChatMessageItem extends Record<string, unknown> {
  role?: string;
  content?: string;
  createdAt?: string;
}

// ============ 血缘 / 版本 ============
export interface DocVersion {
  id: number;
  docId: string;
  versionNo: number;
  status: 'DRAFT' | 'PUBLISHED' | 'SUPERSEDED' | string;
  parentVersionNo: number | null;
  changeSummary: string | null;
  artifactSha256: string;
  createdBy: string;
  createdAt: string;
}

export type FieldSource = 'MODEL' | 'RULE' | 'HUMAN';

export interface ExtractedField {
  id: number;
  docId: string;
  fieldKey: string;
  fieldLabel: string;
  valueText: string;
  valueType: string;
  confidence: number;
  source: FieldSource;
  schemaKey: string;
  schemaVersion: string;
  valid: boolean;
  reviewRequired: boolean;
  versionNo: number;
  pageNo: number | null;
  snippet: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface LineageView {
  docId: string;
  versions: DocVersion[];
  artifacts: unknown[];
  edges: unknown[];
  fields: ExtractedField[];
}

export interface FieldLineageView {
  docId: string;
  fieldKey: string;
  current: ExtractedField | null;
  history: unknown[];
  edges: unknown[];
}

export interface VersionDiffField {
  fieldKey: string;
  valueA: string | null;
  valueB: string | null;
  changed: boolean;
  confidenceA: number | null;
  confidenceB: number | null;
  sourceA: string | null;
  sourceB: string | null;
  editedByA: string | null;
  editedByB: string | null;
}

export interface VersionDiff {
  docId: string;
  versionA: number;
  versionB: number;
  fields: VersionDiffField[];
}

// ============ 复核案件 ============
export type ReviewCaseType = 'LOW_CONFIDENCE' | 'MATERIAL_DIFF' | 'RULE_MISMATCH';
export type ReviewCaseStatus = 'OPEN' | 'APPROVED' | 'REJECTED' | 'EDITED';
export type ReviewAction = 'APPROVE' | 'REJECT' | 'EDIT';

export interface ReviewCase {
  id: number;
  caseType: ReviewCaseType;
  docId: string;
  versionNo: number;
  fieldKey: string;
  diffJson: string | null;
  source: string | null;
  confidence: number | null;
  status: ReviewCaseStatus;
  humanTaskId: number | null;
  taskId: string | null;
  ruleCode: string | null;
  ruleVersion: number | null;
  computationId: number | null;
  resolutionJson: string | null;
  resolvedBy: string | null;
  createdAt: string;
  resolvedAt: string | null;
}

// ============ 冲突 ============
export interface ConflictItem extends Record<string, unknown> {
  id?: number;
  chunkIdA?: string;
  chunkIdB?: string;
  similarity?: number;
  domainTag?: string;
  subDomainTag?: string;
  status?: string;
  resolution?: string | null;
  resolvedBy?: string | null;
  detectedAt?: string;
}

/** 冲突 diff 单侧 chunk 视图（后端 toDiffView 的键）。 */
export interface ConflictChunkView {
  chunkId?: string;
  content?: string | null;
  docId?: string | null;
  domainTag?: string;
  subDomainTag?: string;
  createdBy?: string;
  createdIdentity?: string;
  sourceFilename?: string;
  version?: number;
}

export interface ConflictDiff {
  conflict: unknown;
  chunkA: ConflictChunkView;
  chunkB: ConflictChunkView;
}

// ============ 身份管理 ============
export interface IdentityProfile {
  userId: string;
  businessIdentity: string;
  identityLabel: string;
  allowedSubDomains: string[];
  overrides: unknown;
  /** 后端以 JSON 字符串存储（UserProfile.assignedDomains 为 String）。 */
  assignedDomains: string | null;
}

export interface IdentityUpdateRequest {
  businessIdentity: string;
  overrides?: string;
  assignedDomains?: string;
}

/** 后端 PUT /api/admin/identity/{userId} 仅回执，不含完整画像；保存后需重新 GET。 */
export interface IdentityUpdateAck {
  status: string;
  userId: string;
  businessIdentity: string;
}

// ============ 人工任务处理 ============
export interface HumanTaskResolveRequest {
  kind: 'INPUT' | 'DIRECT_RESOLVE';
  formValue?: Record<string, unknown>;
  resultRef?: string;
}

export interface ReviewCaseResolveRequest {
  action: ReviewAction;
  editedFields?: Record<string, string>;
  comment?: string;
}

// ============ 用户反馈 ============
export interface FeedbackRequest {
  sessionId: string;
  conversationId: string;
  /** 会话级反馈不强挂 chunk（SSE sources 仅父文档粒度），传 null。 */
  chunkId: string | null;
  feedbackType: 'USEFUL' | 'USELESS';
  feedbackComment?: string;
}

// ============ 知识治理（指标聚合） ============
export interface StaleKnowledgeItem {
  chunkId: string;
  docId: string | null;
  domainTag: string | null;
  subDomainTag: string | null;
  createdBy: string | null;
  createdAt: string | null;
  recentRetrievals: number;
  staleScore: number;
}

/** 疑似无用知识：被检索访问过，且无用反馈占比（无用/(有用+无用)）严格超过 50%。 */
export interface SuspectUselessKnowledgeItem {
  chunkId: string;
  docId: string | null;
  domainTag: string | null;
  subDomainTag: string | null;
  sourceFilename: string | null;
  createdBy: string | null;
  retrievalCount: number;
  usefulCount: number;
  uselessCount: number;
  totalFeedback: number;
  uselessRatio: number;
}

/** GET /api/metrics/aggregation；未聚合时 aggregatedAt 为 null 且带 message。 */
export interface MetricsAggregation {
  aggregatedAt: string | null;
  message?: string;
  totalKnowledge?: number;
  totalRetrievals?: number;
  totalCitations?: number;
  usefulCount?: number;
  uselessCount?: number;
  recallRate?: number;
  staleKnowledgeCount?: number;
  staleKnowledge?: StaleKnowledgeItem[];
  suspectUselessKnowledgeCount?: number;
  suspectUselessKnowledge?: SuspectUselessKnowledgeItem[];
  answerEval?: AnswerEvalSummary | null;
}

/** RAG 回答评测三率汇总（率可能为 null=分母 0，禁止显示为 0%）。 */
export interface AnswerEvalSummary {
  judgedCount?: number;
  pendingCount?: number;
  failedCount?: number;
  accuracyRate?: number | null;
  faithfulnessRate?: number | null;
  relevanceRate?: number | null;
  accuracyJudged?: number;
  faithfulnessJudged?: number;
  relevanceJudged?: number;
}

/** GET /api/metrics/answer-eval/samples；faithfulness/relevance 为 null 表示无法评判。 */
export interface AnswerEvalSampleItem {
  id: number;
  sessionId: string;
  question: string | null;
  answer: string | null;
  channel: string | null;
  judgeModel: string | null;
  faithfulness: number | null;
  relevance: number | null;
  verdictReason: string | null;
  status: string;
  error: string | null;
  createdAt: string;
  judgedAt: string | null;
}

/** RAGAS 历史执行行（GET /api/metrics/ragas/runs）；指标 null=无成功评分显示"-"。 */
export interface RagasRun {
  id: number;
  runId: string;
  status: 'RUNNING' | 'OK' | 'ERROR' | string;
  judgeModel: string | null;
  embeddingModel: string | null;
  endpoint: string | null;
  sampleCount: number;
  durationSec: number | null;
  faithfulness: number | null;
  answerRelevancy: number | null;
  contextPrecision: number | null;
  contextRecall: number | null;
  factualCorrectness: number | null;
  semanticSimilarity: number | null;
  reason: string | null;
  outputLog: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  createdAt: string;
}

/** RAGAS 评测用例集单行：contexts/errors 为 JSON 字符串。 */
export interface RagasSample {
  id: number;
  runId: string;
  sampleId: string;
  domainTag: string | null;
  /** 三阶段数据流水线来源：seed-manual/synthetic/production/expert；旧 run 回填 seed-manual。 */
  source: string | null;
  /** 难度：simple/reasoning/multi_hop/boundary。 */
  difficulty: string | null;
  /** 审核状态：pending/approved/rejected；基线只评 approved。 */
  reviewStatus: string | null;
  /** 答案来源：manual/reference-proxy/production。 */
  answerOrigin: string | null;
  /** 逗号分隔标签（noise/hard-negative/distribution 等）。 */
  tags: string | null;
  question: string;
  contexts: string;
  answer: string;
  reference: string;
  faithfulness: number | null;
  answerRelevancy: number | null;
  contextPrecision: number | null;
  contextRecall: number | null;
  factualCorrectness: number | null;
  semanticSimilarity: number | null;
  errors: string | null;
  createdAt: string;
}

/** GET /api/metrics/ragas/runs/{runId}。 */
export interface RagasRunDetail {
  run: RagasRun;
  samples: RagasSample[];
}

// ============ 统一可观测 ============
export interface ObservabilityDashboard {
  businessMetrics?: {
    period?: string;
    retrievals?: number;
    citations?: number;
    useful?: number;
    useless?: number;
  };
  knowledgeDetails?: {
    totalKnowledge?: number;
    staleKnowledge?: number;
    pendingConflicts?: number;
  };
  feedbackAudit?: {
    totalFeedback?: number;
    gatewayBlocked?: number;
    contentViolations?: number;
    violationTypes?: string[];
  };
}

export interface KnowledgeMetaItem {
  chunkId: string;
  docId: string | null;
  domainTag: string | null;
  subDomainTag: string | null;
  requiredIdentity: string | null;
  createdBy: string | null;
  sourceFilename: string | null;
  version: number | null;
  createdAt: string | null;
}

export interface FeedbackAuditItem {
  id: number;
  sessionId: string | null;
  chunkId: string | null;
  feedbackType: string | null;
  feedbackComment: string | null;
  createdAt: string | null;
}

export interface GatewayAuditItem {
  id: number;
  direction: string | null;
  detectorName: string | null;
  detectionResult: string | null;
  riskScore: number | null;
  userId: string | null;
  createdAt: string | null;
}

export interface TraceSpanItem {
  id: number;
  nodeId: string | null;
  spanType: string | null;
  status: string | null;
  durationMs: number;
  modelUsed: string | null;
  errorMsg: string | null;
}
