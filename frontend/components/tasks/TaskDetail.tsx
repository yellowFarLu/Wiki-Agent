'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  Descriptions,
  Empty,
  Popconfirm,
  Progress,
  Space,
  Spin,
  Steps,
  Tag,
  Timeline,
  Typography,
} from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import Link from 'next/link';
import {
  cancelTask,
  getTask,
  getTaskEvents,
  getTaskHumanTasks,
  getTaskSteps,
  replayTask,
  resumeTask,
  suspendTask,
} from '@/lib/api';
import { subscribeTaskStream, type TaskStreamSubscription } from '@/lib/sse';
import { formatDateTime } from '@/lib/datetime';
import type { EventView, HumanTaskView, StepView, TaskView } from '@/lib/types';
import TaskStatusTag from '@/components/common/TaskStatusTag';

const TERMINAL: ReadonlySet<string> = new Set(['COMPLETED', 'FAILED', 'CANCELLED']);

function stepStatus(s: StepView['status']): 'wait' | 'process' | 'finish' | 'error' {
  switch (s) {
    case 'DONE':
      return 'finish';
    case 'RUNNING':
      return 'process';
    case 'FAILED':
      return 'error';
    case 'PENDING':
      return 'wait';
    case 'SKIPPED':
      return 'finish';
    default:
      return 'wait';
  }
}

function renderDetail(detail: unknown): string {
  if (detail === null || detail === undefined) return '';
  if (typeof detail === 'string') return detail;
  try {
    return JSON.stringify(detail, null, 2);
  } catch {
    return String(detail);
  }
}

export default function TaskDetail({ taskId }: { taskId: string }) {
  const { message } = App.useApp();
  const [task, setTask] = useState<TaskView | null>(null);
  const [steps, setSteps] = useState<StepView[]>([]);
  const [events, setEvents] = useState<EventView[]>([]);
  const [humanTasks, setHumanTasks] = useState<HumanTaskView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reconnecting, setReconnecting] = useState<string | null>(null);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const subRef = useRef<TaskStreamSubscription | null>(null);

  const stopStream = useCallback(() => {
    subRef.current?.close();
    subRef.current = null;
  }, []);

  const loadSnapshot = useCallback(async () => {
    setError(null);
    try {
      const [t, s, e, h] = await Promise.all([
        getTask(taskId),
        getTaskSteps(taskId),
        getTaskEvents(taskId),
        getTaskHumanTasks(taskId),
      ]);
      setTask(t);
      setSteps(s);
      setEvents(e);
      setHumanTasks(h);
      return t;
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载失败');
      return null;
    }
  }, [taskId]);

  const startStream = useCallback(() => {
    stopStream();
    subRef.current = subscribeTaskStream(taskId, {
      onEvent: (name, data) => {
        if (name === 'progress' && data && typeof data === 'object') {
          const p = (data as Record<string, unknown>).progressPercent;
          if (typeof p === 'number') {
            setTask((prev) => (prev ? { ...prev, progressPercent: p } : prev));
          }
        }
        if (name === 'done' || name === 'error') {
          setReconnecting(null);
          void loadSnapshot();
        }
      },
      onReconnecting: (attempt, delay) => {
        setReconnecting(`连接中断，重连中…（第 ${attempt} 次，${Math.round(delay / 1000)} 秒后重试）`);
      },
      onReconnect: () => setReconnecting(null),
      onGiveUp: () => setReconnecting('连接已断开且重连失败，请点击「刷新」手动更新'),
    });
  }, [taskId, stopStream, loadSnapshot]);

  useEffect(() => {
    void (async () => {
      setLoading(true);
      const t = await loadSnapshot();
      setLoading(false);
      if (t && !TERMINAL.has(t.status)) {
        startStream();
      }
    })();
    return () => stopStream();
  }, [loadSnapshot, startStream, stopStream]);

  const doAction = async (action: 'suspend' | 'resume' | 'cancel' | 'replay') => {
    setActionLoading(action);
    try {
      const updated =
        action === 'suspend'
          ? await suspendTask(taskId)
          : action === 'resume'
            ? await resumeTask(taskId)
            : action === 'cancel'
              ? await cancelTask(taskId)
              : await replayTask(taskId);
      setTask(updated);
      message.success('操作成功');
      if (!TERMINAL.has(updated.status)) {
        startStream();
      } else {
        stopStream();
      }
      await loadSnapshot();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败');
    } finally {
      setActionLoading(null);
    }
  };

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: 80 }}>
        <Spin size="large" description="任务详情加载中…">
          <div style={{ width: 200, height: 80 }} />
        </Spin>
      </div>
    );
  }

  if (error && !task) {
    return (
      <Alert
        type="error"
        showIcon
        title="任务详情加载失败"
        description={error}
        action={
          <Button
            size="small"
            onClick={() => {
              setLoading(true);
              void loadSnapshot().finally(() => setLoading(false));
            }}
          >
            重试
          </Button>
        }
      />
    );
  }

  if (!task) {
    return <Empty description="任务不存在" />;
  }

  const canSuspend = task.status === 'RUNNING' || task.status === 'PENDING' || task.status === 'WAITING_HUMAN';
  const canResume = task.status === 'SUSPENDED';
  const canCancel = !TERMINAL.has(task.status);
  const canReplay = task.status === 'FAILED' || task.status === 'CANCELLED' || task.status === 'COMPLETED';

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {reconnecting && <Alert type="warning" showIcon title={reconnecting} />}

      <Card
        title={
          <Space>
            <span>任务详情</span>
            <Typography.Text code copyable>
              {task.taskId}
            </Typography.Text>
          </Space>
        }
        extra={
          <Space>
            {canSuspend && (
              <Popconfirm title="确认暂停该任务？" onConfirm={() => doAction('suspend')}>
                <Button loading={actionLoading === 'suspend'}>暂停</Button>
              </Popconfirm>
            )}
            {canResume && (
              <Popconfirm title="确认恢复该任务？" onConfirm={() => doAction('resume')}>
                <Button type="primary" loading={actionLoading === 'resume'}>
                  恢复
                </Button>
              </Popconfirm>
            )}
            {canCancel && (
              <Popconfirm title="确认取消该任务？" onConfirm={() => doAction('cancel')}>
                <Button danger loading={actionLoading === 'cancel'}>
                  取消
                </Button>
              </Popconfirm>
            )}
            {canReplay && (
              <Popconfirm title="确认重放该任务？" onConfirm={() => doAction('replay')}>
                <Button loading={actionLoading === 'replay'}>重放</Button>
              </Popconfirm>
            )}
            <Button
              icon={<ReloadOutlined />}
              onClick={() => {
                setLoading(true);
                void loadSnapshot().finally(() => setLoading(false));
              }}
            >
              刷新
            </Button>
          </Space>
        }
      >
        <Descriptions bordered size="small" column={3}>
          <Descriptions.Item label="状态">
            <TaskStatusTag status={task.status} />
          </Descriptions.Item>
          <Descriptions.Item label="类型">{task.taskType}</Descriptions.Item>
          <Descriptions.Item label="业务键">{task.bizKey}</Descriptions.Item>
          <Descriptions.Item label="进度">
            <Progress
              percent={task.progressPercent ?? 0}
              size="small"
              style={{ minWidth: 160 }}
              status={task.status === 'FAILED' ? 'exception' : undefined}
            />
          </Descriptions.Item>
          <Descriptions.Item label="尝试次数">
            {task.attempt}/{task.maxAttempts}
          </Descriptions.Item>
          <Descriptions.Item label="提交人">{task.submittedBy}</Descriptions.Item>
          <Descriptions.Item label="创建时间">{formatDateTime(task.createdAt)}</Descriptions.Item>
          <Descriptions.Item label="更新时间">{formatDateTime(task.updatedAt)}</Descriptions.Item>
          <Descriptions.Item label="租约持有">{task.leaseOwner ?? '-'}</Descriptions.Item>
          {task.errorMsg && (
            <Descriptions.Item label="错误信息" span={3}>
              <Typography.Text type="danger">
                {task.errorCode ? `[${task.errorCode}] ` : ''}
                {task.errorMsg}
              </Typography.Text>
            </Descriptions.Item>
          )}
          {task.suspendReason && (
            <Descriptions.Item label="暂停原因" span={3}>
              {task.suspendReason}
            </Descriptions.Item>
          )}
          {task.resultRef && (
            <Descriptions.Item label="结果引用" span={3}>
              {task.resultRef}
            </Descriptions.Item>
          )}
        </Descriptions>
      </Card>

      <Card title="执行步骤">
        {steps.length === 0 ? (
          <Empty description="暂无步骤" />
        ) : (
          <Steps
            direction="vertical"
            current={steps.findIndex((s) => s.status === 'RUNNING')}
            items={steps.map((s) => ({
              title: (
                <span style={{ color: s.status === 'SKIPPED' ? '#999' : undefined }}>
                  {s.stepNo}. {s.stepName}
                  {s.status === 'SKIPPED' && <Tag style={{ marginLeft: 8 }}>已跳过</Tag>}
                </span>
              ),
              status: stepStatus(s.status),
              description: (
                <div>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {s.stepType}
                    {s.startedAt ? ` ｜ 开始 ${formatDateTime(s.startedAt)}` : ''}
                    {s.endedAt ? ` ｜ 结束 ${formatDateTime(s.endedAt)}` : ''}
                  </Typography.Text>
                  {s.errorMsg && (
                    <div>
                      <Typography.Text type="danger" style={{ fontSize: 12 }}>
                        {s.errorMsg}
                      </Typography.Text>
                    </div>
                  )}
                </div>
              ),
            }))}
          />
        )}
      </Card>

      <Card title="人工任务">
        {humanTasks.length === 0 ? (
          <Empty description="暂无人工任务" />
        ) : (
          <Timeline
            items={humanTasks.map((h) => ({
              key: h.id,
              color:
                h.status === 'RESOLVED' ? 'green' : h.status === 'OPEN' || h.status === 'CLAIMED' ? 'blue' : 'gray',
              children: (
                <div>
                  <Space>
                    <Typography.Text strong>{h.title}</Typography.Text>
                    <Tag>{h.kind}</Tag>
                    <Tag color={h.status === 'OPEN' ? 'gold' : h.status === 'CLAIMED' ? 'blue' : 'default'}>
                      {h.status}
                    </Tag>
                    {(h.status === 'OPEN' || h.status === 'CLAIMED') && (
                      <Link href="/?tab=workbench">前往工作台处理</Link>
                    )}
                  </Space>
                  <div>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {h.instruction}
                    </Typography.Text>
                  </div>
                </div>
              ),
            }))}
          />
        )}
      </Card>

      <Card title="事件时间线">
        {events.length === 0 ? (
          <Empty description="暂无事件" />
        ) : (
          <Timeline
            items={events.map((e, idx) => ({
              key: idx,
              children: (
                <div>
                  <Space>
                    <Typography.Text strong>{e.eventType}</Typography.Text>
                    <Tag>{e.actorType}</Tag>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {e.actorId} ｜ {formatDateTime(e.createdAt)}
                    </Typography.Text>
                  </Space>
                  {e.detail !== null && e.detail !== undefined && (
                    <pre
                      style={{
                        marginTop: 4,
                        fontSize: 12,
                        background: '#fafafa',
                        padding: 8,
                        borderRadius: 4,
                        maxHeight: 200,
                        overflow: 'auto',
                      }}
                    >
                      {renderDetail(e.detail)}
                    </pre>
                  )}
                </div>
              ),
            }))}
          />
        )}
      </Card>
    </Space>
  );
}
