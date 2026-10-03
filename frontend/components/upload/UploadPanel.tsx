'use client';

import { useState } from 'react';
import { Alert, App, Card, DatePicker, Select, Space, Typography, Upload } from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import Link from 'next/link';
import dayjs, { type Dayjs } from 'dayjs';
import type { UploadProps } from 'antd';
import { uploadDocument } from '@/lib/api';
import { DOMAIN_OPTIONS, getSettings, SUB_DOMAIN_OPTIONS } from '@/lib/settings';
import type { DocumentView } from '@/lib/types';

interface UploadResult {
  key: string;
  filename: string;
  state: 'uploading' | 'success' | 'duplicate' | 'failed';
  doc?: DocumentView;
  error?: string;
}

export default function UploadPanel({ onUploaded }: { onUploaded: () => void }) {
  const { message } = App.useApp();
  const [results, setResults] = useState<UploadResult[]>([]);
  const [domain, setDomain] = useState<string | undefined>(undefined);
  const [subDomain, setSubDomain] = useState<string | undefined>(undefined);
  const [effectiveDate, setEffectiveDate] = useState<Dayjs>(dayjs());

  const doUpload = async (file: File) => {
    const key = `${file.name}-${Date.now()}-${Math.random()}`;
    setResults((prev) => [{ key, filename: file.name, state: 'uploading' }, ...prev]);
    const settings = getSettings();
    const effectiveDomain = domain || settings.domain || undefined;
    const effectiveSubDomain = subDomain || settings.subDomain || undefined;
    try {
      const doc = await uploadDocument(
        file,
        effectiveDomain,
        effectiveSubDomain,
        effectiveDate.format('YYYY-MM-DD'),
      );
      setResults((prev) =>
        prev.map((r) =>
          r.key === key
            ? { ...r, state: doc.duplicate ? 'duplicate' : 'success', doc }
            : r,
        ),
      );
      if (doc.duplicate) {
        message.warning(`「${file.name}」重复上传，已关联既有任务`);
      } else {
        message.success(`「${file.name}」上传成功`);
      }
      onUploaded();
    } catch (e) {
      const errMsg = e instanceof Error ? e.message : '上传失败';
      setResults((prev) =>
        prev.map((r) => (r.key === key ? { ...r, state: 'failed', error: errMsg } : r)),
      );
      message.error(`「${file.name}」上传失败：${errMsg}`);
    }
  };

  const props: UploadProps = {
    multiple: true,
    showUploadList: false,
    customRequest: (options) => {
      void doUpload(options.file as File);
    },
  };

  return (
    <Card title="上传材料" style={{ marginBottom: 24 }}>
      <Space style={{ marginBottom: 12 }} wrap>
        <Typography.Text type="secondary">本批业务域：</Typography.Text>
        <Select
          style={{ width: 180 }}
          allowClear
          placeholder="沿用全局默认"
          options={DOMAIN_OPTIONS}
          value={domain}
          onChange={(v) => setDomain(v)}
        />
        <Typography.Text type="secondary">本批子域：</Typography.Text>
        <Select
          style={{ width: 180 }}
          allowClear
          placeholder="沿用全局默认"
          options={SUB_DOMAIN_OPTIONS}
          value={subDomain}
          onChange={(v) => setSubDomain(v)}
        />
        <Typography.Text type="secondary">生效日期：</Typography.Text>
        <DatePicker
          style={{ width: 160 }}
          allowClear={false}
          value={effectiveDate}
          onChange={(v) => setEffectiveDate(v ?? dayjs())}
        />
        <Typography.Text type="secondary">文档内容生效日期，冲突时以此为准</Typography.Text>
      </Space>
      <Upload.Dragger {...props}>
        <p className="ant-upload-drag-icon">
          <InboxOutlined />
        </p>
        <p className="ant-upload-text">点击或拖拽文件到此区域上传</p>
        <p className="ant-upload-hint">支持多文件上传；不选择业务域时将使用身份设置中的全局默认</p>
      </Upload.Dragger>

      {results.length > 0 && (
        <div style={{ marginTop: 16 }}>
          {results.map((r) => {
            if (r.state === 'uploading') {
              return <Alert key={r.key} type="info" showIcon title={`「${r.filename}」上传中…`} style={{ marginBottom: 8 }} />;
            }
            if (r.state === 'success') {
              return (
                <Alert
                  key={r.key}
                  type="success"
                  showIcon
                  style={{ marginBottom: 8 }}
                  title={
                    <Space>
                      <span>「{r.filename}」上传成功</span>
                      {r.doc?.taskId && (
                        <Link href={`/?tab=tasks&taskId=${encodeURIComponent(r.doc.taskId)}`}>查看任务 {r.doc.taskId.slice(0, 8)}…</Link>
                      )}
                    </Space>
                  }
                />
              );
            }
            if (r.state === 'duplicate') {
              return (
                <Alert
                  key={r.key}
                  type="warning"
                  showIcon
                  style={{ marginBottom: 8 }}
                  title={
                    <Space>
                      <span>「{r.filename}」重复上传，已关联既有任务</span>
                      {r.doc?.taskId && (
                        <Link href={`/?tab=tasks&taskId=${encodeURIComponent(r.doc.taskId)}`}>查看任务 {r.doc.taskId.slice(0, 8)}…</Link>
                      )}
                    </Space>
                  }
                />
              );
            }
            return (
              <Alert
                key={r.key}
                type="error"
                showIcon
                style={{ marginBottom: 8 }}
                title={`「${r.filename}」上传失败`}
                description={r.error}
              />
            );
          })}
        </div>
      )}
    </Card>
  );
}
