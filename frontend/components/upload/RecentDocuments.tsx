'use client';

import { useCallback, useEffect, useState } from 'react';
import { Alert, App, Button, Card, Empty, Popconfirm, Table } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useRouter } from 'next/navigation';
import type { ColumnsType } from 'antd/es/table';
import { deleteDocument, listDocuments } from '@/lib/api';
import { formatDateTime } from '@/lib/datetime';
import type { DocumentView } from '@/lib/types';
import DocStatusTag from '@/components/common/DocStatusTag';

/** 与老 8080 页一致的处理中状态集合：存在这些状态时 2s 轮询直到终态。 */
const PROCESSING = new Set(['PARSING', 'CLEANING', 'CHUNKING', 'EMBEDDING', 'INDEXING']);

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

export default function RecentDocuments() {
  const router = useRouter();
  const { message } = App.useApp();
  const [docs, setDocs] = useState<DocumentView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setDocs(await listDocuments());
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // 有文档处于处理中状态时自动轮询（老 8080 页能力）
  useEffect(() => {
    if (!docs.some((d) => PROCESSING.has(d.status))) return;
    const timer = setTimeout(() => void load(), 2000);
    return () => clearTimeout(timer);
  }, [docs, load]);

  const remove = async (id: string) => {
    try {
      await deleteDocument(id);
      message.success('已删除');
      void load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除失败');
    }
  };

  const columns: ColumnsType<DocumentView> = [
    { title: '文件名', dataIndex: 'filename', key: 'filename' },
    { title: '类型', dataIndex: 'docType', key: 'docType', width: 120 },
    {
      title: '大小',
      dataIndex: 'sizeBytes',
      key: 'sizeBytes',
      width: 120,
      render: (v: number) => formatSize(v),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 120,
      render: (v: string) => <DocStatusTag status={v} />,
    },
    { title: '父块', dataIndex: 'parentCount', key: 'parentCount', width: 70 },
    { title: '子块', dataIndex: 'childCount', key: 'childCount', width: 70 },
    { title: '上传时间', dataIndex: 'createdAt', key: 'createdAt', width: 180, render: (v: string | null) => formatDateTime(v) },
    {
      title: '操作',
      key: 'op',
      width: 80,
      render: (_, record) => (
        // 阻止冒泡：避免触发行点击跳转详情
        <span onClick={(e) => e.stopPropagation()}>
          <Popconfirm
            title="确定删除该文档？"
            description="将同时删除向量索引与切片"
            onConfirm={() => void remove(record.id)}
          >
            <Button size="small" danger type="link">
              删除
            </Button>
          </Popconfirm>
        </span>
      ),
    },
  ];

  return (
    <Card
      title="最近文档"
      extra={
        <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
          刷新
        </Button>
      }
    >
      {error && (
        <Alert
          type="error"
          showIcon
          title="文档列表加载失败"
          description={error}
          style={{ marginBottom: 16 }}
          action={
            <Button size="small" onClick={load}>
              重试
            </Button>
          }
        />
      )}
      <Table<DocumentView>
        rowKey="id"
        columns={columns}
        dataSource={docs}
        loading={loading}
        pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }}
        locale={{ emptyText: <Empty description="暂无文档，请先上传材料" /> }}
        onRow={(record) => ({
          style: { cursor: 'pointer' },
          onClick: () => router.push(`/?tab=upload&docId=${encodeURIComponent(record.id)}`),
        })}
      />
    </Card>
  );
}
