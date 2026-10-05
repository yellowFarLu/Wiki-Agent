'use client';

import { useCallback, useEffect, useState } from 'react';
import { Alert, Button, Card, Empty, Progress, Select, Space, Switch, Table, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useRouter } from 'next/navigation';
import type { ColumnsType } from 'antd/es/table';
import { listTasks } from '@/lib/api';
import type { TaskView } from '@/lib/types';
import { formatDateTime } from '@/lib/datetime';
import TaskStatusTag from '@/components/common/TaskStatusTag';

const STATUS_OPTIONS = [
  { value: 'PENDING', label: '待执行' },
  { value: 'RUNNING', label: '运行中' },
  { value: 'WAITING_HUMAN', label: '等待人工' },
  { value: 'SUSPENDED', label: '已暂停' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'FAILED', label: '失败' },
  { value: 'CANCELLED', label: '已取消' },
];

export default function TaskTable() {
  const router = useRouter();
  const [tasks, setTasks] = useState<TaskView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<string | undefined>(undefined);
  const [mine, setMine] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setTasks(await listTasks({ status, mine }));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [status, mine]);

  useEffect(() => {
    void load();
  }, [load]);

  const columns: ColumnsType<TaskView> = [
    {
      title: '任务 ID',
      dataIndex: 'taskId',
      key: 'taskId',
      width: 140,
      render: (v: string) => <Typography.Text code>{v.slice(0, 8)}…</Typography.Text>,
    },
    { title: '类型', dataIndex: 'taskType', key: 'taskType', width: 140 },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (v: string) => <TaskStatusTag status={v} />,
    },
    {
      title: '进度',
      dataIndex: 'progressPercent',
      key: 'progressPercent',
      width: 180,
      render: (v: number, record) => (
        <Progress
          percent={v ?? 0}
          size="small"
          status={record.status === 'FAILED' ? 'exception' : undefined}
        />
      ),
    },
    {
      title: '尝试次数',
      key: 'attempt',
      width: 100,
      render: (_, r) => `${r.attempt}/${r.maxAttempts}`,
    },
    { title: '提交人', dataIndex: 'submittedBy', key: 'submittedBy', width: 140 },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (v: string) => formatDateTime(v),
    },
  ];

  return (
    <Card>
      <Space style={{ marginBottom: 16 }} wrap>
        <Typography.Text>状态筛选：</Typography.Text>
        <Select
          style={{ width: 160 }}
          allowClear
          placeholder="全部状态"
          options={STATUS_OPTIONS}
          value={status}
          onChange={(v) => setStatus(v)}
        />
        <Typography.Text>只看我提交的：</Typography.Text>
        <Switch checked={mine} onChange={setMine} />
        <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
          刷新
        </Button>
      </Space>

      {error && (
        <Alert
          type="error"
          showIcon
          message="任务列表加载失败"
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
        pagination={{ pageSize: 15, showTotal: (t) => `共 ${t} 条` }}
        locale={{ emptyText: <Empty description="暂无任务" /> }}
        onRow={(record) => ({
          style: { cursor: 'pointer' },
          onClick: () => router.push(`/?tab=tasks&taskId=${encodeURIComponent(record.taskId)}`),
        })}
      />
    </Card>
  );
}
