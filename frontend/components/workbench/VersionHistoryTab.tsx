'use client';

import { useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Empty,
  Input,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { getVersionDiff, getVersions } from '@/lib/api';
import { formatDateTime } from '@/lib/datetime';
import type { DocVersion, VersionDiff, VersionDiffField } from '@/lib/types';

const VERSION_STATUS: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  SUPERSEDED: '已取代',
};

export default function VersionHistoryTab() {
  const [docId, setDocId] = useState('');
  const [versions, setVersions] = useState<DocVersion[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<number[]>([]);
  const [diff, setDiff] = useState<VersionDiff | null>(null);
  const [diffLoading, setDiffLoading] = useState(false);
  const [diffError, setDiffError] = useState<string | null>(null);

  const loadVersions = async () => {
    const id = docId.trim();
    if (!id) return;
    setLoading(true);
    setError(null);
    setVersions([]);
    setSelected([]);
    setDiff(null);
    try {
      setVersions(await getVersions(id));
    } catch (e) {
      setError(e instanceof Error ? e.message : '版本列表加载失败');
    } finally {
      setLoading(false);
    }
  };

  const loadDiff = async () => {
    if (selected.length !== 2) return;
    const [a, b] = [...selected].sort((x, y) => x - y);
    setDiffLoading(true);
    setDiffError(null);
    try {
      setDiff(await getVersionDiff(docId.trim(), a, b));
    } catch (e) {
      setDiff(null);
      setDiffError(e instanceof Error ? e.message : '版本对比失败');
    } finally {
      setDiffLoading(false);
    }
  };

  const versionColumns: ColumnsType<DocVersion> = [
    {
      title: '版本号',
      dataIndex: 'versionNo',
      key: 'versionNo',
      width: 90,
      render: (v: number) => <Tag color="blue">v{v}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (v: string) => (
        <Tag color={v === 'PUBLISHED' ? 'success' : v === 'DRAFT' ? 'default' : 'warning'}>
          {VERSION_STATUS[v] ?? v}
        </Tag>
      ),
    },
    { title: '父版本', dataIndex: 'parentVersionNo', key: 'parentVersionNo', width: 90, render: (v: number | null) => v ?? '-' },
    { title: '变更说明', dataIndex: 'changeSummary', key: 'changeSummary', render: (v: string | null) => v ?? '-' },
    { title: '创建人', dataIndex: 'createdBy', key: 'createdBy', width: 120 },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 180, render: (v: string | null) => formatDateTime(v) },
  ];

  const diffColumns: ColumnsType<VersionDiffField> = [
    { title: '字段键', dataIndex: 'fieldKey', key: 'fieldKey', width: 160 },
    {
      title: `版本 A（v${diff?.versionA ?? ''}）`,
      dataIndex: 'valueA',
      key: 'valueA',
      render: (v: string | null, r) => (
        <div>
          <Typography.Paragraph style={{ marginBottom: 4 }} ellipsis={{ rows: 2, expandable: true, symbol: '展开' }}>
            {v ?? '（空）'}
          </Typography.Paragraph>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {r.sourceA ? `来源 ${r.sourceA}` : ''}
            {r.editedByA ? ` ｜ ${r.editedByA}` : ''}
            {r.confidenceA !== null && r.confidenceA !== undefined ? ` ｜ ${r.confidenceA.toFixed(2)}` : ''}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: `版本 B（v${diff?.versionB ?? ''}）`,
      dataIndex: 'valueB',
      key: 'valueB',
      render: (v: string | null, r) => (
        <div>
          <Typography.Paragraph style={{ marginBottom: 4 }} ellipsis={{ rows: 2, expandable: true, symbol: '展开' }}>
            {v ?? '（空）'}
          </Typography.Paragraph>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {r.sourceB ? `来源 ${r.sourceB}` : ''}
            {r.editedByB ? ` ｜ ${r.editedByB}` : ''}
            {r.confidenceB !== null && r.confidenceB !== undefined ? ` ｜ ${r.confidenceB.toFixed(2)}` : ''}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: '变更',
      dataIndex: 'changed',
      key: 'changed',
      width: 80,
      render: (v: boolean) => (v ? <Tag color="warning">已变更</Tag> : <Tag>一致</Tag>),
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16 }} wrap>
        <Input
          style={{ width: 360 }}
          placeholder="输入文档 ID"
          value={docId}
          onChange={(e) => setDocId(e.target.value)}
          onPressEnter={loadVersions}
        />
        <Button type="primary" loading={loading} onClick={loadVersions}>
          查询版本
        </Button>
      </Space>

      {error && (
        <Alert
          type="error"
          showIcon
          title="版本列表加载失败"
          description={error}
          style={{ marginBottom: 16 }}
          action={
            <Button size="small" onClick={loadVersions}>
              重试
            </Button>
          }
        />
      )}

      {versions.length === 0 && !loading && !error ? (
        <Empty description="请输入文档 ID 查询版本历史" />
      ) : (
        <Card title="版本列表（勾选两个版本进行对比）" size="small" style={{ marginBottom: 16 }}>
          <Table<DocVersion>
            rowKey="versionNo"
            columns={versionColumns}
            dataSource={versions}
            loading={loading}
            pagination={false}
            rowSelection={{
              selectedRowKeys: selected,
              onChange: (keys) => setSelected(keys.slice(-2) as number[]),
            }}
          />
          <Button
            type="primary"
            style={{ marginTop: 12 }}
            disabled={selected.length !== 2}
            loading={diffLoading}
            onClick={loadDiff}
          >
            对比所选版本
          </Button>
        </Card>
      )}

      {diffError && (
        <Alert type="error" showIcon title="版本对比失败" description={diffError} style={{ marginBottom: 16 }} />
      )}

      {diff && (
        <Card title={`版本对比：v${diff.versionA} → v${diff.versionB}`} size="small">
          <Table<VersionDiffField>
            rowKey="fieldKey"
            columns={diffColumns}
            dataSource={diff.fields}
            pagination={{ pageSize: 15 }}
            rowClassName={(r) => (r.changed ? 'ant-table-row-warning' : '')}
            locale={{ emptyText: <Empty description="两个版本之间无字段差异" /> }}
          />
        </Card>
      )}
    </div>
  );
}
