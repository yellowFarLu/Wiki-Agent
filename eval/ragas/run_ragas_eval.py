#!/usr/bin/env python3
"""RAGAS 离线评测脚本（看板 JVM 编排 / CI 夜间 job，两种方式共用）。

方案与口径见 docs/operations/eval-baseline.md §6 与 docs/rag-accuracy-eval.md §2。

指标族（ragas==0.4.3，collections API，与业界 RAGAS 定义一致）：
  - faithfulness                        忠实度（忠诚度）：回答论断能否被 contexts 支撑（无需 reference）
  - answer_relevancy                    答案相关性：由回答反推问题与原问题的余弦相似度（需 LLM+embedding）
  - context_precision_without_reference 上下文精确率无参考变体（用 response 替代 reference）
  - context_recall                      上下文召回率：reference 论断被 contexts 支撑的比例（**必须 reference**）
  - factual_correctness                 事实正确性：response vs reference 论断重叠 F1（必须 reference）
  - semantic_similarity                 语义相似度：response vs reference 向量余弦（必须 reference）

评估数据集三阶段流水线（docs/operations/eval-baseline.md §6.2）：
  默认只评 datasets/golden-ragas.jsonl 中 reviewStatus=approved 的样本；
  RAGAS_INCLUDE_PENDING=true（或 --include-pending）时连 candidates-synthetic/
  production.jsonl 的 pending 候选一起"预览评分"（仅供专家审核参考，不进主聚合）。
  报告按 source（seed-manual/synthetic/production/expert）分组给出 provenance 聚合。
  缺 reference 的样本自动跳过三项参考类指标，缺 contexts 跳过上下文类指标，
  一律 null 不计分母（绝不伪造 ground truth）。

执行模型（重要，踩过死锁，勿随意改）：
  单一事件循环 + 异步 AsyncOpenAI 驱动 5 项指标；**SemanticSimilarity 单独使用
  同步 OpenAI client 的 embeddings**。原因：ragas 0.4.3 的
  SemanticSimilarity.ascore() 内部错误地调用了同步 embed_text()，若 embeddings
  绑定异步 client，会触发 instructor 的"运行循环内另起线程+新循环"桥接，
  而 AsyncOpenAI/httpx 对象归属主循环 → 主循环 thread.join 等待 → 跨循环死锁，
  整个事件循环停摆（假 key 秒失败反而不易触发）。同步 client 的 embed_text
  直接同步 HTTP，只短暂阻塞循环，不产生桥接线程。
  其余指标（含 AnswerRelevancy）均走 agenerate/aembed 纯异步路径。

运行方式：
  pip install -r eval/ragas/requirements.txt
  DASHSCOPE_API_KEY=xxx python3 eval/ragas/run_ragas_eval.py
  DASHSCOPE_API_KEY=xxx python3 eval/ragas/run_ragas_eval.py --include-pending

退出码约定（诚实三原则，与 eval-baseline.md 一致）：
  无 DASHSCOPE_API_KEY      → status=SKIPPED，退出 0（如实跳过，绝不编造分数）
  数据集缺失/格式错误/无样本 → status=ERROR，退出 1（基础设施问题）
  全部样本评分抛异常         → status=ERROR，退出 1
  部分指标失败               → 该 (样本,指标) 记 null + error，聚合只对有值样本取均值；
                              某指标全体缺失 → value=null（看板显示"-"，不显示 0）
门禁：report-only，阈值待真实流量样本基线后按 eval-baseline.md §4 惯例约定，本脚本不判定通过/失败。
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
import time
from pathlib import Path

import dataset_io as dio

HERE = Path(__file__).resolve().parent
DATASET_DIR = Path(os.environ.get("RAGAS_DATASET_DIR", HERE / "datasets"))
# 兼容旧的显式 golden 文件配置（指向具体文件时按文件模式加载）
LEGACY_GOLDEN_FILE = os.environ.get("RAGAS_GOLDEN_PATH", "").strip()
REPORT_PATH = Path(os.environ.get(
    "RAGAS_REPORT_PATH",
    HERE.parents[1] / "target" / "ragas-report" / "ragas-report.json",
))
DASHSCOPE_COMPAT_BASE_URL = os.environ.get(
    "RAGAS_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"
)
JUDGE_MODEL = os.environ.get("RAGAS_JUDGE_MODEL", "qwen-plus")
EMBEDDING_MODEL = os.environ.get("RAGAS_EMBEDDING_MODEL", "text-embedding-v3")
MAX_CONCURRENCY = int(os.environ.get("RAGAS_MAX_CONCURRENCY", "4"))


def write_report(report: dict) -> None:
    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[ragas] 报告已写入 {REPORT_PATH}", flush=True)


def load_samples(include_pending: bool) -> tuple[list[dict], dict]:
    """加载评分样本。显式 RAGAS_GOLDEN_PATH 走文件模式（兼容旧配置/CI 自定义路径）。"""
    if LEGACY_GOLDEN_FILE:
        path = Path(LEGACY_GOLDEN_FILE)
        rows = dio.read_jsonl(path)
        samples = [dio.normalize_sample(r) for r in rows
                   if dio.normalize_sample(r)["reviewStatus"] != dio.STATUS_REJECTED]
        return samples, {"mode": "file", "files": {path.name: len(rows)},
                         "includePending": False}
    samples, files = dio.load_dataset_dir(DATASET_DIR, include_pending=include_pending)
    return samples, {"mode": "dataset-dir", "dir": str(DATASET_DIR),
                     "files": files, "includePending": include_pending}


def aggregate(samples: list[dict], metric_specs, sample_results: list[dict]) -> dict:
    """对给定样本集合做六指标聚合；只对有值样本取均值，全缺 → null。"""
    idx = {r["id"]: r for r in sample_results}
    out = {}
    for key, _, _ in metric_specs:
        values = [idx[s["id"]]["scores"][key] for s in samples
                  if idx[s["id"]]["scores"][key] is not None]
        out[key] = {
            "value": round(sum(values) / len(values), 4) if values else None,
            "scored": len(values),
            "missing": len(samples) - len(values),
        }
    return out


def aggregate_by_source(samples: list[dict], metric_specs,
                        sample_results: list[dict]) -> dict:
    idx = {r["id"]: r for r in sample_results}
    result = {}
    for source in dio.SOURCES:
        group = [s for s in samples if s["source"] == source]
        if not group:
            continue
        metrics = {}
        for key, _, _ in metric_specs:
            values = [idx[s["id"]]["scores"][key] for s in group
                      if idx[s["id"]]["scores"][key] is not None]
            metrics[key] = {
                "value": round(sum(values) / len(values), 4) if values else None,
                "scored": len(values),
                "missing": len(group) - len(values),
            }
        result[source] = {"sampleCount": len(group), "metrics": metrics}
    return result


def build_aggregates(samples: list[dict], metric_specs,
                     sample_results: list[dict]) -> dict:
    """按审核状态组装三套聚合（纯函数）：
    - aggregates / aggregatesBySource：approved 主聚合（基线唯一口径）；
    - aggregatesReviewPending：pending 预览，含 all 总计与 bySource 分组，
      结构与主聚合相似但永不参与基线。无 pending → None。
    """
    approved = [s for s in samples if s["reviewStatus"] == dio.STATUS_APPROVED]
    pending = [s for s in samples if s["reviewStatus"] == dio.STATUS_PENDING]
    return {
        "aggregates": aggregate(approved, metric_specs, sample_results),
        "aggregatesBySource": aggregate_by_source(approved, metric_specs, sample_results),
        "aggregatesReviewPending": (
            {"all": aggregate(pending, metric_specs, sample_results),
             "bySource": aggregate_by_source(pending, metric_specs, sample_results)}
            if pending else None),
    }


async def score_all(samples: list[dict]) -> dict:
    from openai import AsyncOpenAI, OpenAI
    from ragas.embeddings import OpenAIEmbeddings
    from ragas.llms import llm_factory
    from ragas.metrics.collections import (
        AnswerRelevancy,
        ContextPrecisionWithoutReference,
        ContextRecall,
        FactualCorrectness,
        Faithfulness,
        SemanticSimilarity,
    )

    async_client = AsyncOpenAI(
        base_url=DASHSCOPE_COMPAT_BASE_URL,
        api_key=os.environ["DASHSCOPE_API_KEY"],
        timeout=120.0,
        max_retries=2,
    )
    # 仅供 SemanticSimilarity 使用（规避其 ascore 内同步 embed_text 的桥接死锁）
    sync_client = OpenAI(
        base_url=DASHSCOPE_COMPAT_BASE_URL,
        api_key=os.environ["DASHSCOPE_API_KEY"],
        timeout=120.0,
        max_retries=2,
    )
    llm = llm_factory(JUDGE_MODEL, provider="openai", client=async_client)
    async_embeddings = OpenAIEmbeddings(client=async_client, model=EMBEDDING_MODEL)
    sync_embeddings = OpenAIEmbeddings(client=sync_client, model=EMBEDDING_MODEL)

    # (报告指标键, 指标实例, ascore kwargs 构造函数)；kwargs 与 0.4.3 ascore 签名对齐
    metric_specs = [
        ("faithfulness", Faithfulness(llm=llm),
         lambda s: dict(user_input=s["question"], response=s["answer"],
                        retrieved_contexts=s["contexts"])),
        ("answer_relevancy", AnswerRelevancy(llm=llm, embeddings=async_embeddings),
         lambda s: dict(user_input=s["question"], response=s["answer"])),
        ("context_precision_without_reference", ContextPrecisionWithoutReference(llm=llm),
         lambda s: dict(user_input=s["question"], response=s["answer"],
                        retrieved_contexts=s["contexts"])),
        ("context_recall", ContextRecall(llm=llm),
         lambda s: dict(user_input=s["question"], retrieved_contexts=s["contexts"],
                        reference=s["reference"])),
        ("factual_correctness", FactualCorrectness(llm=llm),
         lambda s: dict(response=s["answer"], reference=s["reference"])),
        ("semantic_similarity", SemanticSimilarity(embeddings=sync_embeddings),
         lambda s: dict(reference=s["reference"], response=s["answer"])),
    ]

    sample_results = [
        {"id": s["id"], "domain": s.get("domain"),
         "source": s["source"], "difficulty": s["difficulty"],
         "reviewStatus": s["reviewStatus"], "answerOrigin": s["answerOrigin"],
         "tags": s.get("tags", []), "question": s["question"],
         "contexts": s["contexts"], "answer": s["answer"], "reference": s["reference"],
         "scores": {key: None for key, _, _ in metric_specs},
         "errors": dict(dio.skipped_metrics(s))}
        for s in samples
    ]

    semaphore = asyncio.Semaphore(MAX_CONCURRENCY)

    async def score_one(idx: int, key: str, metric, kwargs_fn) -> None:
        # 字段缺失的指标已在 errors 中登记跳过原因，不调用 LLM
        if key in sample_results[idx]["errors"]:
            return
        async with semaphore:
            try:
                result = await metric.ascore(**kwargs_fn(samples[idx]))
                sample_results[idx]["scores"][key] = round(float(result.value), 4)
            except Exception as exc:  # 单(样本,指标)失败不拖垮整批，如实记 null+error
                sample_results[idx]["scores"][key] = None
                sample_results[idx]["errors"][key] = f"{type(exc).__name__}: {str(exc)[:300]}"

    tasks = [
        score_one(i, key, metric, kwargs_fn)
        for i in range(len(samples))
        for key, metric, kwargs_fn in metric_specs
    ]
    await asyncio.gather(*tasks)

    pending = [s for s in samples if s["reviewStatus"] == dio.STATUS_PENDING]
    grouped = build_aggregates(samples, metric_specs, sample_results)
    return {
        "metricSpecs": metric_specs,
        **grouped,
        "pendingCount": len(pending),
        "samples": sample_results,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="RAGAS 六指标离线评测")
    parser.add_argument("--include-pending", action="store_true",
                        default=os.environ.get("RAGAS_INCLUDE_PENDING", "").lower() == "true",
                        help="连 pending 候选一起预览评分（不进 approved 主聚合）")
    args = parser.parse_args(argv)

    started = time.time()
    base = {
        "tool": {"ragas": "0.4.3", "judgeModel": JUDGE_MODEL,
                 "embeddingModel": EMBEDDING_MODEL, "endpoint": DASHSCOPE_COMPAT_BASE_URL},
        "thresholds": "report-only（待真实流量样本基线后按 docs/operations/eval-baseline.md §4 约定）",
    }

    api_key = os.environ.get("DASHSCOPE_API_KEY", "").strip()
    if not api_key:
        write_report({**base, "status": "SKIPPED",
                      "reason": "DASHSCOPE_API_KEY 未配置，如实跳过，不产出任何编造分数"})
        print("[ragas] SKIPPED: DASHSCOPE_API_KEY 未配置", flush=True)
        return 0

    try:
        samples, dataset_info = load_samples(args.include_pending)
        if not samples:
            raise ValueError("没有可评分样本（golden 集为空且未 --include-pending）")
        invalid = [(s["id"], p) for s in samples
                   for p in dio.validate_scoring_input(s)]
        if invalid:
            raise ValueError(f"样本缺少评分硬前提字段: {invalid[:5]}")
    except Exception as exc:
        write_report({**base, "status": "ERROR", "reason": f"数据集加载失败: {exc}"})
        print(f"[ragas] ERROR: 数据集加载失败: {exc}", flush=True)
        return 1

    approved = [s for s in samples if s["reviewStatus"] == dio.STATUS_APPROVED]
    dataset_block = {**dataset_info, **dio.provenance_stats(samples),
                     "scoredScope": "approved",
                     "approvedCount": len(approved),
                     "note": ("主聚合仅含 approved；pending 预览见 metricsReviewPending"
                              if args.include_pending else "仅评 approved（加 --include-pending 可预览候选）")}
    print(f"[ragas] 开始评分：{len(approved)} approved"
          f"{f' + {len(samples) - len(approved)} pending 预览' if args.include_pending else ''}"
          f" × 6 项指标", flush=True)
    try:
        outcome = asyncio.run(score_all(samples))
    except Exception as exc:
        write_report({**base, "status": "ERROR", "dataset": dataset_block,
                      "reason": f"评分流程异常: {type(exc).__name__}: {exc}"})
        print(f"[ragas] ERROR: {type(exc).__name__}: {exc}", flush=True)
        return 1

    metric_specs = outcome.pop("metricSpecs")
    total_scored = sum(a["scored"] for a in outcome["aggregates"].values())
    status = "OK" if total_scored > 0 else "ERROR"
    report = {
        **base,
        "status": status,
        "reason": None if status == "OK" else "approved 样本全部指标评分失败（疑 judge/embedding 不可用），见 samples[].errors",
        "dataset": dataset_block,
        "sampleCount": len(samples),
        "durationSec": round(time.time() - started, 1),
        "metrics": outcome["aggregates"],
        "metricsBySource": outcome["aggregatesBySource"],
        "metricsReviewPending": outcome["aggregatesReviewPending"],
        "samples": outcome["samples"],
    }
    write_report(report)
    print(f"[ragas] approved 主聚合（{len(approved)} 条）:", flush=True)
    for key, agg in outcome["aggregates"].items():
        v = agg["value"]
        print(f"[ragas] {key}: {'-' if v is None else v} (scored={agg['scored']}, missing={agg['missing']})",
              flush=True)
    return 0 if status == "OK" else 1


if __name__ == "__main__":
    sys.exit(main())
