'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Empty,
  Form,
  Input,
  Modal,
  Skeleton,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  claimHumanTask,
  getTaskHumanTasks,
  listTasks,
  resolveHumanTask,
} from '@/lib/api';
import { formatDateTime } from '@/lib/datetime';
import type { HumanTaskView, TaskView } from '@/lib/types';
import TaskStatusTag from '@/components/common/TaskStatusTag';

const KIND_LABEL: Record<string, string> = {
  INPUT: '输入',
  REVIEW: '复核',
  TOOL_APPROVAL: '工具审批',
  DECRYPT: '解密',
};

/** 从 formSchema（未知 JSON）中提取可渲染的字段列表 */
function extractFields(formSchema: unknown): { key: string; label: string }[] {
  if (!formSchema || typeof formSchema !== 'object') return [];
  const obj = formSchema as Record<string, unknown>;
  // 形态一：{ fields: [{name|key, label}] }
  if (Array.isArray(obj.fields)) {
    return obj.fields
      .map((f) => {
        if (!f || typeof f !== 'object') return null;
        const rec = f as Record<string, unknown>;
        const key = typeof rec.name === 'string' ? rec.name : typeof rec.key === 'string' ? rec.key : null;
        if (!key) return null;
        const label = typeof rec.label === 'string' ? rec.label : key;
        return { key, label };
      })
      .filter((x): x is { key: string; label: string } => x !== null);
  }
  // 形态二：JSON Schema { properties: { key: {title?} } }
  if (obj.properties && typeof obj.properties === 'object') {
    return Object.entries(obj.properties as Record<string, unknown>).map(([key, v]) => {
      const title =
        v && typeof v === 'object' && typeof (v as Record<string, unknown>).title === 'string'
          ? ((v as Record<string, unknown>).title as string)
          : key;
      return { key, label: title };
    });
  }
  return [];
}

function renderJson(v: unknown): string {
  if (v === null || v === undefined) return '';
  if (typeof v === 'string') return v;
  try {
    return JSON.stringify(v, null, 2);
  } catch {
    return String(v);
  }
}

export default function HumanTasksTab({ onGoReview }: { onGoReview: () => void }) {
  const { message } = App.useApp();
  const [tasks, setTasks] = useState<TaskView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [humanTasksMap, setHumanTasksMap] = useState<Record<string, HumanTaskView[]>>({});
  const [expandLoading, setExpandLoading] = useState<Record<string, boolean>>({});
  const [active, setActive] = useState<HumanTaskView | null>(null);
  const [formValues, setFormValues] = useState<Record<string, unknown>>({});
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setTasks(await listTasks({ status: 'WAITING_HUMAN', mine: true }));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const loadHumanTasks = async (taskId: string) => {
    setExpandLoading((prev) => ({ ...prev, [taskId]: true }));
    try {
      const list = await getTaskHumanTasks(taskId);
      setHumanTasksMap((prev) => ({ ...prev, [taskId]: list }));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '人工任务加载失败');
    } finally {
      setExpandLoading((prev) => ({ ...prev, [taskId]: false }));
    }
  };

  const claim = async (h: HumanTaskView) => {
    try {
      const updated = await claimHumanTask(h.id);
      message.success('认领成功');
      setHumanTasksMap((prev) => ({
        ...prev,
        [h.taskId]: (prev[h.taskId] ?? []).map((x) => (x.id === h.id ? updated : x)),
      }));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '认领失败');
    }
  };

  const openHandle = (h: HumanTaskView) => {
    setActive(h);
    const fields = extractFields(h.formSchema);
    const init: Record<string, unknown> = {};
    for (const f of fields) init[f.key] = '';
    setFormValues(init);
  };

  const doResolve = async (kind: 'INPUT' | 'DIRECT_RESOLVE', values?: Record<string, unknown>) => {
    if (!active) return;
    setSubmitting(true);
    try {
      await resolveHumanTask(active.id, { kind, formValue: values ?? formValues });
      message.success('处理完成');
      setActive(null);
      await loadHumanTasks(active.taskId);
      void load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '处理失败');
    } finally {
      setSubmitting(false);
    }
  };

  const columns: ColumnsType<TaskView> = [
    {
      title: '任务 ID',
      dataIndex: 'taskId',
      key: 'taskId',
      width: 140,
      render: (v: string) => <Typography.Text code>{v.slice(0, 8)}…</Typography.Text>,
    },
    { title: '类型', dataIndex: 'taskType', key: 'taskType', width: 160 },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 120,
      render: (v: string) => <TaskStatusTag status={v} />,
    },
    { title: '业务键', dataIndex: 'bizKey', key: 'bizKey' },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', width: 180, render: (v: string | null) => formatDateTime(v) },
  ];

  const humanColumns: ColumnsType<HumanTaskView> = [
    { title: '标题', dataIndex: 'title', key: 'title' },
    {
      title: '类型',
      dataIndex: 'kind',
      key: 'kind',
      width: 110,
      render: (v: string) => <Tag color="blue">{KIND_LABEL[v] ?? v}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (v: string) => (
        <Tag color={v === 'OPEN' ? 'gold' : v === 'CLAIMED' ? 'blue' : 'default'}>{v}</Tag>
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 200,
      render: (_, h) => {
        if (h.kind === 'REVIEW' && (h.status === 'OPEN' || h.status === 'CLAIMED')) {
          return (
            <Button size="small" type="link" onClick={onGoReview}>
              前往复核案件
            </Button>
          );
        }
        if (h.status === 'OPEN') {
          return (
            <Space>
              <Button size="small" onClick={() => claim(h)}>
                认领
              </Button>
              <Button size="small" type="primary" onClick={() => openHandle(h)}>
                处理
              </Button>
            </Space>
          );
        }
        if (h.status === 'CLAIMED') {
          return (
            <Button size="small" type="primary" onClick={() => openHandle(h)}>
              处理
            </Button>
          );
        }
        return <Typography.Text type="secondary">已完成</Typography.Text>;
      },
    },
  ];

  const activeFields = active ? extractFields(active.formSchema) : [];

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Typography.Text type="secondary">
          展示我提交的「等待人工」任务，展开行查看并处理其中的人工任务
        </Typography.Text>
        <Button onClick={load} loading={loading}>
          刷新
        </Button>
      </Space>

      {error && (
        <Alert
          type="error"
          showIcon
          title="等待人工任务加载失败"
          description={error}
          style={{ marginBottom: 16 }}
          action={
            <Button size="small" onClick={load}>
              重试
            </Button>
          }
        />
      )}

      <Table<TaskView>
        rowKey="taskId"
        columns={columns}
        dataSource={tasks}
        loading={loading}
        pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }}
        locale={{ emptyText: <Empty description="暂无等待人工处理的任务" /> }}
        expandable={{
          onExpand: (expanded, record) => {
            if (expanded) void loadHumanTasks(record.taskId);
          },
          expandedRowRender: (record) => {
            if (expandLoading[record.taskId]) return <Skeleton active paragraph={{ rows: 2 }} />;
            const list = humanTasksMap[record.taskId] ?? [];
            if (list.length === 0) return <Empty description="该任务暂无人工任务" image={Empty.PRESENTED_IMAGE_SIMPLE} />;
            return (
              <Table<HumanTaskView>
                rowKey="id"
                columns={humanColumns}
                dataSource={list}
                pagination={false}
                size="small"
              />
            );
          },
        }}
      />

      <Modal
        title={active ? `处理：${active.title}` : '处理人工任务'}
        open={active !== null}
        onCancel={() => setActive(null)}
        footer={null}
        width={560}
      >
        {active && (
          <div>
            <Typography.Paragraph type="secondary">{active.instruction}</Typography.Paragraph>

            {active.kind === 'DECRYPT' && (
              <Form layout="vertical">
                <Form.Item label="解密口令" required>
                  <Input.Password
                    placeholder="请输入解密口令"
                    value={(formValues.password as string) ?? ''}
                    onChange={(e) =>
                      setFormValues((prev) => ({ ...prev, password: e.target.value }))
                    }
                  />
                </Form.Item>
                <Button
                  type="primary"
                  loading={submitting}
                  onClick={() => doResolve('INPUT', { password: formValues.password ?? '' })}
                >
                  提交口令
                </Button>
              </Form>
            )}

            {active.kind === 'TOOL_APPROVAL' && (
              <div>
                <Typography.Title level={5}>待审批参数</Typography.Title>
                <pre
                  style={{
                    background: '#fafafa',
                    border: '1px solid #f0f0f0',
                    borderRadius: 4,
                    padding: 12,
                    fontSize: 12,
                    maxHeight: 280,
                    overflow: 'auto',
                  }}
                >
                  {renderJson(active.formSchema) || '（无参数）'}
                </pre>
                <Space>
                  <Button
                    type="primary"
                    loading={submitting}
                    onClick={() => doResolve('INPUT', { approved: true })}
                  >
                    批准
                  </Button>
                  <Button danger loading={submitting} onClick={() => doResolve('DIRECT_RESOLVE')}>
                    驳回
                  </Button>
                </Space>
              </div>
            )}

            {active.kind === 'INPUT' && (
              <Form layout="vertical">
                {activeFields.length === 0 && (
                  <Alert
                    type="info"
                    showIcon
                    style={{ marginBottom: 12 }}
                    title="未识别表单结构，请查看原始定义"
                    description={
                      <pre style={{ fontSize: 12, margin: 0 }}>{renderJson(active.formSchema)}</pre>
                    }
                  />
                )}
                {activeFields.map((f) => (
                  <Form.Item key={f.key} label={f.label}>
                    <Input
                      value={(formValues[f.key] as string) ?? ''}
                      onChange={(e) =>
                        setFormValues((prev) => ({ ...prev, [f.key]: e.target.value }))
                      }
                    />
                  </Form.Item>
                ))}
                <Button type="primary" loading={submitting} onClick={() => doResolve('INPUT')}>
                  提交
                </Button>
              </Form>
            )}

            {active.kind === 'REVIEW' && (
              <Button type="primary" onClick={onGoReview}>
                前往复核案件处理
              </Button>
            )}
          </div>
        )}
      </Modal>
    </div>
  );
}
