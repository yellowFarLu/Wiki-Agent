'use client';

import { memo, type ReactNode } from 'react';
import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Popover, Tag, Typography } from 'antd';
import type { Source } from '@/lib/types';

interface Props {
  text: string;
  sources: Source[];
}

/**
 * rehype 插件：把正文文本中的 [1][2] 引用标记替换为自定义 hast 元素
 * {@code <cite-ref idx="1" />}，再由 react-markdown 的 components 映射成可点击角标。
 *
 * 为什么不用正则切文本：Markdown 交给 react-markdown 统一解析，引用标记必须在 AST
 * 阶段处理，才能在加粗/列表/表格等任意语法中都保持「Markdown 渲染 + 角标 Popover」
 * 两者同时正确；也能避免把引用误判为 reference-style 链接。
 * {@code <code>} 内不处理，保证代码片段里的 [1] 原样展示。
 */
function rehypeCitations() {
  const CITATION = /\[(\d+)\]/g;

  type HastNode = {
    type: string;
    tagName?: string;
    value?: string;
    properties?: Record<string, unknown>;
    children?: HastNode[];
  };

  const splitText = (value: string): HastNode[] => {
    const out: HastNode[] = [];
    let last = 0;
    let m: RegExpExecArray | null;
    CITATION.lastIndex = 0;
    while ((m = CITATION.exec(value)) !== null) {
      if (m.index > last) {
        out.push({ type: 'text', value: value.slice(last, m.index) });
      }
      out.push({
        type: 'element',
        tagName: 'cite-ref',
        properties: { idx: Number(m[1]) },
        children: [],
      });
      last = m.index + m[0].length;
    }
    if (last < value.length) {
      out.push({ type: 'text', value: value.slice(last) });
    }
    return out;
  };

  const walk = (node: HastNode) => {
    if (!node.children) {
      return;
    }
    const next: HastNode[] = [];
    for (const child of node.children) {
      if (
        child.type === 'text' &&
        typeof child.value === 'string' &&
        node.tagName !== 'code' &&
        /\[\d+\]/.test(child.value)
      ) {
        next.push(...splitText(child.value));
      } else {
        walk(child);
        next.push(child);
      }
    }
    node.children = next;
  };

  return (tree: unknown) => {
    walk(tree as HastNode);
  };
}

// 插件数组模块级常量：保持引用稳定，配合 memo 避免每次流式更新都重建插件与重解析。
const REMARK_PLUGINS = [remarkGfm];
const REHYPE_PLUGINS = [rehypeCitations];

// 含自定义 hast 标签 cite-ref，用计算键 + 类型断言注册（Components 默认仅含标准 HTML 标签）。
function buildComponents(sources: Source[]): Components {
  return {
    a: ({ children, href }) => (
      <a href={href} target="_blank" rel="noopener noreferrer">
        {children as ReactNode}
      </a>
    ),
    ['cite-ref' as string]: ({ idx }: { idx?: number }) =>
      idx === undefined ? null : <CitationBadge idx={idx} sources={sources} />,
  } as Components;
}

/** 引用角标：点击弹出来源详情，内容与历史版本完全一致。 */
function CitationBadge({ idx, sources }: { idx: number; sources: Source[] }) {
  const source = sources.find((s) => s.index === idx);
  if (!source) {
    return <span>[{idx}]</span>;
  }
  return (
    <Popover
      title={
        <span>
          来源 [{source.index}] {source.filename}
          {source.pageNo !== null && <Tag style={{ marginLeft: 8 }}>第 {source.pageNo} 页</Tag>}
        </span>
      }
      content={
        <div style={{ maxWidth: 420 }}>
          <Typography.Paragraph style={{ fontSize: 12, whiteSpace: 'pre-wrap' }}>
            {source.snippet}
          </Typography.Paragraph>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            文档 ID：{source.docId} ｜ 版本：v{source.versionNo} ｜ 相关度：
            {source.score.toFixed(3)}
          </Typography.Text>
        </div>
      }
    >
      <sup
        style={{
          color: '#1677ff',
          cursor: 'pointer',
          fontWeight: 600,
          padding: '0 2px',
        }}
      >
        [{idx}]
      </sup>
    </Popover>
  );
}

/**
 * 模型回答的 Markdown 渲染：
 * <ul>
 *   <li>标准 Markdown + GFM 扩展（表格/删除线/任务列表等），修复 **加粗** 等原样显示的问题；</li>
 *   <li>[1][2] 引用经 rehype 插件渲染为来源角标 Popover；</li>
 *   <li>react-markdown 默认不渲染原始 HTML，天然规避 XSS；</li>
 *   <li>流式输出中未闭合的语法会被容错处理，token 到达后自然补全。</li>
 * </ul>
 * memo：text/sources 引用不变时跳过重解析。
 */
function AnswerText({ text, sources }: Props) {
  return (
    <div className="wa-md">
      <ReactMarkdown
        remarkPlugins={REMARK_PLUGINS}
        rehypePlugins={REHYPE_PLUGINS}
        components={buildComponents(sources)}
      >
        {text}
      </ReactMarkdown>
    </div>
  );
}

export default memo(AnswerText);
