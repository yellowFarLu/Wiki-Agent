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

执行模型（重要，踩过死锁，勿随意改）：
  单一事件循环 + 异步 AsyncOpenAI 驱动 5 项指标；**SemanticSimilarity 单独使用
  同步 OpenAI client 的 embeddings**。原因：ragas 0.4.3 的
  SemanticSimilarity.ascore() 内部错误地调用了同步 embed_text()，若 embeddings
  绑定异步 client，会触发 instructor 的"运行循环内另起线程+新循环"桥接，
  而 AsyncOpenAI/httpx 对象归属主循环 → 主循环 thread.join 等待 → 跨循环死锁，
  整个事件循环停摆（假 key 秒失败反而不易触发）。同步 client 的 embed_text
  直接同步 HTTP，只短暂阻塞循环，不产生桥接线程。
  其余指标（含 AnswerRelevancy）均走 agenerate/aembed 纯异步路径。

黄金集 golden-ragas.jsonl 的 provenance（诚实声明，禁止伪造）：
  问题复用 src/test/resources/eval/retrieve-golden.json 的检索黄金问题及其变体；
  contexts 逐字取自评测种子库 src/test/resources/eval/seed/retrieve-seed.json 的真实 chunk 内容
  （eval-c-*，即离线评测管线播种到知识库的内容）；
  answer / reference 为人工基于 contexts 撰写的忠实回答（黄金答案基线），
  不是线上流量录制。后续接入真实流量样本时，逐条替换并在该头部注明来源。

运行方式：
  pip install -r eval/ragas/requirements.txt
  DASHSCOPE_API_KEY=xxx python3 eval/ragas/run_ragas_eval.py

退出码约定（诚实三原则，与 eval-baseline.md 一致）：
  无 DASHSCOPE_API_KEY      → status=SKIPPED，退出 0（如实跳过，绝不编造分数）
  黄金集缺失/格式错误/无样本 → status=ERROR，退出 1（基础设施问题）
  全部样本评分抛异常         → status=ERROR，退出 1
  部分指标失败               → 该 (样本,指标) 记 null + error，聚合只对有值样本取均值；
                              某指标全体缺失 → value=null（看板显示"-"，不显示 0）
门禁：report-only，阈值待首次 CI 基线后按 eval-baseline.md §4 惯例约定，本脚本不判定通过/失败。
"""
from __future__ import annotations

import asyncio
import json
import os
import sys
import time
from pathlib import Path

GOLDEN_PATH = Path(__file__).resolve().parent / "golden-ragas.jsonl"
REPORT_PATH = Path(os.environ.get(
    "RAGAS_REPORT_PATH",
    Path(__file__).resolve().parents[2] / "target" / "ragas-report" / "ragas-report.json",
))
DASHSCOPE_COMPAT_BASE_URL = os.environ.get(
    "RAGAS_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"
)
JUDGE_MODEL = os.environ.get("RAGAS_JUDGE_MODEL", "qwen-plus")
EMBEDDING_MODEL = os.environ.get("RAGAS_EMBEDDING_MODEL", "text-embedding-v3")
MAX_CONCURRENCY = int(os.environ.get("RAGAS_MAX_CONCURRENCY", "4"))

REQUIRED_FIELDS = ("id", "question", "contexts", "answer", "reference")


def write_report(report: dict) -> None:
    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[ragas] 报告已写入 {REPORT_PATH}", flush=True)


def load_golden() -> list[dict]:
    samples = []
    with GOLDEN_PATH.open(encoding="utf-8") as f:
        for lineno, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            missing = [k for k in REQUIRED_FIELDS if k not in row]
            if missing:
                raise ValueError(f"golden 第 {lineno} 行缺字段 {missing}: {row.get('id', '?')}")
            if not isinstance(row["contexts"], list) or not row["contexts"]:
                raise ValueError(f"golden 第 {lineno} 行 contexts 必须为非空数组: {row['id']}")
            samples.append(row)
    return samples


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
        {"id": s["id"], "domain": s.get("domain"), "question": s["question"],
         "contexts": s["contexts"], "answer": s["answer"], "reference": s["reference"],
         "scores": {key: None for key, _, _ in metric_specs},
         "errors": {}}
        for s in samples
    ]

    semaphore = asyncio.Semaphore(MAX_CONCURRENCY)

    async def score_one(idx: int, key: str, metric, kwargs_fn) -> None:
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

    aggregates = {}
    for key, _, _ in metric_specs:
        values = [r["scores"][key] for r in sample_results if r["scores"][key] is not None]
        aggregates[key] = {
            "value": round(sum(values) / len(values), 4) if values else None,
            "scored": len(values),
            "missing": len(samples) - len(values),
        }
    return {"aggregates": aggregates, "samples": sample_results}


def main() -> int:
    started = time.time()
    base = {
        "tool": {
            "ragas": "0.4.3",
            "judgeModel": JUDGE_MODEL,
            "embeddingModel": EMBEDDING_MODEL,
            "endpoint": DASHSCOPE_COMPAT_BASE_URL,
        },
        "goldenFile": str(GOLDEN_PATH),
        "thresholds": "report-only（待首次 CI 基线后按 docs/operations/eval-baseline.md §4 惯例约定）",
    }

    api_key = os.environ.get("DASHSCOPE_API_KEY", "").strip()
    if not api_key:
        write_report({**base, "status": "SKIPPED",
                      "reason": "DASHSCOPE_API_KEY 未配置，如实跳过，不产出任何编造分数"})
        print("[ragas] SKIPPED: DASHSCOPE_API_KEY 未配置", flush=True)
        return 0

    try:
        samples = load_golden()
        if not samples:
            raise ValueError("golden 集为空")
    except Exception as exc:
        write_report({**base, "status": "ERROR", "reason": f"黄金集加载失败: {exc}"})
        print(f"[ragas] ERROR: 黄金集加载失败: {exc}", flush=True)
        return 1

    print(f"[ragas] 开始评分：{len(samples)} 条样本 × 6 项指标", flush=True)
    try:
        outcome = asyncio.run(score_all(samples))
    except Exception as exc:
        write_report({**base, "status": "ERROR", "reason": f"评分流程异常: {type(exc).__name__}: {exc}"})
        print(f"[ragas] ERROR: {type(exc).__name__}: {exc}", flush=True)
        return 1

    total_scored = sum(a["scored"] for a in outcome["aggregates"].values())
    status = "OK" if total_scored > 0 else "ERROR"
    report = {
        **base,
        "status": status,
        "reason": None if status == "OK" else "全部样本评分均失败（疑 judge/embedding 服务不可用），见 samples[].errors",
        "sampleCount": len(samples),
        "durationSec": round(time.time() - started, 1),
        "metrics": outcome["aggregates"],
        "samples": outcome["samples"],
    }
    write_report(report)
    for key, agg in outcome["aggregates"].items():
        v = agg["value"]
        print(f"[ragas] {key}: {'-' if v is None else v} (scored={agg['scored']}, missing={agg['missing']})",
              flush=True)
    return 0 if status == "OK" else 1


if __name__ == "__main__":
    sys.exit(main())
