'use client';

import { memo, useCallback, useMemo, useState } from 'react';
import { Button, Tabs, Typography } from 'antd';
import {
  AuditOutlined,
  CloudUploadOutlined,
  FundOutlined,
  MessageOutlined,
  NodeIndexOutlined,
  RollbackOutlined,
  ScheduleOutlined,
  SettingOutlined,
  ToolOutlined,
} from '@ant-design/icons';
import { useRouter, useSearchParams } from 'next/navigation';
import ChatPanel from '@/components/chat/ChatPanel';
import UploadPanel from '@/components/upload/UploadPanel';
import RecentDocuments from '@/components/upload/RecentDocuments';
import TaskTable from '@/components/tasks/TaskTable';
import TaskDetail from '@/components/tasks/TaskDetail';
import ResultsPanel from '@/components/results/ResultsPanel';
import ReviewCasesTab from '@/components/workbench/ReviewCasesTab';
import HumanTasksTab from '@/components/workbench/HumanTasksTab';
import VersionHistoryTab from '@/components/workbench/VersionHistoryTab';
import GraphPanel from '@/components/graph/GraphPanel';
import GovernPanel from '@/components/govern/GovernPanel';
import ObservePanel from '@/components/observe/ObservePanel';
import LocalSettingsForm from '@/components/settings/LocalSettingsForm';
import AdminIdentityPanel from '@/components/settings/AdminIdentityPanel';

const TAB_KEYS = [
  'chat',
  'upload',
  'tasks',
  'workbench',
  'graph',
  'govern',
  'observe',
  'settings',
] as const;
type TabKey = (typeof TAB_KEYS)[number];

function isTabKey(v: string | null): v is TabKey {
  return v !== null && (TAB_KEYS as readonly string[]).includes(v);
}

/** 材料上传 Tab：默认上传面板 + 最近文档；docId 参数存在时切换为结构化结果（原 /results/[docId]）。 */
const UploadPane = memo(function UploadPane({ docId, onBack }: { docId: string | null; onBack: () => void }) {
  const [tick, setTick] = useState(0);

  if (docId) {
    return (
      <div>
        <Button icon={<RollbackOutlined />} onClick={onBack} style={{ marginBottom: 16 }}>
          返回上传与文档列表
        </Button>
        <Typography.Title level={3} className="wa-page-title">
          结构化结果
        </Typography.Title>
        <ResultsPanel docId={docId} />
      </div>
    );
  }
  return (
    <div>
      <Typography.Title level={3} className="wa-page-title">
        材料上传
      </Typography.Title>
      <UploadPanel onUploaded={() => setTick((t) => t + 1)} />
      <RecentDocuments key={tick} />
    </div>
  );
});

/** 任务中心 Tab：默认任务列表；taskId 参数存在时切换为任务详情（原 /tasks/[taskId]）。 */
const TasksPane = memo(function TasksPane({ taskId, onBack }: { taskId: string | null; onBack: () => void }) {
  if (taskId) {
    return (
      <div>
        <Button icon={<RollbackOutlined />} onClick={onBack} style={{ marginBottom: 16 }}>
          返回任务列表
        </Button>
        <Typography.Title level={3} className="wa-page-title">
          任务详情
        </Typography.Title>
        <TaskDetail taskId={taskId} />
      </div>
    );
  }
  return (
    <div>
      <Typography.Title level={3} className="wa-page-title">
        任务中心
      </Typography.Title>
      <TaskTable />
    </div>
  );
});

/** 人工工作台 Tab：保留原有页内三 Tabs（复核案件 / 人工任务 / 历史版本）。 */
const WorkbenchPane = memo(function WorkbenchPane() {
  const [activeTab, setActiveTab] = useState('review');
  return (
    <div>
      <Typography.Title level={3} className="wa-page-title">
        人工工作台
      </Typography.Title>
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          { key: 'review', label: '复核案件', children: <ReviewCasesTab /> },
          {
            key: 'human',
            label: '人工任务',
            children: <HumanTasksTab onGoReview={() => setActiveTab('review')} />,
          },
          { key: 'versions', label: '历史版本', children: <VersionHistoryTab /> },
        ]}
      />
    </div>
  );
});

/**
 * 统一控制台大页面：新老页面功能合并为 8 个顶部 Tabs。
 * 顺序即产品动线：对话（主功能）→ 材料上传 → 任务中心 → 人工工作台 →
 * 知识图谱 → 知识治理 → 可观测 → 身份设置。
 * Tab 状态与 URL 查询串双向同步（?tab=xxx），刷新/分享链接保持当前 Tab；
 * 旧深链 /tasks/[id]、/results/[docId] 由旧路由 307 重定向为
 * ?tab=tasks&taskId= 与 ?tab=upload&docId=，详情视图在对应 Tab 内展开；
 * 对话「查链路」跳转 ?tab=observe&sessionId= 自动查询执行路径。
 * 已激活的 Tab 保持挂载（antd Tabs 默认不销毁），对话 SSE 等状态切 Tab 不丢失。
 */
/**
 * 无 props 的重型面板提升为模块级常量元素：引用永久不变，
 * Console 重渲染（如切 Tab、URL 参数变化）时 React 自动 bail out 这些子树，
 * 避免每次都对已挂载的面板做 reconcile。
 */
const CHAT_PANE = <ChatPanel />;
const WORKBENCH_PANE = <WorkbenchPane />;
const GRAPH_PANE = <GraphPanel />;
const GOVERN_PANE = <GovernPanel />;
const SETTINGS_PANE = (
  <div>
    <Typography.Title level={3} className="wa-page-title">
      身份设置
    </Typography.Title>
    <LocalSettingsForm />
    <AdminIdentityPanel />
  </div>
);

export default function Console() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const tabParam = searchParams.get('tab');
  const activeKey: TabKey = isTabKey(tabParam) ? tabParam : 'chat';
  const taskId = searchParams.get('taskId');
  const docId = searchParams.get('docId');
  const sessionId = searchParams.get('sessionId');

  const uploadDocId = activeKey === 'upload' ? docId : null;
  const tasksTaskId = activeKey === 'tasks' ? taskId : null;
  const observeSessionId = activeKey === 'observe' ? sessionId : null;

  const gotoTab = useCallback(
    (tab: string) => {
      // 手动切 Tab 时清除详情参数，避免回到 Tab 仍停在详情视图
      router.replace(`/?tab=${tab}`, { scroll: false });
    },
    [router],
  );
  const backToUpload = useCallback(() => router.replace('/?tab=upload', { scroll: false }), [router]);
  const backToTasks = useCallback(() => router.replace('/?tab=tasks', { scroll: false }), [router]);

  const items = useMemo(
    () => [
      {
        key: 'chat',
        label: (
          <span>
            <MessageOutlined /> 对话
          </span>
        ),
        children: CHAT_PANE,
      },
      {
        key: 'upload',
        label: (
          <span>
            <CloudUploadOutlined /> 材料上传
          </span>
        ),
        children: <UploadPane docId={uploadDocId} onBack={backToUpload} />,
      },
      {
        key: 'tasks',
        label: (
          <span>
            <ScheduleOutlined /> 任务中心
          </span>
        ),
        children: <TasksPane taskId={tasksTaskId} onBack={backToTasks} />,
      },
      {
        key: 'workbench',
        label: (
          <span>
            <ToolOutlined /> 人工工作台
          </span>
        ),
        children: WORKBENCH_PANE,
      },
      {
        key: 'graph',
        label: (
          <span>
            <NodeIndexOutlined /> 知识图谱
          </span>
        ),
        children: GRAPH_PANE,
      },
      {
        key: 'govern',
        label: (
          <span>
            <AuditOutlined /> 知识治理
          </span>
        ),
        children: GOVERN_PANE,
      },
      {
        key: 'observe',
        label: (
          <span>
            <FundOutlined /> 可观测
          </span>
        ),
        children: <ObservePanel initialSessionId={observeSessionId} />,
      },
      {
        key: 'settings',
        label: (
          <span>
            <SettingOutlined /> 身份设置
          </span>
        ),
        children: SETTINGS_PANE,
      },
    ],
    [uploadDocId, tasksTaskId, observeSessionId, backToUpload, backToTasks],
  );

  return (
    <Tabs
      className="wa-main-tabs"
      activeKey={activeKey}
      onChange={gotoTab}
      size="large"
      // antd v6 默认会销毁隐藏面板（切走 ChatPanel 会中断 SSE、切回重载并重播入场动画）；
      // 显式保活已访问面板，维持「对话 SSE/表单状态切 Tab 不丢失」的产品行为。
      destroyOnHidden={false}
      tabBarStyle={{ marginBottom: 24 }}
      items={items}
    />
  );
}
