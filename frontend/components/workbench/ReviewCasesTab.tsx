'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { listReviewCases, resolveReviewCase } from '@/lib/api';
import { formatDateTime } from '@/lib/datetime';
import type { ReviewCase } from '@/lib/types';

const CASE_TYPE_LABEL: Record<string, string> = {
  LOW_CONFIDENCE: '低置信度',
  MATERIAL_DIFF: '重大差异',
  RULE_MISMATCH: '规则不匹配',
};

const STATUS_OPTIONS = [
  { value: 'OPEN', label: '待处理' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'EDITED', label: '已编辑' },
];

function prettyJson(raw: string | null): string {
  if (!raw) return '（无差异详情）';
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

export default function ReviewCasesTab() {
  const { message } = App.useApp();
  const [cases, setCases] = useState<ReviewCase[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<string>('OPEN');
  const [active, setActive] = useState<ReviewCase | null>(null);
  const [editOpen, setEditOpen] = useState(false);
  const [editFields, setEditFields] = useState<Record<string, string>>({});
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setCases(await listReviewCases({ status }));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [status]);

  useEffect(() => {
    void load();
  }, [load]);

  const doResolve = async (action: 'APPROVE' | 'REJECT' | 'EDIT') => {
    if (!active) return;
    setSubmitting(true);
    try {
      await resolveReviewCase(active.id, {
        action,
        editedFields: action === 'EDIT' ? editFields : undefined,
        comment: comment || undefined,
      });
      message.success('处置成功');
      setActive(null);
      setEditOpen(false);
      setEditFields({});
      setComment('');
      void load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '处置失败');
    } finally {
      setSubmitting(false);
    }
  };

  const openEdit = () => {
    // 预填：从 diffJson 提取字段键作为编辑项
    const initial: Record<string, string> = {};
    if (active?.diffJson) {
      try {
        const parsed = JSON.parse(active.diffJson) as unknown;
        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
          for (const [k, v] of Object.entries(parsed as Record<string, unknown>)) {
            if (typeof v === 'string') initial[k] = v;
            else if (v && typeof v === 'object') {
              const rec = v as Record<string, unknown>;
              const candidate = rec.valueB ?? rec.value ?? rec.newValue;
              initial[k] = typeof candidate === 'string' ? candidate : JSON.stringify(candidate);
            }
          }
        }
      } catch {
        // 无法解析则从空表单开始
      }
    }
    if (active?.fieldKey && !(active.fieldKey in initial)) {
      initial[active.fieldKey] = '';
    }
    setEditFields(initial);
    setEditOpen(true);
  };

  const columns: ColumnsType<ReviewCase> = [
    {
      title: '案件 ID',
      dataIndex: 'id',
      key: 'id',
      width: 120,
      render: (v: number) => <Typography.Text code>#{v}</Typography.Text>,
    },
    {
      title: '类型',
      dataIndex: 'caseType',
      key: 'caseType',
      width: 120,
      render: (v: string) => <Tag color="orange">{CASE_TYPE_LABEL[v] ?? v}</Tag>,
    },
    {
      title: '文档 ID',
      dataIndex: 'docId',
      key: 'docId',
      width: 120,
      render: (v: string) => <Typography.Text code>{v.slice(0, 8)}</Typography.Text>,
    },
    { title: '字段键', dataIndex: 'fieldKey', key: 'fieldKey', width: 160 },
    {
      title: '置信度',
      dataIndex: 'confidence',
      key: 'confidence',
      width: 100,
      render: (v: number | null) => (v === null ? '-' : v.toFixed(2)),
    },
    { title: '状态', dataIndex: 'status', key: 'status', width: 90, render: (v: string) => <Tag>{v}</Tag> },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 180, render: (v: string | null) => formatDateTime(v) },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Typography.Text>状态：</Typography.Text>
        <Select
          style={{ width: 140 }}
          value={status}
          onChange={(v) => setStatus(v)}
          options={STATUS_OPTIONS}
        />
        <Button onClick={load} loading={loading}>
          刷新
        </Button>
      </Space>

      {error && (
        <Alert
          type="error"
          showIcon
          title="复核案件加载失败"
          description={error}
          style={{ marginBottom: 16 }}
          action={
            <Button size="small" onClick={load}>
              重试
            </Button>
          }
        />
      )}

      <Table<ReviewCase>
        rowKey="id"
        columns={columns}
        dataSource={cases}
        loading={loading}
        pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }}
        locale={{ emptyText: <Empty description="暂无待处理复核案件" /> }}
        onRow={(record) => ({
          style: { cursor: 'pointer' },
          onClick: () => {
            setActive(record);
            setComment('');
          },
        })}
      />

      <Drawer
        title="复核案件详情"
        size={640}
        open={active !== null}
        onClose={() => {
          setActive(null);
          setEditOpen(false);
        }}
      >
        {active && (
          <>
            <Descriptions bordered size="small" column={1} style={{ marginBottom: 16 }}>
              <Descriptions.Item label="案件 ID">{active.id}</Descriptions.Item>
              <Descriptions.Item label="类型">
                {CASE_TYPE_LABEL[active.caseType] ?? active.caseType}
              </Descriptions.Item>
              <Descriptions.Item label="文档 ID">{active.docId}</Descriptions.Item>
              <Descriptions.Item label="版本">v{active.versionNo}</Descriptions.Item>
              <Descriptions.Item label="字段键">{active.fieldKey}</Descriptions.Item>
              <Descriptions.Item label="置信度">
                {active.confidence === null ? '-' : active.confidence.toFixed(2)}
              </Descriptions.Item>
              {active.ruleCode && (
                <Descriptions.Item label="规则">
                  {active.ruleCode}
                  {active.ruleVersion ? `（${active.ruleVersion}）` : ''}
                </Descriptions.Item>
              )}
            </Descriptions>

            <Typography.Title level={5}>差异详情</Typography.Title>
            <pre
              style={{
                background: '#fafafa',
                border: '1px solid #f0f0f0',
                borderRadius: 4,
                padding: 12,
                fontSize: 12,
                maxHeight: 320,
                overflow: 'auto',
              }}
            >
              {prettyJson(active.diffJson)}
            </pre>

            {active.status === 'OPEN' && (
              <Space style={{ marginTop: 16 }}>
                <Button type="primary" loading={submitting} onClick={() => doResolve('APPROVE')}>
                  通过
                </Button>
                <Button danger loading={submitting} onClick={() => doResolve('REJECT')}>
                  驳回
                </Button>
                <Button onClick={openEdit}>编辑</Button>
              </Space>
            )}
          </>
        )}
      </Drawer>

      <Modal
        title="编辑字段取值"
        open={editOpen}
        onCancel={() => setEditOpen(false)}
        onOk={() => doResolve('EDIT')}
        confirmLoading={submitting}
        okText="提交编辑"
        width={560}
      >
        <Form layout="vertical">
          {Object.keys(editFields).length === 0 && (
            <Alert type="info" showIcon title="差异详情中未解析出字段，请先在详情中确认字段键" />
          )}
          {Object.entries(editFields).map(([key, value]) => (
            <Form.Item key={key} label={key}>
              <Input.TextArea
                rows={2}
                value={value}
                onChange={(e) =>
                  setEditFields((prev) => ({ ...prev, [key]: e.target.value }))
                }
              />
            </Form.Item>
          ))}
          <Form.Item label="备注（可选）">
            <Input value={comment} onChange={(e) => setComment(e.target.value)} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
