#!/usr/bin/env python3
"""RAGAS 评测数据集共享 IO / schema 工具（阶段一/二/三流水线共用）。

数据集三阶段来源与口径见 docs/operations/eval-baseline.md §6.2：
  source=seed-manual  —— 人工基于评测种子库编写的原始基线（2026-10-05 首批 12 条）
  source=synthetic    —— 阶段一：RAGAS TestsetGenerator 基于评测语料合成
  source=production   —— 阶段二：从生产日志（rag_answer_eval / chat_history / kb_feedback）导出
  source=expert       —— 阶段三：领域专家手工补充的边界 case（错别字/模糊指代/知识库越界等）

审核状态：pending（候选，不进基线主聚合）→ approved（专家审核通过，进入 golden 主集）
         / rejected（审核拒绝，留痕于 reviewed-rejected.jsonl，不参与评分）。

诚实铁律：
  - 无 reference 的样本只参与三项无参考指标评分，context_recall / factual_correctness /
    semantic_similarity 记 null 跳过（不计分母），绝不伪造 ground truth；
  - answerOrigin=reference-proxy 的合成样本仅证明接线正确性，近满分不代表管道质量，
    专家审核时应替换为系统真实回答或明确保留并知悉其性质；
  - 生产候选（candidates-production.jsonl）含真实日志，仅写 target/ 目录，严禁入库 git。
"""
from __future__ import annotations

import json
from collections import Counter
from pathlib import Path
from typing import Iterable

# ---- 来源 / 状态 / 难度枚举（字符串常量，跨 Python-JVM-前端三方一致） ----
SOURCE_SEED_MANUAL = "seed-manual"
SOURCE_SYNTHETIC = "synthetic"
SOURCE_PRODUCTION = "production"
SOURCE_EXPERT = "expert"
SOURCES = (SOURCE_SEED_MANUAL, SOURCE_SYNTHETIC, SOURCE_PRODUCTION, SOURCE_EXPERT)

STATUS_PENDING = "pending"
STATUS_APPROVED = "approved"
STATUS_REJECTED = "rejected"

DIFFICULTY_SIMPLE = "simple"
DIFFICULTY_REASONING = "reasoning"
DIFFICULTY_MULTI_HOP = "multi_hop"
DIFFICULTY_BOUNDARY = "boundary"

ANSWER_ORIGIN_MANUAL = "manual"
ANSWER_ORIGIN_PROXY = "reference-proxy"
ANSWER_ORIGIN_PRODUCTION = "production"

# RAGAS 六指标对输入字段的依赖（与 run_ragas_eval.py metric_specs 对齐）
METRICS_NEEDING_REFERENCE = ("context_recall", "factual_correctness", "semantic_similarity")
METRICS_NEEDING_CONTEXTS = ("faithfulness", "context_precision_without_reference",
                            "context_recall")
METRIC_KEYS = ("faithfulness", "answer_relevancy", "context_precision_without_reference",
               "context_recall", "factual_correctness", "semantic_similarity")

GOLDEN_FILE = "golden-ragas.jsonl"
CANDIDATE_FILES = ("candidates-synthetic.jsonl", "candidates-production.jsonl")
REJECTED_LOG_FILE = "reviewed-rejected.jsonl"

# 除 id/question/contexts/answer/reference 外的可选字段及其默认值
_OPTIONAL_DEFAULTS = {
    "domain": None,
    "source": SOURCE_SEED_MANUAL,
    "difficulty": DIFFICULTY_SIMPLE,
    "reviewStatus": STATUS_APPROVED,
    "answerOrigin": ANSWER_ORIGIN_MANUAL,
    "sourceRef": None,
    "tags": list,
    "reviewedBy": None,
    "reviewedAt": None,
    "note": None,
}


def read_jsonl(path: Path) -> list[dict]:
    """读取 jsonl；文件不存在返回空列表。含 _comment 的说明行跳过。"""
    if not path.exists():
        return []
    rows = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            if "_comment" in row:
                continue
            rows.append(row)
    return rows


def write_jsonl(path: Path, rows: Iterable[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")


def normalize_sample(row: dict) -> dict:
    """补齐 schema 默认值，兼容 2026-10-05 首批 12 条无 provenance 字段的旧格式。"""
    sample = {
        "id": row["id"],
        "question": row["question"],
        "contexts": list(row.get("contexts") or []),
        "answer": row.get("answer") or "",
        "reference": row.get("reference") or "",
    }
    for key, default in _OPTIONAL_DEFAULTS.items():
        if key in row and row[key] is not None:
            sample[key] = row[key]
        else:
            sample[key] = default() if callable(default) else default
    return sample


def validate_scoring_input(sample: dict) -> list[str]:
    """评分前校验：question/answer 必填（无 answer 无法评任何指标）。"""
    problems = []
    if not sample.get("question"):
        problems.append("question 为空")
    if not sample.get("answer"):
        problems.append("answer 为空（生产候选须先在审核阶段补齐系统回答）")
    return problems


def validate_for_approve(sample: dict) -> list[str]:
    """专家审核通过前校验：question/answer/contexts 必须齐备；reference 缺失只告警不禁入。"""
    problems = validate_scoring_input(sample)
    if not sample.get("contexts"):
        problems.append("contexts 为空（至少需要一个经审核的上下文 chunk）")
    return problems


def has_reference(sample: dict) -> bool:
    return bool((sample.get("reference") or "").strip())


def has_contexts(sample: dict) -> bool:
    return bool(sample.get("contexts"))


def skipped_metrics(sample: dict) -> dict[str, str]:
    """按字段缺失情况返回应跳过的指标及原因（不调用 LLM，诚实记 null）。"""
    skipped = {}
    if not has_reference(sample):
        for key in METRICS_NEEDING_REFERENCE:
            skipped[key] = "skipped: reference 缺失（无人工 ground truth，不计分母）"
    if not has_contexts(sample):
        for key in METRICS_NEEDING_CONTEXTS:
            skipped.setdefault(key, "skipped: contexts 缺失（无经审核上下文，不计分母）")
    return skipped


def load_dataset_dir(dataset_dir: Path, include_pending: bool = False) -> tuple[list[dict], dict]:
    """加载数据集目录。

    默认只加载 golden-ragas.jsonl（其中样本均应为 approved）；
    include_pending=True 时追加 candidates-synthetic/production 中的非 rejected 候选。
    返回 (规范化后的样本列表, 加载清单 {文件: 行数})。
    """
    manifest = {}
    files = [dataset_dir / GOLDEN_FILE]
    if include_pending:
        files += [dataset_dir / name for name in CANDIDATE_FILES]
    samples = []
    for path in files:
        rows = read_jsonl(path)
        manifest[path.name] = len(rows)
        for row in rows:
            sample = normalize_sample(row)
            if path.name == GOLDEN_FILE:
                if sample["reviewStatus"] != STATUS_REJECTED:
                    samples.append(sample)
            elif include_pending and sample["reviewStatus"] == STATUS_PENDING:
                samples.append(sample)  # 候选文件只有 pending 行才参与预览评分
    return samples, manifest


def provenance_stats(samples: list[dict]) -> dict:
    """按 source / reviewStatus / difficulty 计数（报告与 review CLI 共用）。"""
    return {
        "total": len(samples),
        "bySource": dict(sorted(Counter(s["source"] for s in samples).items())),
        "byReviewStatus": dict(sorted(Counter(s["reviewStatus"] for s in samples).items())),
        "byDifficulty": dict(sorted(Counter(s["difficulty"] for s in samples).items())),
    }


def merge_candidates(existing: list[dict], incoming: list[dict], force: bool = False) -> tuple[list[dict], int, int]:
    """幂等合并候选：同 id 已存在时默认跳过（保护已生成/已编辑行）；
    force=True 时仅当旧行仍为 pending 才覆盖（approved 行任何情况下都不覆盖）。
    返回 (合并后列表, 新增数, 跳过数)。
    """
    merged = list(existing)
    index = {s["id"]: i for i, s in enumerate(merged)}
    added = skipped = 0
    for sample in incoming:
        pos = index.get(sample["id"])
        if pos is None:
            index[sample["id"]] = len(merged)
            merged.append(sample)
            added += 1
        elif force and merged[pos]["reviewStatus"] == STATUS_PENDING:
            merged[pos] = sample
            skipped += 1
        else:
            skipped += 1
    return merged, added, skipped


def load_corpus(path: Path) -> dict[str, list[dict]]:
    """加载阶段一语料 jsonl（每行 {id, domain, content}），按 domain 分组保序。"""
    grouped: dict[str, list[dict]] = {}
    for row in read_jsonl(path):
        for key in ("id", "domain", "content"):
            if not row.get(key):
                raise ValueError(f"语料行缺字段 {key}: {row.get('id', '?')}")
        grouped.setdefault(row["domain"], []).append(row)
    if not grouped:
        raise ValueError(f"语料为空: {path}")
    for domain, chunks in grouped.items():
        if len(chunks) < 2:
            raise ValueError(f"域 {domain} 仅 {len(chunks)} 个 chunk，无法支撑多跳合成，至少需要 2 个")
    return grouped
