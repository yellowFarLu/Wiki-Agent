'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Card, Empty, Input, List, Select, Spin, Table, Tag, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { SearchOutlined, NodeIndexOutlined } from '@ant-design/icons';
import { useRouter } from 'next/navigation';
import { formatDateTime } from '@/lib/datetime';

interface GraphNode {
  id: string;
  name: string;
  type: string;
  description: string;
  sourceDocId: string;
  sourceChunkId: string;
}

interface GraphEdge {
  id: string;
  sourceEntityId: string;
  targetEntityId: string;
  relationType: string;
  description: string;
  weight: number;
}

interface GraphSearchResult {
  matchedEntities: GraphNode[];
  neighborEntities: GraphNode[];
  edges: GraphEdge[];
  relatedChunkIds: string[];
}

interface EntityListRow {
  id: string;
  name: string;
  type: string;
  description: string;
  sourceDocId: string;
  sourceChunkId: string;
  createdAt: string | null;
}

interface EntityPage {
  entities: EntityListRow[];
  total: number;
  page: number;
  size: number;
  types: string[];
}

const TYPE_COLORS: Record<string, string> = {
  '人物': '#f5222d',
  '组织': '#fa8c16',
  '产品': '#faad14',
  '地点': '#52c41a',
  '概念': '#1890ff',
  '事件': '#722ed1',
  '规则': '#13c2c2',
  '其他': '#8c8c8c',
};

/** “已有实体”清单列定义。 */
const entityColumns: ColumnsType<EntityListRow> = [
  {
    title: '实体名称',
    dataIndex: 'name',
    render: (v: string, r) => (
      <span>
        <Tag color={TYPE_COLORS[r.type] ?? 'default'}>{r.type}</Tag>
        {v}
      </span>
    ),
  },
  {
    title: '描述',
    dataIndex: 'description',
    ellipsis: true,
    render: (v: string | null) => v || '-',
  },
  {
    title: '来源文档',
    dataIndex: 'sourceDocId',
    width: 170,
    render: (v: string) => (v ? `${v.slice(0, 13)}…` : '-'),
  },
  {
    title: '抽取时间',
    dataIndex: 'createdAt',
    width: 170,
    render: (v: string | null) => formatDateTime(v),
  },
];

export default function GraphPanel() {
  const router = useRouter();
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<GraphSearchResult | null>(null);
  const [stats, setStats] = useState<{ entityCount: number; relationCount: number } | null>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [error, setError] = useState<string | null>(null);
  const [entityPage, setEntityPage] = useState<EntityPage | null>(null);
  const [listLoading, setListLoading] = useState(false);
  const [typeFilter, setTypeFilter] = useState<string | null>(null);
  const [graphTitle, setGraphTitle] = useState<string>('');

  useEffect(() => {
    fetch('/api/graph/stats')
      .then((r) => (r.ok ? r.json() : null))
      .then(setStats)
      .catch(() => setStats(null));
  }, []);

  /** 分页拉取“已有实体”清单；type 非空时按类型过滤。 */
  const loadEntities = useCallback(async (page: number, size: number, type: string | null) => {
    setListLoading(true);
    try {
      const params = new URLSearchParams({ page: String(page), size: String(size) });
      if (type) params.set('type', type);
      const res = await fetch(`/api/graph/entities?${params.toString()}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      setEntityPage((await res.json()) as EntityPage);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '实体列表加载失败');
    } finally {
      setListLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadEntities(0, 20, null);
  }, [loadEntities]);

  /** 点击实体行：拉取该实体 1-hop 邻居子图并滚动到可视化区。 */
  const showEntityGraph = useCallback(async (entity: EntityListRow) => {
    try {
      const res = await fetch(`/api/graph/entity/${encodeURIComponent(entity.id)}/neighbors`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = (await res.json()) as GraphSearchResult;
      setResult(data);
      setGraphTitle(entity.name);
      setError(null);
      if (data.edges.length === 0) {
        message.info(`实体「${entity.name}」暂未抽取到关系`);
      }
      document.getElementById('wa-graph-viz')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch (e) {
      message.error(e instanceof Error ? e.message : '实体关系加载失败');
    }
  }, []);

  const doSearch = useCallback(async () => {
    if (!query.trim()) return;
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`/api/graph/search?q=${encodeURIComponent(query.trim())}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = (await res.json()) as GraphSearchResult;
      setResult(data);
      setGraphTitle(query.trim());
      if (data.matchedEntities.length === 0 && data.neighborEntities.length === 0) {
        message.info('未在知识图谱中找到相关实体');
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '搜索失败');
      setResult(null);
    } finally {
      setLoading(false);
    }
  }, [query]);

  // Canvas 力导向图渲染
  useEffect(() => {
    if (!result || !canvasRef.current) return;
    const canvas = canvasRef.current;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const nodes = [...result.matchedEntities, ...result.neighborEntities];
    const edges = result.edges;
    if (nodes.length === 0) return;

    // 力导向布局（简化版）
    const width = canvas.width;
    const height = canvas.height;
    const centerX = width / 2;
    const centerY = height / 2;
    const radius = Math.min(width, height) * 0.35;
    const matchedIds = new Set(result.matchedEntities.map((m) => m.id));
    const nodeRadius = (id: string) => (matchedIds.has(id) ? 20 : 14);

    // 只保留两端节点都在图中的边
    const nodeIds = new Set(nodes.map((n) => n.id));
    const visibleEdges = edges.filter(
      (e) => nodeIds.has(e.sourceEntityId) && nodeIds.has(e.targetEntityId),
    );

    // 节点位置初始化（圆形布局）
    const positions = new Map<string, { x: number; y: number }>();
    nodes.forEach((n, i) => {
      const angle = (2 * Math.PI * i) / nodes.length;
      positions.set(n.id, {
        x: centerX + radius * Math.cos(angle),
        y: centerY + radius * Math.sin(angle),
      });
    });

    // 迭代布局（斥力对称、弹簧朝向理想边长、边界钳制防出画布）
    const pad = 40;
    const idealLen = 120;
    for (let iter = 0; iter < 150; iter++) {
      // 斥力（两端对称受力）
      const repulsion = 4000;
      for (let i = 0; i < nodes.length; i++) {
        for (let j = i + 1; j < nodes.length; j++) {
          const a = positions.get(nodes[i].id)!;
          const b = positions.get(nodes[j].id)!;
          const dx = a.x - b.x;
          const dy = a.y - b.y;
          const dist = Math.sqrt(dx * dx + dy * dy) || 1;
          const force = (repulsion / (dist * dist)) * 0.5;
          const fx = (dx / dist) * force;
          const fy = (dy / dist) * force;
          a.x += fx;
          a.y += fy;
          b.x -= fx;
          b.y -= fy;
        }
      }
      // 弹簧引力（边）：朝理想边长收放
      for (const e of visibleEdges) {
        const a = positions.get(e.sourceEntityId)!;
        const b = positions.get(e.targetEntityId)!;
        const dx = b.x - a.x;
        const dy = b.y - a.y;
        const dist = Math.sqrt(dx * dx + dy * dy) || 1;
        const force = (dist - idealLen) * 0.02;
        const fx = (dx / dist) * force;
        const fy = (dy / dist) * force;
        a.x += fx;
        a.y += fy;
        b.x -= fx;
        b.y -= fy;
      }
      // 向心力 + 边界钳制
      positions.forEach((pos) => {
        pos.x += (centerX - pos.x) * 0.005;
        pos.y += (centerY - pos.y) * 0.005;
        pos.x = Math.min(Math.max(pos.x, pad), width - pad);
        pos.y = Math.min(Math.max(pos.y, pad), height - pad);
      });
    }

    // 渲染
    ctx.clearRect(0, 0, width, height);
    // 边（含方向箭头 + 关系类型标注）；同一对节点的多边标注交替上下偏移
    const pairCount = new Map<string, number>();
    for (const e of visibleEdges) {
      const a = positions.get(e.sourceEntityId)!;
      const b = positions.get(e.targetEntityId)!;
      const dx = b.x - a.x;
      const dy = b.y - a.y;
      const dist = Math.sqrt(dx * dx + dy * dy) || 1;
      const ux = dx / dist;
      const uy = dy / dist;
      // 连线（端点收进节点圆内，避免穿过圆心）
      const sx = a.x + ux * nodeRadius(e.sourceEntityId);
      const sy = a.y + uy * nodeRadius(e.sourceEntityId);
      const tx = b.x - ux * (nodeRadius(e.targetEntityId) + 6);
      const ty = b.y - uy * (nodeRadius(e.targetEntityId) + 6);
      ctx.beginPath();
      ctx.moveTo(sx, sy);
      ctx.lineTo(tx, ty);
      ctx.strokeStyle = '#8c8c8c';
      ctx.lineWidth = 1.5;
      ctx.stroke();
      // 箭头
      const arrowLen = 7;
      const angle = Math.atan2(uy, ux);
      ctx.beginPath();
      ctx.moveTo(tx + ux * 6, ty + uy * 6);
      ctx.lineTo(
        tx + ux * 6 - arrowLen * Math.cos(angle - Math.PI / 6),
        ty + uy * 6 - arrowLen * Math.sin(angle - Math.PI / 6),
      );
      ctx.lineTo(
        tx + ux * 6 - arrowLen * Math.cos(angle + Math.PI / 6),
        ty + uy * 6 - arrowLen * Math.sin(angle + Math.PI / 6),
      );
      ctx.closePath();
      ctx.fillStyle = '#8c8c8c';
      ctx.fill();
      // 关系类型标注（白描边保证在线上可读）
      const pairKey = [e.sourceEntityId, e.targetEntityId].sort().join('|');
      const k = pairCount.get(pairKey) ?? 0;
      pairCount.set(pairKey, k + 1);
      const off = (k % 2 === 0 ? -1 : 1) * 10;
      const mx = (sx + tx) / 2 - uy * off;
      const my = (sy + ty) / 2 + ux * off;
      ctx.font = '11px sans-serif';
      ctx.textAlign = 'center';
      ctx.lineWidth = 3;
      ctx.strokeStyle = '#fff';
      ctx.strokeText(e.relationType, mx, my);
      ctx.fillStyle = '#595959';
      ctx.fillText(e.relationType, mx, my);
    }
    // 节点
    for (const n of nodes) {
      const pos = positions.get(n.id);
      if (!pos) continue;
      const isMatched = result.matchedEntities.some((m) => m.id === n.id);
      ctx.beginPath();
      ctx.arc(pos.x, pos.y, isMatched ? 20 : 14, 0, 2 * Math.PI);
      ctx.fillStyle = TYPE_COLORS[n.type] ?? '#8c8c8c';
      ctx.fill();
      ctx.strokeStyle = isMatched ? '#000' : '#fff';
      ctx.lineWidth = isMatched ? 2 : 1;
      ctx.stroke();
      // 标签
      ctx.fillStyle = '#333';
      ctx.font = '12px sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText(n.name, pos.x, pos.y + 32);
    }
  }, [result]);

  return (
    <div>
      <Typography.Title level={3}>
        <NodeIndexOutlined /> 知识图谱
      </Typography.Title>

      {stats && (
        <Card size="small" style={{ marginBottom: 16 }}>
          <Typography.Text>
            实体总数: <strong>{stats.entityCount}</strong>，关系总数: <strong>{stats.relationCount}</strong>
          </Typography.Text>
        </Card>
      )}

      <Card style={{ marginBottom: 16 }}>
        <Input.Search
          placeholder="输入关键词搜索知识图谱（如：人物名、产品名、概念）"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onSearch={doSearch}
          enterButton={<><SearchOutlined /> 搜索</>}
          loading={loading}
          size="large"
        />
      </Card>

      <Card
        style={{ marginBottom: 16 }}
        title="已有实体"
        extra={
          <Select
            allowClear
            placeholder="按类型筛选"
            style={{ width: 160 }}
            value={typeFilter ?? undefined}
            onChange={(v: string | undefined) => {
              const next = v ?? null;
              setTypeFilter(next);
              void loadEntities(0, entityPage?.size ?? 20, next);
            }}
            options={(entityPage?.types ?? []).map((t) => ({ value: t, label: t }))}
          />
        }
      >
        <Table<EntityListRow>
          rowKey="id"
          size="small"
          loading={listLoading}
          columns={entityColumns}
          dataSource={entityPage?.entities ?? []}
          onRow={(r) => ({ onClick: () => void showEntityGraph(r) })}
          pagination={{
            current: (entityPage?.page ?? 0) + 1,
            pageSize: entityPage?.size ?? 20,
            total: entityPage?.total ?? 0,
            showSizeChanger: true,
            pageSizeOptions: [10, 20, 50],
            showTotal: (t) => `共 ${t} 个实体`,
          }}
          onChange={(p) =>
            loadEntities((p.current ?? 1) - 1, p.pageSize ?? 20, typeFilter)
          }
          locale={{ emptyText: '知识图谱中暂无实体，请先上传包含业务知识的材料' }}
        />
        <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
          点击实体行查看该实体的关系网络（1-hop 邻居）。
        </Typography.Paragraph>
      </Card>

      {error && (
        <Card style={{ marginBottom: 16 }}>
          <Typography.Text type="danger">{error}</Typography.Text>
        </Card>
      )}

      {loading && (
        <div style={{ textAlign: 'center', padding: 40 }}>
          <Spin size="large" />
        </div>
      )}

      {!loading && result && (
        <>
          {(result.matchedEntities.length > 0 || result.neighborEntities.length > 0) ? (
            <div id="wa-graph-viz" style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
              <Card
                title={graphTitle ? `图谱可视化：${graphTitle}` : '图谱可视化'}
                style={{ flex: '1 1 600px', minWidth: 400 }}
              >
                <canvas
                  ref={canvasRef}
                  width={600}
                  height={400}
                  style={{ border: '1px solid #f0f0f0', borderRadius: 8 }}
                />
              </Card>
              <Card title="实体列表" style={{ flex: '1 1 300px', minWidth: 280 }}>
                <List
                  size="small"
                  dataSource={[...result.matchedEntities, ...result.neighborEntities]}
                  renderItem={(item) => (
                    <List.Item
                      style={{ cursor: 'pointer' }}
                      onClick={() => router.push(`/?tab=upload&docId=${item.sourceDocId}`)}
                    >
                      <List.Item.Meta
                        title={
                          <span>
                            <Tag color={TYPE_COLORS[item.type]}>{item.type}</Tag>
                            {item.name}
                          </span>
                        }
                        description={item.description?.slice(0, 80) || '无描述'}
                      />
                    </List.Item>
                  )}
                />
              </Card>
            </div>
          ) : (
            <Empty description="未找到相关实体" />
          )}
        </>
      )}
    </div>
  );
}
