'use client';

import { getSettings } from './settings';

export interface ChatRequestBody {
  question: string;
  domain?: string;
  subDomain?: string;
  identity?: string;
  sessionId?: string;
  /** 本轮上传附件 fileId（清关意图：先上传映射表拿到 fileId 再随对话轮带上，作为 USER_UPLOAD 证据）。 */
  attachmentFileId?: string;
}

export type ChatEventHandler = (eventName: string, data: unknown) => void;

/**
 * 对话流：POST /api/chat，读取 SSE 流并按事件回调。
 * 返回 AbortController 供调用方中止。
 */
export function chatStream(
  body: ChatRequestBody,
  onEvent: ChatEventHandler,
): AbortController {
  const controller = new AbortController();
  const settings = getSettings();

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'X-User-Id': settings.userId || 'anonymous',
  };
  if (settings.identity) headers['X-Business-Identity'] = settings.identity;

  void (async () => {
    try {
      const res = await fetch('/api/chat', {
        method: 'POST',
        headers,
        body: JSON.stringify(body),
        signal: controller.signal,
      });
      if (!res.ok || !res.body) {
        let message = `对话请求失败（HTTP ${res.status}）`;
        try {
          const errBody = (await res.json()) as { message?: string };
          if (errBody?.message) message = errBody.message;
        } catch {
          // 忽略
        }
        onEvent('error', { message });
        return;
      }
      await parseSseStream(res.body, onEvent, controller.signal);
    } catch (e) {
      if (controller.signal.aborted) return;
      onEvent('error', { message: e instanceof Error ? e.message : '网络异常' });
    }
  })();

  return controller;
}

async function parseSseStream(
  body: ReadableStream<Uint8Array>,
  onEvent: ChatEventHandler,
  signal: AbortSignal,
): Promise<void> {
  const reader = body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  let eventName = 'message';
  let dataLines: string[] = [];

  const flush = () => {
    if (dataLines.length === 0) {
      eventName = 'message';
      return;
    }
    const raw = dataLines.join('\n');
    dataLines = [];
    let data: unknown = raw;
    try {
      data = JSON.parse(raw);
    } catch {
      // 非 JSON 原样传递
    }
    onEvent(eventName, data);
    eventName = 'message';
  };

  const processLine = (line: string) => {
    if (line === '') {
      flush();
      return;
    }
    if (line.startsWith(':')) return; // 注释/心跳
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim();
      return;
    }
    if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''));
    }
  };

  for (;;) {
    if (signal.aborted) {
      await reader.cancel().catch(() => undefined);
      return;
    }
    const { done, value } = await reader.read();
    if (done) {
      buffer += decoder.decode();
      // 处理末尾残留
      const tail = buffer.split('\n');
      for (const l of tail) processLine(l.replace(/\r$/, ''));
      flush();
      return;
    }
    buffer += decoder.decode(value, { stream: true });
    let idx: number;
    while ((idx = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, idx).replace(/\r$/, '');
      buffer = buffer.slice(idx + 1);
      processLine(line);
    }
  }
}

// ============ 任务进度流 ============

export interface TaskStreamHandlers {
  onEvent: (eventName: string, data: unknown) => void;
  onReconnecting?: (attempt: number, nextDelayMs: number) => void;
  onReconnect?: () => void;
  onGiveUp?: () => void;
}

export interface TaskStreamSubscription {
  close: () => void;
}

const MAX_RETRIES = 5;

/**
 * 订阅任务 SSE 流，断线指数退避自动重连（最多 5 次）。
 * 收到 done/error 事件后主动关闭，不再重连。
 *
 * 实现说明：用 fetch 流式读取而非 EventSource，以便携带 X-User-Id / X-Business-Identity
 * 身份头（EventSource 不支持自定义头，规格 §2.7 要求全链路带头）。
 */
export function subscribeTaskStream(
  taskId: string,
  handlers: TaskStreamHandlers,
): TaskStreamSubscription {
  const controller = new AbortController();
  let retries = 0;
  let closed = false;

  const close = () => {
    closed = true;
    controller.abort();
  };

  void (async () => {
    while (!closed) {
      const settings = getSettings();
      const headers: Record<string, string> = {
        'X-User-Id': settings.userId || 'anonymous',
      };
      if (settings.identity) headers['X-Business-Identity'] = settings.identity;

      let terminal = false;
      try {
        const res = await fetch(`/api/tasks/${encodeURIComponent(taskId)}/stream`, {
          headers,
          signal: controller.signal,
        });
        if (!res.ok || !res.body) {
          throw new Error(`任务流请求失败（HTTP ${res.status}）`);
        }
        await parseSseStream(
          res.body,
          (name, data) => {
            handlers.onEvent(name, data);
            if (name === 'done' || name === 'error') {
              terminal = true;
            }
          },
          controller.signal,
        );
      } catch {
        // 中止或网络异常均走重连判断
      }

      if (closed || terminal) break;
      if (retries >= MAX_RETRIES) {
        handlers.onGiveUp?.();
        break;
      }
      const delay = Math.min(1000 * 2 ** retries, 16000);
      retries += 1;
      handlers.onReconnecting?.(retries, delay);
      await new Promise((r) => setTimeout(r, delay));
      if (!closed) handlers.onReconnect?.();
    }
  })();

  return { close };
}
