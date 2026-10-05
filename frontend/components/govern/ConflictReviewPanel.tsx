'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  App,
  Button,
  Card,
  Col,
  Empty,
  Input,
  Popconfirm,
  Row,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { ReloadOutlined, ScanOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import {
  getConflictDiff,
  ignoreConflict,
  listConflicts,
  resolveConflict,
  scanConflicts,
} from '@/lib/api';
import type { ConflictResolution } from '@/lib/api';
import { getSettings } from '@/lib/settings';
import type { ConflictChunkView, ConflictDiff, ConflictItem } from '@/lib/types';
import { formatDateTime } from '@/lib/datetime';

const STATUS_OPTIONS = [
  { value: 'DETECTED', label: '待处理' },
  { value: 'RESOLVED', label: '已解决' },
  { value: 'IGNORED', label: '已忽略' },
];

const RESOLUTIONS: { key: ConflictResolution; label: string; danger?: boolean }[] = [
  { key: 'KEEP_A', label: '保留 A' },
  { key: 'KEEP_B', label: '保留 B' },
  { key: 'KEEP_BOTH', label: '双方并存' },
  { key: 'MERGE', label: '已人工合并' },
  { key: 'DELETE_A', label: '淘汰 A', danger: true },
  { key: 'DELETE_B', label: '淘汰 B', danger: true },
];

function ChunkPane({ title, chunk, color }: { title: string; chunk: ConflictChunkView; color: string }) {
  return (
    <Card
      size="small"
      title={
        <Space size={8} wrap>
          <span style={{ color }}>{title}</span>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {chunk.chunkId ? `${chunk.chunkId.slice(0, 16)}…` : '-'}
          </Typography.Text>
        </Space>
      }
      styles={{ body: { maxHeight: 360, overflow: 'auto' } }}
    >
      <div style={{ marginBottom: 8 }}>
        {chunk.sourceFilename && <Tag color="blue">{chunk.sourceFilename}</Tag>}
        {chunk.domainTag && <Tag>{chunk.domainTag}</Tag>}
        {chunk.subDomainTag && <Tag>{chunk.subDomainTag}</Tag>}
        {chunk.createdIdentity && <Tag color="purple">{chunk.createdIdentity}</Tag>}
        {chunk.version !== undefined && <Tag color="purple">v{chunk.version}</Tag>}
      </div>
      <pre style={{ fontSize: 12, whiteSpace: 'pre-wrap', margin: 0 }}>
        {chunk.content || '（chunk 原文缺失，可能已被删除）'}
      </pre>
    </Card>
  );
}

/**
 * 冲突审核完整组件（代码冲突合并式）：状态筛选 / 立即扫描 / A-B 对比 / 7 种处理决议。
 * 知识治理 Tab 与文档详情页共用（后端冲突实体无 docId 维度，两处均展示全量列表）。
 */
export default function ConflictReviewPanel() {
  const { message } = App.useApp();
  const [status, setStatus] = useState('DETECTED');
  const [list, setList] = useState<ConflictItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [scanning, setScanning] = useState(false);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [diff, setDiff] = useState<ConflictDiff | null>(null);
  const [diffLoading, setDiffLoading] = useState(false);
  const [comment, setComment] = useState('');
  const [acting, setActing] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setList(await listConflicts(status));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '冲突列表加载失败');
    } finally {
      setLoading(false);
    }
  }, [status, message]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDiff = async (id: number) => {
    setSelectedId(id);
    setDiff(null);
    setComment('');
    setDiffLoading(true);
    try {
      setDiff(await getConflictDiff(String(id)));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '冲突对比加载失败');
    } finally {
      setDiffLoading(false);
    }
  };

  const scan = async () => {
    setScanning(true);
    try {
      const r = await scanConflicts();
      message.success(`扫描完成，新检出冲突 ${r.newConflicts ?? '?'} 条`);
      void load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '扫描失败');
    } finally {
      setScanning(false);
    }
  };

  const act = async (resolution: ConflictResolution | 'IGNORE') => {
    if (selectedId === null) return;
    setActing(true);
    const resolvedBy = getSettings().userId || 'anonymous';
    try {
      if (resolution === 'IGNORE') {
        await ignoreConflict(String(selectedId), { resolvedBy, comment: comment.trim() || undefined });
      } else {
        await resolveConflict(String(selectedId), {
          resolution,
          resolvedBy,
          comment: comment.trim() || undefined,
        });
      }
      message.success('处理完成');
      await openDiff(selectedId);
      void load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '处理失败');
    } finally {
      setActing(false);
    }
  };

  const columns: ColumnsType<ConflictItem> = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    {
      title: '领域',
      key: 'domain',
      render: (_, c) => `${c.domainTag ?? ''}/${c.subDomainTag ?? ''}`,
    },
    {
      title: '相似度',
      dataIndex: 'similarity',
      width: 100,
      render: (v: number | undefined) => (v !== undefined ? v.toFixed(4) : '-'),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (v: string) => <Tag color={v === 'DETECTED' ? 'orange' : 'green'}>{v}</Tag>,
    },
    {
      title: '检出时间',
      dataIndex: 'detectedAt',
      width: 170,
      render: (v: string | undefined) => formatDateTime(v),
    },
  ];

  const conflict = (diff?.conflict ?? null) as ConflictItem | null;
  const resolved = conflict?.status !== undefined && conflict.status !== 'DETECTED';

  return (
    <div>
      <Space style={{ marginBottom: 12 }} wrap>
        <span>状态</span>
        <Select style={{ width: 120 }} options={STATUS_OPTIONS} value={status} onChange={setStatus} />
        <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
          刷新
        </Button>
        <Button type="primary" icon={<ScanOutlined />} onClick={() => void scan()} loading={scanning}>
          立即扫描
        </Button>
      </Space>
      <Row gutter={16}>
        <Col span={10}>
          <Table<ConflictItem>
            rowKey={(r) => String(r.id ?? '')}
            size="small"
            columns={columns}
            dataSource={list}
            loading={loading}
            pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }}
            locale={{ emptyText: <Empty description={`暂无${STATUS_OPTIONS.find((o) => o.value === status)?.label ?? ''}的冲突`} /> }}
            onRow={(record) => ({
              style: { cursor: 'pointer' },
              onClick: () => record.id !== undefined && void openDiff(record.id),
            })}
            rowClassName={(r) => (r.id === selectedId ? 'ant-table-row-selected' : '')}
          />
        </Col>
        <Col span={14}>
          {selectedId === null ? (
            <Card>
              <Empty description="从左侧选择一条冲突查看双方内容对比" />
            </Card>
          ) : (
            <Card loading={diffLoading}>
              {diff && conflict && (
                <div>
                  <Typography.Paragraph>
                    冲突 #{conflict.id} · 相似度 {conflict.similarity?.toFixed(4)} · 状态{' '}
                    <Tag color={resolved ? 'green' : 'orange'}>{conflict.status}</Tag>
                    {conflict.resolution && (
                      <>
                        {' '}· 决议 <b>{conflict.resolution}</b>
                      </>
                    )}
                    {conflict.resolvedBy && <> · 处理人 {conflict.resolvedBy}</>}
                  </Typography.Paragraph>
                  <Row gutter={12}>
                    <Col span={12}>
                      <ChunkPane title="A 方" chunk={diff.chunkA} color="#1677ff" />
                    </Col>
                    <Col span={12}>
                      <ChunkPane title="B 方" chunk={diff.chunkB} color="#fa8c16" />
                    </Col>
                  </Row>
                  {!resolved && (
                    <div style={{ marginTop: 12 }}>
                      <Space wrap style={{ marginBottom: 8 }}>
                        {RESOLUTIONS.map((r) =>
                          r.danger ? (
                            <Popconfirm
                              key={r.key}
                              title={`确认执行 ${r.key}？对应知识的 is_active 将被置为 false（检索不再命中）`}
                              onConfirm={() => void act(r.key)}
                            >
                              <Button danger loading={acting}>
                                {r.label}
                              </Button>
                            </Popconfirm>
                          ) : (
                            <Button key={r.key} loading={acting} onClick={() => void act(r.key)}>
                              {r.label}
                            </Button>
                          ),
                        )}
                        <Button loading={acting} onClick={() => void act('IGNORE')}>
                          误报忽略
                        </Button>
                      </Space>
                      <Input
                        placeholder="处理说明（可选）"
                        value={comment}
                        onChange={(e) => setComment(e.target.value)}
                      />
                    </div>
                  )}
                </div>
              )}
            </Card>
          )}
        </Col>
      </Row>
    </div>
  );
}
