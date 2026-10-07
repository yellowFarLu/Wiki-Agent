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
} from 'antd';
import {
  DislikeOutlined,
  LikeOutlined,
  LoadingOutlined,
  PlusOutlined,
  RobotOutlined,
  SendOutlined,
  StopOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useRouter } from 'next/navigation';
import { getChatMessages, listChatSessions, submitFeedback } from '@/lib/api';
import { chatStream } from '@/lib/sse';
import { getSettings } from '@/lib/settings';
import type { ChatStage, Source } from '@/lib/types';
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
 * 单条消息气泡。React.memo 浅比较 props：
 * 流式输出时只有最后一条 assistant 消息对象被替换，历史消息引用不变、直接跳过渲染；
 * 打字时输入区状态已下沉，本组件完全不重渲染。
 */
const ChatMessageItem = memo(function ChatMessageItem({
  m,
  onFeedback,
}: {
  m: ChatMsg;
  onFeedback: (target: ChatMsg, type: 'USEFUL' | 'USELESS') => void;
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

  const send = useCallback(
    (question: string) => {
      setSending(true);
      stickToBottomRef.current = true;
      setMessages((prev) => [
        ...prev,
        { _uid: genUid(), role: 'user', content: question, sources: [], stage: null },
        emptyAssistant(),
      ]);

      const settings = getSettings();
      abortRef.current = chatStream(
        {
          question,
          domain: settings.domain || undefined,
          subDomain: settings.subDomain || undefined,
          identity: settings.identity || undefined,
          sessionId: sessionIdRef.current ?? undefined,
        },
        (name, data) => {
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
      );
    },
    [loadSessions, updateLastAssistant],
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
            messages.map((m) => <ChatMessageItem key={m._uid} m={m} onFeedback={sendFeedback} />)
          )}
        </div>

        <div style={{ borderTop: '1px solid #f0f0f0', paddingTop: 12 }}>
          <ChatComposer sending={sending} onSend={send} onStop={stop} />
        </div>
      </Card>
    </div>
  );
}
