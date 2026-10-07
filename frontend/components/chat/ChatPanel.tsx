'use client';

import { memo, useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  App,
  Avatar,
  Button,
  Card,
  Empty,
  Input,
  List,
  Skeleton,
  Space,
  Tag,
  Typography,
  Upload,
} from 'antd';
import type { UploadProps } from 'antd';
import {
  DislikeOutlined,
  DownloadOutlined,
  FileExcelOutlined,
  InboxOutlined,
  LikeOutlined,
  LoadingOutlined,
  PlusOutlined,
  RobotOutlined,
  SendOutlined,
  StopOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useRouter } from 'next/navigation';
import { getChatMessages, listChatSessions, submitFeedback, uploadBusinessMapping } from '@/lib/api';
import { chatStream } from '@/lib/sse';
import { getSettings } from '@/lib/settings';
import type { ChatStage, FileReadyPayload, SlotRequestPayload, Source } from '@/lib/types';
import AnswerText from '@/components/chat/AnswerText';

interface ChatMsg {
  /** 前端本地稳定标识，用于列表 key / memo 比对，避免用数组下标。 */
  _uid: string;
  role: 'user' | 'assistant';
  content: string;
  sources: Source[];
  stage: ChatStage | null;
  rewrittenQuery?: string;
  blocked?: string;
  error?: string;
  streaming?: boolean;
  /** 本条回答归属的后端权威会话 ID（session 事件下发），用于反馈/查链路。 */
  sid?: string;
  /** 知识库未命中兜底来源：web_search 联网 / model_knowledge 模型通用知识。 */
  fallbackMode?: string;
  feedback?: 'USEFUL' | 'USELESS';
  feedbackFailed?: boolean;
  /** 业务意图缺槽追问（订单号输入框 / 映射表上传区）；存在时该气泡挂起等待用户补槽。 */
  slotRequest?: SlotRequestPayload;
  /** 清关 Excel 已生成（下载卡片）。 */
  fileReady?: FileReadyPayload;
}

let uidSeq = 0;
/** 生成消息本地稳定 ID（时间戳 + 自增 + 随机后缀，防并发/防串）。 */
function genUid(): string {
  uidSeq += 1;
  return `m_${Date.now().toString(36)}_${uidSeq.toString(36)}_${Math.random().toString(36).slice(2, 6)}`;
}

/** 校验并规范化引用来源（历史接口返回的 JSON 为弱类型，丢弃残缺项避免角标/来源区渲染异常）。 */
function normalizeSources(raw: unknown): Source[] {
  if (!Array.isArray(raw)) return [];
  return raw.filter(
    (s): s is Source =>
      s !== null &&
      typeof s === 'object' &&
      typeof (s as Source).index === 'number' &&
      typeof (s as Source).docId === 'string' &&
      typeof (s as Source).filename === 'string',
  );
}

const STAGE_LABEL: Record<ChatStage, string> = {
  routing: '分析问题',
  rewriting: '改写中',
  retrieving: '检索中',
  grading: '证据评估',
  generating: '生成中',
  fallback: '兜底应答',
};

const STAGE_ORDER: ChatStage[] = ['routing', 'rewriting', 'retrieving', 'grading', 'generating', 'fallback'];

function emptyAssistant(): ChatMsg {
  return { _uid: genUid(), role: 'assistant', content: '', sources: [], stage: null, streaming: true };
}

/**
 * 输入区（发送框 + 发送/停止按钮）。
 * 输入文本是高频本地状态，独立成组件后，打字只会重渲染输入区自身，
 * 不再触发上方整个消息列表（含历史回答的引用解析/Popover）重渲染。
 */
const ChatComposer = memo(function ChatComposer({
  sending,
  onSend,
  onStop,
}: {
  sending: boolean;
  onSend: (question: string) => void;
  onStop: () => void;
}) {
  const [input, setInput] = useState('');

  const send = () => {
    const question = input.trim();
    if (!question || sending) return;
    setInput('');
    onSend(question);
  };

  return (
    <Space.Compact style={{ width: '100%' }}>
      <Input.TextArea
        value={input}
        onChange={(e) => setInput(e.target.value)}
        placeholder="输入问题，回车发送（Shift+回车换行）"
        autoSize={{ minRows: 1, maxRows: 6 }}
        onPressEnter={(e) => {
          if (!e.shiftKey) {
            e.preventDefault();
            send();
          }
        }}
      />
      {sending ? (
        <Button danger icon={<StopOutlined />} onClick={onStop}>
          停止
        </Button>
      ) : (
        <Button type="primary" icon={<SendOutlined />} onClick={send}>
          发送
        </Button>
      )}
    </Space.Compact>
  );
});

/**
 * 缺槽追问操作区（业务意图挂起点）：
 * <ul>
 *   <li>orderNo → 内联输入框，提交的原文由后端 ActGate 正则解析</li>
 *   <li>mappingExcel → 上传区，先调映射表上传接口拿 fileId，再带 fileId 发起对话轮</li>
 * </ul>
 * 交互期间禁用，防止重复提交。
 */
const SlotRequestArea = memo(function SlotRequestArea({
  slotRequest,
  busy,
  onOrderNo,
  onMappingFile,
}: {
  slotRequest: SlotRequestPayload;
  busy: boolean;
  onOrderNo: (orderNo: string) => void;
  onMappingFile: (file: File) => Promise<void>;
}) {
  const [orderNo, setOrderNo] = useState('');
  const [uploading, setUploading] = useState(false);
  const { message } = App.useApp();

  if (slotRequest.slot === 'orderNo') {
    const submit = () => {
      const v = orderNo.trim();
      if (!v || busy) return;
      setOrderNo('');
      onOrderNo(v);
    };
    return (
      <div style={{ marginTop: 10 }}>
        <Space.Compact style={{ width: '100%' }}>
          <Input
            value={orderNo}
            onChange={(e) => setOrderNo(e.target.value)}
            placeholder="请输入订单号（8–20 位字母或数字）"
            onPressEnter={submit}
            disabled={busy}
          />
          <Button type="primary" icon={<SendOutlined />} onClick={submit} disabled={busy}>
            提交
          </Button>
        </Space.Compact>
        {slotRequest.retry > 0 && (
          <div>
            <Typography.Text type="warning" style={{ fontSize: 12 }}>
              未能识别上次提供的信息，请核对后重新输入（第 {slotRequest.retry + 1} 次）
            </Typography.Text>
          </div>
        )}
      </div>
    );
  }

  if (slotRequest.slot === 'mappingExcel') {
    const uploadProps: UploadProps = {
      accept: '.xlsx',
      multiple: false,
      showUploadList: false,
      disabled: busy || uploading,
      customRequest: async (options) => {
        setUploading(true);
        try {
          await onMappingFile(options.file as File);
          options.onSuccess?.({}, new XMLHttpRequest());
        } catch (e) {
          options.onError?.(e as Error);
          message.error(e instanceof Error ? `上传失败：${e.message}` : '上传失败');
        } finally {
          setUploading(false);
        }
      },
    };
    return (
      <div style={{ marginTop: 10 }}>
        <Upload.Dragger {...uploadProps}>
          <p className="ant-upload-drag-icon">
            {uploading ? <LoadingOutlined spin /> : <InboxOutlined />}
          </p>
          <p className="ant-upload-text">
            {uploading ? '映射表上传校验中…' : '点击或拖拽上传小包号 / 大包号映射 Excel'}
          </p>
          <p className="ant-upload-hint">
            仅支持 .xlsx；首行须含「小包号」「大包号」两列，且至少一行数据
          </p>
        </Upload.Dragger>
      </div>
    );
  }

  return null;
});

/** 清关 Excel 产物下载卡片：文件名 / 行数 / 下载（同域附件响应，历史会话仍可重复下载）。 */
const FileReadyCard = memo(function FileReadyCard({ file }: { file: FileReadyPayload }) {
  return (
    <Card
      size="small"
      style={{ marginTop: 10, background: '#f6ffed', borderColor: '#b7eb8f' }}
      title={
        <Space>
          <FileExcelOutlined style={{ color: '#52c41a' }} />
          <span>清关信息已生成</span>
        </Space>
      }
      extra={
        <Button type="primary" size="small" icon={<DownloadOutlined />} href={file.downloadUrl}>
          下载 Excel
        </Button>
      }
    >
      <Typography.Text style={{ fontSize: 13 }}>{file.fileName}</Typography.Text>
      <br />
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        共 {file.rowCount} 行；历史会话中仍可通过此卡片重新下载
      </Typography.Text>
    </Card>
  );
});

/**
 * 单条消息气泡。React.memo 浅比较 props：
 * 流式输出时只有最后一条 assistant 消息对象被替换，历史消息引用不变、直接跳过渲染；
 * 打字时输入区状态已下沉，本组件完全不重渲染。
 */
const ChatMessageItem = memo(function ChatMessageItem({
  m,
  busy,
  onFeedback,
  onSlotOrderNo,
  onSlotMapping,
}: {
  m: ChatMsg;
  busy: boolean;
  onFeedback: (target: ChatMsg, type: 'USEFUL' | 'USELESS') => void;
  onSlotOrderNo: (from: ChatMsg, orderNo: string) => void;
  onSlotMapping: (from: ChatMsg, file: File) => Promise<void>;
}) {
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: m.role === 'user' ? 'flex-end' : 'flex-start',
        marginBottom: 16,
      }}
    >
      {m.role === 'assistant' && (
        <Avatar
          icon={<RobotOutlined />}
          style={{
            marginRight: 8,
            flexShrink: 0,
            background: 'linear-gradient(135deg, #6366f1, #8b5cf6)',
            boxShadow: '0 4px 12px rgba(99,102,241,0.4)',
          }}
        />
      )}
      <div
        style={{
          maxWidth: '75%',
          // 性能：气泡不再使用 backdrop-filter 模糊（多气泡叠加+滚动时背景采样成本高），
          // 改用近不透明白底，视觉层次由边框/阴影承担。
          background:
            m.role === 'user'
              ? 'linear-gradient(135deg, #6366f1 0%, #8b5cf6 55%, #d946ef 100%)'
              : 'rgba(255,255,255,0.96)',
          color: m.role === 'user' ? '#fff' : undefined,
          border: m.role === 'assistant' ? '1px solid rgba(99,102,241,0.18)' : undefined,
          borderRadius: 14,
          padding: '10px 14px',
          boxShadow:
            m.role === 'user'
              ? '0 6px 18px rgba(124,58,237,0.35)'
              : '0 4px 14px rgba(49,46,129,0.08)',
        }}
      >
        {m.role === 'assistant' && m.streaming && (m.stage || !m.content) && (
          <Space style={{ marginBottom: m.content ? 8 : 0 }}>
            <LoadingOutlined spin />
            {STAGE_ORDER.map((st) => (
              <Tag key={st} color={m.stage === st ? 'processing' : 'default'}>
                {STAGE_LABEL[st]}
              </Tag>
            ))}
            {m.rewrittenQuery && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                改写：{m.rewrittenQuery}
              </Typography.Text>
            )}
          </Space>
        )}
        {m.role === 'assistant' ? (
          <>
            {m.fallbackMode && (
              <div style={{ marginBottom: 6 }}>
                <Tag color={m.fallbackMode === 'web_search' ? 'blue' : 'orange'}>
                  {m.fallbackMode === 'web_search'
                    ? '企业知识库未命中 · 联网搜索回答'
                    : '企业知识库未命中 · 模型通用知识回答（非企业文档，请注意甄别）'}
                </Tag>
              </div>
            )}
            <AnswerText text={m.content} sources={m.sources} />
            {m.slotRequest && (
              <>
                {!m.content && (
                  <Typography.Text style={{ display: 'block', marginBottom: 2 }}>
                    {m.slotRequest.prompt}
                  </Typography.Text>
                )}
                <SlotRequestArea
                  slotRequest={m.slotRequest}
                  busy={busy}
                  onOrderNo={(v) => onSlotOrderNo(m, v)}
                  onMappingFile={(f) => onSlotMapping(m, f)}
                />
              </>
            )}
            {m.fileReady && <FileReadyCard file={m.fileReady} />}
            {m.streaming && m.content && <LoadingOutlined spin style={{ marginLeft: 6 }} />}
            {m.sources.length > 0 && !m.streaming && (
              <div style={{ marginTop: 8, borderTop: '1px dashed #eee', paddingTop: 6 }}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  引用来源：
                </Typography.Text>
                {m.sources.map((s) => (
                  <Tag key={s.index} style={{ fontSize: 12 }}>
                    [{s.index}] {s.filename}
                    {s.pageNo !== null ? ` P${s.pageNo}` : ''}
                  </Tag>
                ))}
              </div>
            )}
            {m.blocked && (
              <Alert type="warning" showIcon title="请求被拦截" description={m.blocked} style={{ marginTop: 8 }} />
            )}
            {m.error && (
              <Alert type="error" showIcon title="对话出错" description={m.error} style={{ marginTop: 8 }} />
            )}
            {!m.streaming && m.content && !m.blocked && !m.error && (
              <div style={{ marginTop: 6 }}>
                <Button
                  size="small"
                  type="text"
                  icon={<LikeOutlined />}
                  disabled={!!m.feedback}
                  onClick={() => onFeedback(m, 'USEFUL')}
                >
                  有用
                </Button>
                <Button
                  size="small"
                  type="text"
                  icon={<DislikeOutlined />}
                  disabled={!!m.feedback}
                  onClick={() => onFeedback(m, 'USELESS')}
                >
                  无用
                </Button>
                {m.feedback && (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {m.feedback === 'USEFUL' ? '已记录：有用，谢谢反馈' : '已记录：无用，我们将改进'}
                  </Typography.Text>
                )}
                {m.feedbackFailed && (
                  <Typography.Text type="danger" style={{ fontSize: 12 }}>
                    反馈失败，请重试
                  </Typography.Text>
                )}
              </div>
            )}
          </>
        ) : (
          <span style={{ whiteSpace: 'pre-wrap' }}>{m.content}</span>
        )}
      </div>
      {m.role === 'user' && (
        <Avatar
          icon={<UserOutlined />}
          style={{
            marginLeft: 8,
            flexShrink: 0,
            background: 'linear-gradient(135deg, #0891b2, #22d3ee)',
            boxShadow: '0 4px 12px rgba(8,145,178,0.4)',
          }}
        />
      )}
    </div>
  );
});

export default function ChatPanel() {
  const { message } = App.useApp();
  const router = useRouter();
  const [sessions, setSessions] = useState<{ sessionId: string; label: string }[]>([]);
  const [sessionsLoading, setSessionsLoading] = useState(true);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMsg[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const sessionIdRef = useRef<string | null>(null);
  /** 是否贴底跟随流式输出；用户上滑查看历史时暂停跟随，回到底部恢复。 */
  const stickToBottomRef = useRef(true);
  /** 一帧内多个 delta 只滚动一次，避免高频 token 造成滚动抖动。 */
  const scrollRafRef = useRef(false);

  const loadSessions = useCallback(async () => {
    setSessionsLoading(true);
    try {
      const raw = await listChatSessions();
      const list = raw
        .map((s) => {
          const id = typeof s.sessionId === 'string' ? s.sessionId : null;
          if (!id) return null;
          // 标题优先级：后端 title（首条用户提问）> preview > lastQuestion > sessionId 兜底
          const title =
            typeof s.title === 'string' && s.title
              ? s.title
              : typeof s.preview === 'string' && s.preview
                ? (s.preview as string)
                : typeof s.lastQuestion === 'string' && s.lastQuestion
                  ? (s.lastQuestion as string)
                  : id.slice(0, 8);
          return { sessionId: id, label: title };
        })
        .filter((x): x is { sessionId: string; label: string } => x !== null);
      setSessions(list);
    } catch {
      setSessions([]);
    } finally {
      setSessionsLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadSessions();
  }, [loadSessions]);

  /** 消息更新后的贴底跟随：即时跳转（无 smooth 动画）+ rAF 合帧。 */
  useEffect(() => {
    if (!stickToBottomRef.current || scrollRafRef.current) return;
    scrollRafRef.current = true;
    requestAnimationFrame(() => {
      scrollRafRef.current = false;
      const el = scrollRef.current;
      if (el && stickToBottomRef.current) el.scrollTop = el.scrollHeight;
    });
  }, [messages]);

  const openSession = useCallback(
    async (sid: string) => {
      abortRef.current?.abort();
      setSending(false);
      setSessionId(sid);
      sessionIdRef.current = sid;
      stickToBottomRef.current = true;
      setHistoryLoading(true);
      setMessages([]);
      try {
        const raw = await getChatMessages(sid);
        const msgs: ChatMsg[] = raw
          .map((m): ChatMsg | null => {
            const role = typeof m.role === 'string' ? m.role : '';
            const content = typeof m.content === 'string' ? m.content : '';
            if (role === 'user') return { _uid: genUid(), role: 'user', content, sources: [], stage: null };
            if (role === 'assistant')
              return {
                _uid: genUid(),
                role: 'assistant',
                content,
                // 历史接口回传的引用来源：还原正文 [n] 角标 Popover 与底部来源区
                sources: normalizeSources(m.sources),
                stage: null,
              };
            return null;
          })
          .filter((x): x is ChatMsg => x !== null);
        setMessages(msgs);
      } catch (e) {
        message.error(e instanceof Error ? e.message : '历史消息加载失败');
      } finally {
        setHistoryLoading(false);
      }
    },
    [message],
  );

  const newSession = useCallback(() => {
    abortRef.current?.abort();
    setSending(false);
    setSessionId(null);
    sessionIdRef.current = null;
    stickToBottomRef.current = true;
    setMessages([]);
  }, []);

  const updateLastAssistant = useCallback((fn: (m: ChatMsg) => ChatMsg) => {
    setMessages((prev) => {
      const next = [...prev];
      const lastIdx = next.length - 1;
      if (lastIdx >= 0 && next[lastIdx].role === 'assistant') {
        next[lastIdx] = fn(next[lastIdx]);
      }
      return next;
    });
  }, []);

  /**
   * SSE 事件统一处理：知识问答与业务意图共用同一套事件。
   * slot_request → 气泡挂起进入补槽态（结束 sending，输入在气泡内完成）；
   * file_ready → 挂下载卡片。注意 slot_request 后不会再收到 done。
   */
  const handleStreamEvent = useCallback(
    (name: string, data: unknown) => {
      switch (name) {
        case 'session': {
          const sid = (data as { sessionId?: string }).sessionId;
          if (sid) {
            sessionIdRef.current = sid;
            setSessionId(sid);
            updateLastAssistant((m) => ({ ...m, sid }));
          }
          break;
        }
        case 'stage': {
          const d = data as { stage?: ChatStage; rewrittenQuery?: string; mode?: string };
          updateLastAssistant((m) => ({
            ...m,
            stage: d.stage ?? null,
            rewrittenQuery: d.rewrittenQuery ?? m.rewrittenQuery,
            fallbackMode: d.stage === 'fallback' ? d.mode ?? m.fallbackMode : m.fallbackMode,
          }));
          break;
        }
        case 'sources': {
          if (Array.isArray(data)) {
            updateLastAssistant((m) => ({ ...m, sources: data as Source[] }));
          }
          break;
        }
        case 'delta': {
          const text = (data as { text?: string }).text ?? '';
          updateLastAssistant((m) => ({ ...m, content: m.content + text }));
          break;
        }
        case 'slot_request': {
          const payload = data as SlotRequestPayload;
          updateLastAssistant((m) => ({ ...m, slotRequest: payload, streaming: false, stage: null }));
          setSending(false);
          break;
        }
        case 'file_ready': {
          const payload = data as FileReadyPayload;
          updateLastAssistant((m) => ({ ...m, fileReady: payload }));
          break;
        }
        case 'blocked': {
          const msg = (data as { message?: string }).message ?? '请求被拦截';
          updateLastAssistant((m) => ({ ...m, blocked: msg, streaming: false, stage: null }));
          setSending(false);
          break;
        }
        case 'error': {
          const msg = (data as { message?: string }).message ?? '对话出错';
          updateLastAssistant((m) => ({ ...m, error: msg, streaming: false, stage: null }));
          setSending(false);
          break;
        }
        case 'done': {
          updateLastAssistant((m) => ({ ...m, streaming: false, stage: null }));
          setSending(false);
          void loadSessions();
          break;
        }
        default:
          break;
      }
    },
    [loadSessions, updateLastAssistant],
  );

  /**
   * 发起一轮对话并打开 SSE 流。clearUid 非空（缺槽追问续跑）时，
   * 先抹掉原追问气泡上的 slotRequest，再追加 user/assistant 两条气泡；
   * attachmentFileId 用于映射表上传后的续跑轮。
   */
  const startTurn = useCallback(
    (question: string, attachmentFileId?: string, clearUid?: string) => {
      setSending(true);
      stickToBottomRef.current = true;
      setMessages((prev) => {
        const base = clearUid
          ? prev.map((m) => (m._uid === clearUid ? { ...m, slotRequest: undefined } : m))
          : prev;
        return [
          ...base,
          {
            _uid: genUid(),
            role: 'user' as const,
            content: question,
            sources: [] as Source[],
            stage: null,
          },
          emptyAssistant(),
        ];
      });

      const settings = getSettings();
      abortRef.current = chatStream(
        {
          question,
          domain: settings.domain || undefined,
          subDomain: settings.subDomain || undefined,
          identity: settings.identity || undefined,
          sessionId: sessionIdRef.current ?? undefined,
          attachmentFileId,
        },
        handleStreamEvent,
      );
    },
    [handleStreamEvent],
  );

  /** 普通发送（知识问答或被路由回知识链路的输入）。 */
  const send = useCallback((question: string) => startTurn(question), [startTurn]);

  /** 缺槽-订单号：追问气泡内提交的文本即新一轮 question，由后端 ActGate 正则解析。 */
  const submitSlotOrderNo = useCallback(
    (from: ChatMsg, orderNo: string) => startTurn(orderNo, undefined, from._uid),
    [startTurn],
  );

  /** 缺槽-映射表：先上传（后端同步校验列头/行数，422 会抛错），拿 fileId 后续跑对话轮。 */
  const submitSlotMapping = useCallback(
    async (from: ChatMsg, file: File) => {
      const uploaded = await uploadBusinessMapping(file);
      startTurn('映射表已上传，请生成清关信息', uploaded.fileId, from._uid);
    },
    [startTurn],
  );

  useEffect(() => {
    return () => abortRef.current?.abort();
  }, []);

  /** 中断当前 SSE 流（老 8080 页「停止」按钮能力）。 */
  const stop = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    updateLastAssistant((m) => ({
      ...m,
      streaming: false,
      stage: null,
      content: m.content ? `${m.content}\n\n（已停止）` : m.content,
    }));
    setSending(false);
  }, [updateLastAssistant]);

  /** 👍/👎 会话级反馈：写入 kb_feedback，是治理看板"疑似无用知识"判定与可观测反馈审计的数据源。 */
  const sendFeedback = useCallback(async (target: ChatMsg, type: 'USEFUL' | 'USELESS') => {
    const sid = target.sid ?? sessionIdRef.current;
    if (!sid) return;
    const targetUid = target._uid;
    try {
      await submitFeedback({ sessionId: sid, conversationId: sid, chunkId: null, feedbackType: type });
      setMessages((prev) => prev.map((m) => (m._uid === targetUid ? { ...m, feedback: type, feedbackFailed: false } : m)));
    } catch {
      setMessages((prev) => prev.map((m) => (m._uid === targetUid ? { ...m, feedbackFailed: true } : m)));
    }
  }, []);

  /** 滚动事件：距底部 40px 内视为贴底，继续跟随；上滑则暂停自动滚动。 */
  const handleScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    stickToBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
  }, []);

  /** 当前会话标题：优先取会话列表 label（后端 title），新会话则用首条用户提问即时生成。 */
  const currentTitle = (() => {
    if (!sessionId) return null;
    const found = sessions.find((s) => s.sessionId === sessionId);
    if (found) return found.label;
    const firstUser = messages.find((m) => m.role === 'user');
    if (firstUser) {
      const oneLine = firstUser.content.replace(/\s+/g, ' ').trim();
      return oneLine.length > 40 ? `${oneLine.slice(0, 40)}…` : oneLine;
    }
    return null;
  })();

  return (
    <div style={{ display: 'flex', gap: 16, height: 'calc(100vh - 160px)' }}>
      <Card
        title="会话列表"
        style={{ width: 260, flexShrink: 0, overflow: 'auto' }}
        extra={
          <Button size="small" icon={<PlusOutlined />} onClick={newSession}>
            新建会话
          </Button>
        }
      >
        {sessionsLoading ? (
          <Skeleton active paragraph={{ rows: 4 }} />
        ) : sessions.length === 0 ? (
          <Empty description="暂无历史会话" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <List
            dataSource={sessions}
            renderItem={(s) => (
              <List.Item
                style={{
                  cursor: 'pointer',
                  background:
                    s.sessionId === sessionId
                      ? 'linear-gradient(135deg, rgba(99,102,241,0.16), rgba(217,70,239,0.12))'
                      : undefined,
                  border:
                    s.sessionId === sessionId ? '1px solid rgba(99,102,241,0.25)' : undefined,
                  padding: '8px 12px',
                  borderRadius: 10,
                }}
                onClick={() => void openSession(s.sessionId)}
              >
                <Typography.Text ellipsis style={{ fontSize: 13 }}>
                  {s.label}
                </Typography.Text>
              </List.Item>
            )}
          />
        )}
      </Card>

      <Card
        title={
          <Space size={8}>
            {currentTitle ? (
              <Typography.Text strong copyable={{ text: currentTitle }} style={{ fontSize: 15 }}>
                {currentTitle}
              </Typography.Text>
            ) : (
              <span>对话</span>
            )}
            {currentTitle && sessionId && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                /
              </Typography.Text>
            )}
            {sessionId && (
              <Typography.Text type="secondary" style={{ fontSize: 11 }} copyable={{ text: sessionId }}>
                {sessionId}
              </Typography.Text>
            )}
          </Space>
        }
        style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
        styles={{ body: { flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' } }}
        extra={
          sessionId ? (
            <Button
              size="small"
              onClick={() => router.push(`/?tab=observe&sessionId=${encodeURIComponent(sessionId)}`)}
            >
              查链路
            </Button>
          ) : undefined
        }
      >
        <div
          ref={scrollRef}
          onScroll={handleScroll}
          style={{ flex: 1, overflow: 'auto', paddingRight: 8 }}
        >
          {historyLoading ? (
            <Skeleton active paragraph={{ rows: 6 }} />
          ) : messages.length === 0 ? (
            <Empty
              style={{ marginTop: 120 }}
              description={
                <span>
                  暂无对话内容
                  <br />
                  在下方输入问题，开始与知识库对话
                </span>
              }
            />
          ) : (
            messages.map((m) => (
              <ChatMessageItem
                key={m._uid}
                m={m}
                busy={sending}
                onFeedback={sendFeedback}
                onSlotOrderNo={submitSlotOrderNo}
                onSlotMapping={submitSlotMapping}
              />
            ))
          )}
        </div>

        <div style={{ borderTop: '1px solid #f0f0f0', paddingTop: 12 }}>
          <ChatComposer sending={sending} onSend={send} onStop={stop} />
        </div>
      </Card>
    </div>
  );
}
