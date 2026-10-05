'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  App,
  Button,
  Card,
  Input,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import {
  getObservabilityDashboard,
  getSessionTrace,
  listFeedbackAudit,
  listGatewayAudit,
  listKnowledgeMeta,
} from '@/lib/api';
import type {
  FeedbackAuditItem,
  GatewayAuditItem,
  KnowledgeMetaItem,
  ObservabilityDashboard,
  TraceSpanItem,
} from '@/lib/types';
import { formatDateTime } from '@/lib/datetime';

/** Tab 1: 执行路径 */
function TracePane({ initialSessionId }: { initialSessionId?: string | null }) {
  const { message } = App.useApp();
  const [sid, setSid] = useState(initialSessionId ?? '');
  const [spans, setSpans] = useState<TraceSpanItem[]>([]);
  const [loading, setLoading] = useState(false);

  const query = useCallback(
    async (target?: string) => {
      const s = (target ?? sid).trim();
      if (!s) return;
      setLoading(true);
      try {
        setSpans(await getSessionTrace(s));
      } catch (e) {
        message.error(e instanceof Error ? e.message : '查询失败');
        setSpans([]);
      } finally {
        setLoading(false);
      }
    },
    [sid, message],
  );

  // 从对话「查链路」跳入时自动查询一次
  useEffect(() => {
    if (initialSessionId) {
      setSid(initialSessionId);
      void query(initialSessionId);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialSessionId]);

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Input
          placeholder="输入 sessionId"
          value={sid}
          onChange={(e) => setSid(e.target.value)}
          onPressEnter={() => void query()}
          style={{ width: 320 }}
        />
        <Button icon={<SearchOutlined />} type="primary" onClick={() => void query()} loading={loading}>
          查询执行路径
        </Button>
      </Space>
      {spans.length === 0 && !loading && (
        <Typography.Text type="secondary">输入 sessionId 查询 Agent 执行链路</Typography.Text>
      )}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {spans.map((s, i) => (
          <Card key={s.id} size="small" style={{ marginBottom: 4 }}>
            <Space wrap>
              <b>#{i + 1}</b>
              <span>{s.nodeId}</span>
              <Tag color="default">{s.spanType}</Tag>
              <Tag color={s.status === 'ERROR' ? 'red' : 'green'}>{s.status}</Tag>
              <Typography.Text type="secondary">{s.durationMs}ms</Typography.Text>
              {s.modelUsed && <Typography.Text type="secondary">· {s.modelUsed}</Typography.Text>}
            </Space>
            {s.errorMsg && <div style={{ color: '#cf1322', marginTop: 4 }}>{s.errorMsg}</div>}
          </Card>
        ))}
      </div>
    </div>
  );
}

/** Tab 2: 业务指标 */
function MetricsPane() {
  const { message } = App.useApp();
  const [data, setData] = useState<ObservabilityDashboard | null>(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setData(await getObservabilityDashboard());
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [message]);

  useEffect(() => {
    void load();
  }, [load]);

  const bm = data?.businessMetrics;
  const kd = data?.knowledgeDetails;
  const fa = data?.feedbackAudit;

  const kpis = [
    { label: '检索次数（7d）', value: bm?.retrievals ?? '-' },
    { label: '引用次数（7d）', value: bm?.citations ?? '-' },
    { label: '有用反馈（7d）', value: bm?.useful ?? '-' },
    { label: '无用反馈（7d）', value: bm?.useless ?? '-' },
    { label: '知识总量', value: kd?.totalKnowledge ?? '-' },
    { label: '过期知识', value: kd?.staleKnowledge ?? '-', suffix: '超 180 天未更新' },
    { label: '待处理冲突', value: kd?.pendingConflicts ?? '-' },
    { label: '网关拦截', value: fa?.gatewayBlocked ?? '-' },
  ];

  return (
    <div>
      <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading} style={{ marginBottom: 12 }}>
        刷新（近 7 天）
      </Button>
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        {kpis.map((k) => (
          <Card key={k.label} style={{ minWidth: 180, flex: 1 }}>
            <Statistic title={k.label} value={k.value} />
            {k.suffix && <Typography.Text type="secondary" style={{ fontSize: 12 }}>{k.suffix}</Typography.Text>}
          </Card>
        ))}
      </div>
    </div>
  );
}

/** Tab 3: 知识明细 */
function KnowledgePane() {
  const { message } = App.useApp();
  const [list, setList] = useState<KnowledgeMetaItem[]>([]);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setList(await listKnowledgeMeta());
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [message]);

  const columns: ColumnsType<KnowledgeMetaItem> = [
    { title: 'chunkId', dataIndex: 'chunkId', render: (v: string) => v.slice(0, 12) + '…' },
    { title: 'docId', dataIndex: 'docId' },
    { title: '领域', dataIndex: 'domainTag' },
    { title: '子领域', dataIndex: 'subDomainTag' },
    { title: '所需身份', dataIndex: 'requiredIdentity' },
    { title: '创建人', dataIndex: 'createdBy' },
    { title: '来源文件', dataIndex: 'sourceFilename' },
    { title: '版本', dataIndex: 'version' },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      render: (v: string | null) => formatDateTime(v),
    },
  ];

  return (
    <div>
      <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading} style={{ marginBottom: 12 }}>
        加载知识明细
      </Button>
      <Table<KnowledgeMetaItem>
        rowKey="chunkId"
        size="small"
        columns={columns}
        dataSource={list}
        loading={loading}
        pagination={{ pageSize: 10 }}
        locale={{ emptyText: '暂无知识元数据（上传文档并打标后出现）' }}
      />
    </div>
  );
}

/** Tab 4: 反馈审计 */
function AuditPane() {
  const { message } = App.useApp();
  const [feedbacks, setFeedbacks] = useState<FeedbackAuditItem[]>([]);
  const [gateways, setGateways] = useState<GatewayAuditItem[]>([]);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [f, g] = await Promise.all([listFeedbackAudit(), listGatewayAudit()]);
      setFeedbacks(f);
      setGateways(g);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [message]);

  const fbColumns: ColumnsType<FeedbackAuditItem> = [
    { title: 'ID', dataIndex: 'id' },
    { title: 'sessionId', dataIndex: 'sessionId' },
    { title: 'chunkId', dataIndex: 'chunkId' },
    {
      title: '类型',
      dataIndex: 'feedbackType',
      render: (v: string) => (
        <Tag color={v === 'USEFUL' ? 'green' : 'red'}>{v}</Tag>
      ),
    },
    { title: '备注', dataIndex: 'feedbackComment' },
    {
      title: '时间',
      dataIndex: 'createdAt',
      render: (v: string | null) => formatDateTime(v),
    },
  ];

  const gwColumns: ColumnsType<GatewayAuditItem> = [
    { title: 'ID', dataIndex: 'id' },
    { title: '方向', dataIndex: 'direction' },
    { title: '检测器', dataIndex: 'detectorName' },
    {
      title: '结果',
      dataIndex: 'detectionResult',
      render: (v: string) => (
        <Tag color={v === 'BLOCK' ? 'red' : 'green'}>{v}</Tag>
      ),
    },
    { title: '风险分', dataIndex: 'riskScore' },
    { title: 'userId', dataIndex: 'userId' },
    {
      title: '时间',
      dataIndex: 'createdAt',
      render: (v: string | null) => formatDateTime(v),
    },
  ];

  return (
    <div>
      <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading} style={{ marginBottom: 12 }}>
        加载审计数据
      </Button>
      <Typography.Title level={4}>用户反馈</Typography.Title>
      <Table<FeedbackAuditItem>
        rowKey="id"
        size="small"
        columns={fbColumns}
        dataSource={feedbacks}
        loading={loading}
        pagination={{ pageSize: 10 }}
        locale={{ emptyText: '暂无反馈' }}
      />
      <Typography.Title level={4} style={{ marginTop: 24 }}>
        安全网关拦截记录
      </Typography.Title>
      <Table<GatewayAuditItem>
        rowKey="id"
        size="small"
        columns={gwColumns}
        dataSource={gateways}
        loading={loading}
        pagination={{ pageSize: 10 }}
        locale={{ emptyText: '暂无网关审计记录' }}
      />
    </div>
  );
}

/** 可观测 Tab：执行路径 / 业务指标 / 知识明细 / 反馈审计。 */
export default function ObservePanel({ initialSessionId }: { initialSessionId?: string | null }) {
  const [active, setActive] = useState('trace');

  return (
    <div>
      <Typography.Title level={3}>可观测</Typography.Title>
      <Tabs
        activeKey={active}
        onChange={setActive}
        items={[
          { key: 'trace', label: '执行路径', children: <TracePane initialSessionId={initialSessionId} /> },
          { key: 'metrics', label: '业务指标', children: <MetricsPane /> },
          { key: 'knowledge', label: '知识明细', children: <KnowledgePane /> },
          { key: 'audit', label: '反馈审计', children: <AuditPane /> },
        ]}
      />
    </div>
  );
}
