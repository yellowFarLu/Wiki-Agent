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
import { getAnswerEvalSamples, getMetricsAggregation, getRagasRuns, getRagasRunDetail, triggerRagasRun } from '@/lib/api';
import { formatDateTime } from '@/lib/datetime';
import type {
  AnswerEvalSampleItem,
  MetricsAggregation,
  RagasRun,
  RagasRunDetail,
  RagasSample,
  StaleKnowledgeItem,
  SuspectUselessKnowledgeItem,
} from '@/lib/types';
import ConflictReviewPanel from './ConflictReviewPanel';

/** null = 分母 0（无数据），显示 '-' 而非 0%（诚实原则）。 */
function pct(v?: number | null) {
  if (v === undefined || v === null || Number.isNaN(v)) return '-';
  return `${(v * 100).toFixed(1)}%`;
}

/** RAGAS 逐项评分标签：null=未评（评分失败/无法评判），禁止显示为 0 分。 */
function scoreTag(v: number | null | undefined) {
  if (v === null || v === undefined) return <Tag>未评</Tag>;
  const color = v >= 0.8 ? 'green' : v >= 0.5 ? 'orange' : 'red';
  return <Tag color={color}>{v.toFixed(3)}</Tag>;
}

/** RAGAS 用例展开行：答案/参考/contexts/补充指标/评分错误，JSON 解析失败如实兜底。 */
function renderRagasSampleExpand(s: RagasSample) {
  let contextList: string[] = [];
  let errorMap: Record<string, string> = {};
  try {
    const parsed: unknown = JSON.parse(s.contexts);
    if (Array.isArray(parsed)) contextList = parsed;
  } catch {
    contextList = [];
  }
  try {
    if (s.errors) {
      const parsed: unknown = JSON.parse(s.errors);
      if (parsed && typeof parsed === 'object') errorMap = parsed as Record<string, string>;
    }
  } catch {
    errorMap = {};
  }
  return (
    <div style={{ padding: '4px 8px' }}>
      <p><b>助手回答：</b>{s.answer}</p>
      <p><b>参考答案：</b>{s.reference}</p>
      <div><b>检索 contexts（{contextList.length}）：</b></div>
      {contextList.map((c, i) => (
        <p key={i} style={{ marginLeft: 12, color: '#555' }}>{c}</p>
      ))}
      <p>
        <b>事实正确性：</b>{scoreTag(s.factualCorrectness)}
        <span style={{ marginLeft: 16 }}><b>语义相似度：</b>{scoreTag(s.semanticSimilarity)}</span>
      </p>
      {Object.keys(errorMap).length > 0 && (
        <div>
          <b>评分错误明细：</b>
          {Object.entries(errorMap).map(([k, v]) => (
            <p key={k} style={{ marginLeft: 12, color: '#c00' }}>{k}: {v}</p>
          ))}
        </div>
      )}
    </div>
  );
}

function KnowledgeDashboard() {
  const { message } = App.useApp();
  const [data, setData] = useState<MetricsAggregation | null>(null);
  const [samples, setSamples] = useState<AnswerEvalSampleItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [ragasRuns, setRagasRuns] = useState<RagasRun[]>([]);
  const [ragasDetail, setRagasDetail] = useState<RagasRunDetail | null>(null);
  const [ragasExecuting, setRagasExecuting] = useState(false);

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

  /** 选中某次历史执行并拉取用例集。 */
  const selectRagasRun = useCallback(async (runId: string) => {
    try {
      const detail = await getRagasRunDetail(runId);
      setRagasDetail(detail);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '评测详情加载失败');
    }
  }, [message]);

  const loadRagasRuns = useCallback(async (selectLatest = true) => {
    const runs = await getRagasRuns();
    setRagasRuns(runs);
    const running = runs.find((r) => r.status === 'RUNNING');
    if (running) {
      void selectRagasRun(running.runId);
    } else if (selectLatest && runs[0]) {
      void selectRagasRun(runs[0].runId);
    }
  }, [selectRagasRun]);

  /** 触发评测并轮询到终态（与服务端 timeout 对齐，前端最多等 540 秒）。 */
  const runRagas = useCallback(async () => {
    setRagasExecuting(true);
    try {
      const started = await triggerRagasRun();
      if (!started.runId) {
        message.warning(started.message || '评测未能启动');
        return;
      }
      message.info(started.message || '评测已提交');
      const deadlineMs = Date.now() + 540_000;
      // 占位轮询
      while (Date.now() < deadlineMs) {
        await new Promise((r) => setTimeout(r, 3000));
        const detail = await getRagasRunDetail(started.runId);
        setRagasDetail(detail);
        if (detail.run.status !== 'RUNNING') {
          await loadRagasRuns(false);
          if (detail.run.status === 'OK') {
            message.success('RAGAS 评测完成');
          } else {
            message.warning(`RAGAS 评测结束：${detail.run.status}（${detail.run.reason ?? '见详情'}）`);
          }
          return;
        }
      }
      message.error('等待 RAGAS 评测结果超时');
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'RAGAS 评测触发失败');
    } finally {
      setRagasExecuting(false);
    }
  }, [loadRagasRuns, message]);

  useEffect(() => {
    void load(false);
  }, [load]);

  useEffect(() => {
    void loadRagasRuns(true);
  }, [loadRagasRuns]);

  const eval_ = data?.answerEval;
  const kpis = [
    { label: '知识总量', value: data?.totalKnowledge ?? '-' },
    {
      label: '近 30 天检索',
      value: `${data?.totalRetrievals ?? '-'}（引用 ${data?.totalCitations ?? '-'} 次）`,
    },
    {
      label: '用户反馈（原始计数）',
      value: `${data?.usefulCount ?? '-'} 有用 / ${data?.uselessCount ?? '-'} 无用`,
    },
    {
      label: '疑似无用知识',
      value: `${data?.suspectUselessKnowledgeCount ?? '-'}（访问过且无用占比 > 50%）`,
    },
    { label: '上下文召回率（RAGAS 最新）', value: pct(ragasRuns[0]?.contextRecall) },
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

  const suspectColumns: ColumnsType<SuspectUselessKnowledgeItem> = [
    {
      title: 'chunkId',
      dataIndex: 'chunkId',
      render: (v: string) => v.slice(0, 12) + '…',
      ellipsis: true,
    },
    {
      title: '来源文件',
      dataIndex: 'sourceFilename',
      render: (v: string | null) => v || '-',
      ellipsis: true,
    },
    { title: 'docId', dataIndex: 'docId', render: (v: string | null) => v || '-' },
    { title: '领域', dataIndex: 'domainTag', render: (v: string | null) => v || '-' },
    { title: '子领域', dataIndex: 'subDomainTag', render: (v: string | null) => v || '-' },
    { title: '创建人', dataIndex: 'createdBy', render: (v: string | null) => v || '-' },
    { title: '访问次数', dataIndex: 'retrievalCount' },
    { title: '有用', dataIndex: 'usefulCount' },
    { title: '无用', dataIndex: 'uselessCount' },
    {
      title: '无用占比',
      dataIndex: 'uselessRatio',
      render: (v: number, row) => (
        <Tag color={v >= 0.8 ? 'red' : 'orange'}>
          {(v * 100).toFixed(1)}%（{row.uselessCount}/{row.totalFeedback}）
        </Tag>
      ),
      sorter: (a, b) => a.uselessRatio - b.uselessRatio,
      defaultSortOrder: 'descend',
    },
  ];

  // RAGAS 历史执行表列
  const ragasColumns: ColumnsType<RagasRun> = [
    { title: 'runId', dataIndex: 'runId', ellipsis: true },
    {
      title: '状态', dataIndex: 'status', width: 90,
      render: (v: string) => (
        <Tag color={v === 'OK' ? 'green' : v === 'ERROR' ? 'red' : 'blue'}>
          {v === 'OK' ? '成功' : v === 'ERROR' ? '失败' : '执行中'}
        </Tag>
      ),
    },
    { title: '开始时间', dataIndex: 'startedAt', width: 160, render: (v: string | null) => formatDateTime(v) },
    { title: '耗时(s)', dataIndex: 'durationSec', width: 90, render: (v: number | null) => v?.toFixed(1) ?? '-' },
    { title: '用例数', dataIndex: 'sampleCount', width: 80 },
    { title: '忠实度', dataIndex: 'faithfulness', width: 90, render: scoreTag },
    { title: '答案相关性', dataIndex: 'answerRelevancy', width: 100, render: scoreTag },
    { title: '上下文精确率', dataIndex: 'contextPrecision', width: 110, render: scoreTag },
    { title: '上下文召回率', dataIndex: 'contextRecall', width: 110, render: scoreTag },
  ];

  // RAGAS 评测用例集表列（展开行显示答案/参考/contexts/错误）
  const ragasSampleColumns: ColumnsType<RagasSample> = [
    { title: '用例ID', dataIndex: 'sampleId', width: 200, ellipsis: true },
    { title: '领域', dataIndex: 'domainTag', width: 130, render: (v: string | null) => v || '-' },
    { title: '问题', dataIndex: 'question' },
    { title: '忠实度', dataIndex: 'faithfulness', width: 90, render: scoreTag },
    { title: '答案相关性', dataIndex: 'answerRelevancy', width: 100, render: scoreTag },
    { title: '上下文精确率', dataIndex: 'contextPrecision', width: 110, render: scoreTag },
    { title: '上下文召回率', dataIndex: 'contextRecall', width: 110, render: scoreTag },
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
        <Button
          onClick={() => void runRagas()}
          loading={ragasExecuting}
          style={{ marginRight: 8 }}
        >
          执行 RAGAS 评测
        </Button>
        <Typography.Text type="secondary">
          每日 02:00 自动聚合（时间衰减 τ=180 天 + 30 天使用频率）；RAGAS 评测由本按钮触发，执行约 1-5 分钟
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
      <Typography.Title level={4}>疑似无用知识（被访问过且无用反馈占比 &gt; 50%）</Typography.Title>
      <Table<SuspectUselessKnowledgeItem>
        rowKey="chunkId"
        size="small"
        columns={suspectColumns}
        dataSource={data?.suspectUselessKnowledge ?? []}
        loading={loading}
        pagination={{ pageSize: 10 }}
        locale={{ emptyText: '暂无疑似无用知识' }}
      />
      <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
        判定口径：知识被检索访问过（RETRIEVED ≥ 1），且 kb_feedback 中无用次数占反馈总数（有用+无用）比例严格超过 50%；
        占比等于 50% 不入选，未被访问或无反馈的知识不展示。该清单仅用于集中治理，不作为知识质量考核指标。
      </Typography.Paragraph>
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

      <Typography.Title level={4}>RAGAS 离线评测（ragas 官方指标，点击历史行查看用例集）</Typography.Title>
      {ragasDetail?.run.status === 'ERROR' && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          title={`本次评测状态为失败：${ragasDetail.run.reason ?? '见用例评分错误明细'}`}
        />
      )}
      <Table<RagasRun>
        rowKey="id"
        size="small"
        columns={ragasColumns}
        dataSource={ragasRuns}
        pagination={{ pageSize: 10 }}
        locale={{ emptyText: '暂无 RAGAS 评测记录，点击上方“执行 RAGAS 评测”按钮' }}
        onRow={(r) => ({ onClick: () => void selectRagasRun(r.runId) })}
        rowClassName={(r) => (r.runId === ragasDetail?.run.runId ? 'ant-table-row-selected' : '')}
      />
      {ragasDetail && (
        <div style={{ marginTop: 12 }}>
          <Typography.Paragraph type="secondary">
            选中执行：{ragasDetail.run.runId}（judge: {ragasDetail.run.judgeModel ?? '-'} /
            embedding: {ragasDetail.run.embeddingModel ?? '-'} / 开始 {formatDateTime(ragasDetail.run.startedAt)}）
          </Typography.Paragraph>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 12 }}>
            <Card style={{ minWidth: 180, flex: 1 }}>
              <Statistic title="忠实度（忠诚度）" value={pct(ragasDetail.run.faithfulness)} />
            </Card>
            <Card style={{ minWidth: 180, flex: 1 }}>
              <Statistic title="答案相关性" value={pct(ragasDetail.run.answerRelevancy)} />
            </Card>
            <Card style={{ minWidth: 180, flex: 1 }}>
              <Statistic title="上下文精确率" value={pct(ragasDetail.run.contextPrecision)} />
            </Card>
            <Card style={{ minWidth: 180, flex: 1 }}>
              <Statistic title="上下文召回率" value={pct(ragasDetail.run.contextRecall)} />
            </Card>
          </div>
          <Table<RagasSample>
            rowKey="id"
            size="small"
            columns={ragasSampleColumns}
            dataSource={ragasDetail.samples}
            pagination={{ pageSize: 10 }}
            expandable={{ expandedRowRender: renderRagasSampleExpand }}
            locale={{ emptyText: '该执行暂无评测用例' }}
          />
          <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
            指标为 RAGAS 框架官方实现（ragas==0.4.3）；"未评"表示该样本该指标评分失败（展开可见错误），不计入分母；
            无评分时显示"-"，不显示 0。
          </Typography.Paragraph>
        </div>
      )}

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
