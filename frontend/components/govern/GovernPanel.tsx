'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  Statistic,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { getAnswerEvalSamples, getMetricsAggregation } from '@/lib/api';
import { formatDateTime } from '@/lib/datetime';
import type {
  AnswerEvalSampleItem,
  MetricsAggregation,
  StaleKnowledgeItem,
} from '@/lib/types';
import ConflictReviewPanel from './ConflictReviewPanel';

/** null = 分母 0（无数据），显示 '-' 而非 0%（诚实原则）。 */
function pct(v?: number | null) {
  if (v === undefined || v === null || Number.isNaN(v)) return '-';
  return `${(v * 100).toFixed(1)}%`;
}

function KnowledgeDashboard() {
  const { message } = App.useApp();
  const [data, setData] = useState<MetricsAggregation | null>(null);
  const [samples, setSamples] = useState<AnswerEvalSampleItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(
    async (refresh = false) => {
      if (refresh) setRefreshing(true);
      else setLoading(true);
      try {
        const [agg, sampleRows] = await Promise.all([
          getMetricsAggregation(refresh),
          getAnswerEvalSamples(20),
        ]);
        setData(agg);
        setSamples(sampleRows ?? []);
      } catch (e) {
        message.error(e instanceof Error ? e.message : '聚合数据加载失败');
      } finally {
        setLoading(false);
        setRefreshing(false);
      }
    },
    [message],
  );

  useEffect(() => {
    void load(false);
  }, [load]);

  const eval_ = data?.answerEval;
  const kpis = [
    { label: '知识总量', value: data?.totalKnowledge ?? '-' },
    {
      label: '近 30 天检索',
      value: `${data?.totalRetrievals ?? '-'}（引用 ${data?.totalCitations ?? '-'} 次）`,
    },
    {
      label: '有用率',
      value: `${pct(data?.usefulnessRate)}（${data?.usefulCount ?? '-'} 有用 / ${data?.uselessCount ?? '-'} 无用）`,
    },
    { label: '召回率', value: pct(data?.recallRate) },
    {
      label: 'RAG 准确率',
      value: eval_
        ? `${pct(eval_.accuracyRate)}（忠实 ${pct(eval_.faithfulnessRate)} / 相关 ${pct(eval_.relevanceRate)}；已评 ${eval_.judgedCount ?? 0} / 待评 ${eval_.pendingCount ?? 0}）`
        : '暂无数据',
    },
    { label: '疑似过期', value: `${data?.staleKnowledgeCount ?? '-'}（阈值 stale<0.3，τ=180d）` },
  ];

  const staleColumns: ColumnsType<StaleKnowledgeItem> = [
    {
      title: 'chunkId',
      dataIndex: 'chunkId',
      render: (v: string) => v.slice(0, 12) + '…',
      ellipsis: true,
    },
    { title: 'docId', dataIndex: 'docId' },
    { title: '领域', dataIndex: 'domainTag' },
    { title: '子领域', dataIndex: 'subDomainTag' },
    { title: '创建人', dataIndex: 'createdBy' },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      render: (v: string | null) => formatDateTime(v),
    },
    { title: '近 30 天检索', dataIndex: 'recentRetrievals' },
    {
      title: 'stale_score',
      dataIndex: 'staleScore',
      render: (v: number) => (
        <Tag color={v < 0.15 ? 'red' : 'orange'}>{v.toFixed(3)}</Tag>
      ),
    },
  ];

  /** 1=通过 0=不通过 null=无法评判（诚实展示，不捏造）。 */
  function verdictTag(v: number | null | undefined, yes: string, no: string, na: string) {
    if (v === null || v === undefined) return <Tag>{na}</Tag>;
    return <Tag color={v === 1 ? 'green' : 'red'}>{v === 1 ? yes : no}</Tag>;
  }

  const sampleColumns: ColumnsType<AnswerEvalSampleItem> = [
    {
      title: '问题摘要',
      dataIndex: 'question',
      render: (v: string | null) => (v ?? '').slice(0, 40) || '-',
      ellipsis: true,
    },
    { title: '链路', dataIndex: 'channel', width: 110 },
    {
      title: '忠实度',
      dataIndex: 'faithfulness',
      width: 80,
      render: (v: number | null) => verdictTag(v, '有据', '无据', '未评'),
    },
    {
      title: '相关性',
      dataIndex: 'relevance',
      width: 80,
      render: (v: number | null) => verdictTag(v, '切题', '不切题', '未评'),
    },
    {
      title: '判定原因',
      dataIndex: 'verdictReason',
      ellipsis: true,
      render: (v: string | null) => v || '-',
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (v: string) => (
        <Tag color={v === 'JUDGED' ? 'green' : v === 'FAILED' ? 'red' : 'orange'}>
          {v === 'JUDGED' ? '已评' : v === 'FAILED' ? '失败' : '待评'}
        </Tag>
      ),
    },
    {
      title: '评判模型',
      dataIndex: 'judgeModel',
      width: 120,
      render: (v: string | null) => v || '-',
    },
    {
      title: '时间',
      dataIndex: 'createdAt',
      width: 160,
      render: (v: string | null) => formatDateTime(v),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 12 }}>
        <Button
          type="primary"
          icon={<ReloadOutlined />}
          onClick={() => void load(true)}
          loading={refreshing}
          style={{ marginRight: 8 }}
        >
          立即聚合
        </Button>
        <Typography.Text type="secondary">
          每日 02:00 自动聚合（时间衰减 τ=180 天 + 30 天使用频率）
        </Typography.Text>
      </div>
      {data?.aggregatedAt === null && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          description={data.message || '尚未聚合，点击“立即聚合”查看'}
        />
      )}
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 16 }}>
        {kpis.map((k) => (
          <Card key={k.label} style={{ minWidth: 200, flex: 1 }}>
            <Statistic title={k.label} value={k.value} />
          </Card>
        ))}
      </div>
      <Typography.Title level={4}>疑似过期知识（stale_score &lt; 0.3）</Typography.Title>
      <Table<StaleKnowledgeItem>
        rowKey="chunkId"
        size="small"
        columns={staleColumns}
        dataSource={data?.staleKnowledge ?? []}
        loading={loading}
        pagination={{ pageSize: 10 }}
        locale={{ emptyText: '暂无疑似过期知识' }}
      />
      <Typography.Title level={4}>最近 RAG 回答评测样本（LLM-as-judge 抽样评判）</Typography.Title>
      <Table<AnswerEvalSampleItem>
        rowKey="id"
        size="small"
        columns={sampleColumns}
        dataSource={samples}
        loading={loading}
        pagination={{ pageSize: 10 }}
        locale={{ emptyText: '暂无评判样本' }}
      />
      <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
        准确率/忠实度/相关性由 LLM-as-judge 对回答与引用资料抽样评判得出（详见 docs/rag-accuracy-eval.md）；
        “未评”表示该维度无法评判（如回答未引用知识库文档），不计入分母。
      </Typography.Paragraph>
    </div>
  );
}

/** 知识治理 Tab：知识看板 + 冲突审核两个子页。 */
export default function GovernPanel() {
  const [active, setActive] = useState('dashboard');
  return (
    <div>
      <Typography.Title level={3}>知识治理</Typography.Title>
      <Tabs
        activeKey={active}
        onChange={setActive}
        items={[
          { key: 'dashboard', label: '知识看板', children: <KnowledgeDashboard /> },
          { key: 'conflicts', label: '冲突审核', children: <ConflictReviewPanel /> },
        ]}
      />
    </div>
  );
}
