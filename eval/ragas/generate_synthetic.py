#!/usr/bin/env python3
"""阶段一：RAGAS 合成评测数据生成（TestsetGenerator，ragas==0.4.3）。

定位（docs/operations/eval-baseline.md §6.2 方式二）：项目早期基于评测语料
（eval/ragas/corpus/synthetic-corpus.jsonl，按真实公文风格编写的虚构语料）
用 LLM 自动、大规模地生成 question-contexts-reference 候选，快速搭建基线规模。

合成产物 candidates-synthetic.jsonl 一律 reviewStatus=pending，**不直接进入
golden 主集、不直接用于基线判定**：合成问题分布与真实用户存在差距，必须经
领域专家审核（阶段三 review_dataset.py approve）后方可进入基线。

难度分布（query_distribution，env RAGAS_SYNTH_DISTRIBUTION 可覆盖）：
  single_hop_specific=0.5  → simple     单 chunk 事实题（天然含错别字/语病噪声风格）
  multi_hop_abstract=0.25  → reasoning  跨 chunk 抽象综合题
  multi_hop_specific=0.25  → multi_hop  跨 chunk 具体事实多跳题

合成样本 answer 字段以 reference 镜像代理（answerOrigin=reference-proxy）：
它只能证明评分接线正确，近满分不代表管道质量；专家审核时应替换为被评系统的
真实回答（阶段二生产样本天然携带真实 answer）后再 approve。

中文约束（2026-10-05 实测）：不显式传 llm_context 时 qwen-plus 经
DashScope 兼容端点会生成英文/拼音混合问题；传入中文业务场景约束后
问题/参考答案均为简体中文，且自然产生口语化、错别字提问。

运行：
  pip install -r eval/ragas/requirements.txt   # 含 rapidfuzz（testset 字符串距离依赖）
  DASHSCOPE_API_KEY=xxx python3 eval/ragas/generate_synthetic.py
  # 每域生成条数（默认 2，6 域共 12 条）：
  RAGAS_SYNTH_SIZE=4 python3 eval/ragas/generate_synthetic.py
  # 重新生成并刷新已有 pending 行（approved 行永不覆盖）：
  python3 eval/ragas/generate_synthetic.py --force

退出码：无 DASHSCOPE_API_KEY → SKIPPED 退出 0（绝不伪造样本）；
语料缺失/格式错误/生成异常 → ERROR 退出 1。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
from pathlib import Path

import dataset_io as dio

HERE = Path(__file__).resolve().parent
CORPUS_PATH = Path(os.environ.get("RAGAS_SYNTH_CORPUS", HERE / "corpus" / "synthetic-corpus.jsonl"))
OUTPUT_PATH = Path(os.environ.get("RAGAS_SYNTH_OUTPUT", HERE / "datasets" / "candidates-synthetic.jsonl"))
REPORT_PATH = Path(os.environ.get(
    "RAGAS_SYNTH_REPORT",
    HERE.parents[1] / "target" / "ragas-report" / "synthetic-report.json",
))
DASHSCOPE_COMPAT_BASE_URL = os.environ.get(
    "RAGAS_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"
)
JUDGE_MODEL = os.environ.get("RAGAS_SYNTH_MODEL", "qwen-plus")
EMBEDDING_MODEL = os.environ.get("RAGAS_EMBEDDING_MODEL", "text-embedding-v3")
PER_DOMAIN_SIZE = int(os.environ.get("RAGAS_SYNTH_SIZE", "2"))
REQUEST_TIMEOUT = float(os.environ.get("RAGAS_SYNTH_TIMEOUT", "180"))
DEFAULT_DISTRIBUTION = os.environ.get(
    "RAGAS_SYNTH_DISTRIBUTION",
    "single_hop_specific:0.5,multi_hop_abstract:0.25,multi_hop_specific:0.25",
)

# 强制中文业务场景（不传时 qwen-plus 实测生成英文/拼音混合问题）
LLM_CONTEXT = os.environ.get(
    "RAGAS_SYNTH_CONTEXT",
    "跨境物流与企业服务内部知识库（语言必须为简体中文）：覆盖工业园区产业准入、"
    "物业缴费与开票、报关商品归类申报、结算对账调账、商家入驻资质审核、车辆轨迹里程核算等"
    "中国企业内部业务流程。生成的用户画像、问题、场景与参考答案一律使用简体中文，"
    "符合中国企业员工真实提问习惯（口语化、可含业务术语缩写、错别字、不规范措辞），"
    "严禁英文、严禁拼音、严禁翻译腔；参考答案只能依据给定资料，不得引入资料外事实。",
)

# ragas synthesizer 名 → 本数据集 difficulty 枚举
_SYNTH_TO_DIFFICULTY = {
    "single_hop_specific_query_synthesizer": dio.DIFFICULTY_SIMPLE,
    "multi_hop_abstract_query_synthesizer": dio.DIFFICULTY_REASONING,
    "multi_hop_specific_query_synthesizer": dio.DIFFICULTY_MULTI_HOP,
}
_NOISE_STYLES = {
    "MISSPELLED": "noise:misspelled",
    "POOR_GRAMMAR": "noise:poor-grammar",
    "PERSONA": "noise:persona-style",
}


# CLI/env 分布短名 → ragas synthesizer（全名仅用于难度映射，来自产物 synthesizer_name）
DISTRIBUTION_NAMES = ("single_hop_specific", "multi_hop_abstract", "multi_hop_specific")


def parse_distribution(spec: str) -> list[tuple[str, float]]:
    """解析 'name:weight,name:weight'；权重和无须等于 1（ragas 内部归一化），但必须为正。"""
    pairs = []
    for part in spec.split(","):
        part = part.strip()
        if not part:
            continue
        if ":" not in part:
            raise ValueError(f"分布项格式错误（应为 name:weight）: {part}")
        name, weight_s = part.split(":", 1)
        name, weight_s = name.strip(), weight_s.strip()
        if name not in DISTRIBUTION_NAMES:
            raise ValueError(f"未知 synthesizer: {name}，可选 {list(DISTRIBUTION_NAMES)}")
        weight = float(weight_s)
        if weight <= 0:
            raise ValueError(f"分布权重必须为正: {name}={weight}")
        pairs.append((name, weight))
    if not pairs:
        raise ValueError("分布为空")
    return pairs


def make_sample_id(domain: str, question: str) -> str:
    digest = hashlib.sha1(question.strip().encode("utf-8")).hexdigest()[:10]
    return f"synth-{domain}-{digest}"


def map_synth_row(row: dict, domain: str) -> dict:
    """把 ragas Testset.to_list() 的单行映射为本数据集 schema。"""
    question = (row.get("user_input") or "").strip()
    contexts = list(row.get("reference_contexts") or row.get("retrieved_contexts") or [])
    reference = (row.get("reference") or "").strip()
    synth_name = row.get("synthesizer_name") or ""
    tags = [f"synthesizer:{synth_name}"] if synth_name else []
    style = row.get("query_style")
    if style in _NOISE_STYLES:
        tags.append(_NOISE_STYLES[style])
    persona = row.get("persona_name")
    if persona:
        tags.append(f"persona:{persona}")
    return {
        "id": make_sample_id(domain, question),
        "domain": domain,
        "question": question,
        "contexts": contexts,
        # reference 代理答案：仅用于接线 sanity，审核时须替换为系统真实回答
        "answer": reference,
        "reference": reference,
        "source": dio.SOURCE_SYNTHETIC,
        "difficulty": _SYNTH_TO_DIFFICULTY.get(synth_name, dio.DIFFICULTY_SIMPLE),
        "reviewStatus": dio.STATUS_PENDING,
        "answerOrigin": dio.ANSWER_ORIGIN_PROXY,
        "sourceRef": synth_name or "ragas-testset-generator",
        "tags": tags,
        "reviewedBy": None,
        "reviewedAt": None,
        "note": "合成候选：answer 为 reference 代理，审核前不具质量基线意义",
    }


def build_generator():
    """构造 ragas TestsetGenerator（import 留在函数内，无 ragas 环境时纯函数仍可单测）。"""
    from openai import AsyncOpenAI
    from ragas.embeddings import OpenAIEmbeddings
    from ragas.llms import llm_factory
    from ragas.testset import TestsetGenerator

    async_client = AsyncOpenAI(
        base_url=DASHSCOPE_COMPAT_BASE_URL,
        api_key=os.environ["DASHSCOPE_API_KEY"],
        timeout=REQUEST_TIMEOUT,
        max_retries=2,
    )
    llm = llm_factory(JUDGE_MODEL, provider="openai", client=async_client)
    embeddings = OpenAIEmbeddings(client=async_client, model=EMBEDDING_MODEL)
    return TestsetGenerator(llm=llm, embedding_model=embeddings, llm_context=LLM_CONTEXT), llm


def build_synthesizers(llm, distribution: list[tuple[str, float]]):
    from ragas.testset.synthesizers import (
        MultiHopAbstractQuerySynthesizer,
        MultiHopSpecificQuerySynthesizer,
        SingleHopSpecificQuerySynthesizer,
    )
    cls = {
        "single_hop_specific": SingleHopSpecificQuerySynthesizer,
        "multi_hop_abstract": MultiHopAbstractQuerySynthesizer,
        "multi_hop_specific": MultiHopSpecificQuerySynthesizer,
    }
    return [(cls[name](llm=llm, llm_context=LLM_CONTEXT), weight) for name, weight in distribution]


def generate_domain(generator, llm, chunks: list[str], size: int,
                    distribution: list[tuple[str, float]]) -> tuple[list[dict], str]:
    """对单个业务域执行一次合成（同步入口内部走纯异步路径，无 instructor 桥接死锁）。

    返回 (ragas 原始行, 实际使用的分布短名)。多跳 synthesizer 依赖知识图中的
    节点 cluster；当语料关联稀疏（"No clusters found in the knowledge graph"）时，
    如实降级为 single_hop:1.0 重试一次——该域只产 simple 题，难度分布在映射后自然体现。
    """
    from ragas.run_config import RunConfig

    def run(dist: list[tuple[str, float]]):
        return generator.generate_with_chunks(
            chunks=chunks,
            testset_size=size,
            query_distribution=build_synthesizers(llm, dist),
            run_config=RunConfig(timeout=REQUEST_TIMEOUT, max_retries=2),
        ).to_list()

    try:
        return run(distribution), ",".join(n for n, _ in distribution)
    except ValueError as exc:
        # 多跳需要知识图中至少一个多节点 cluster；语料关联稀疏或 LLM 关系抽取波动时
        # 可能抛 "No clusters found ..." / "No relationships match ... Cannot form clusters."
        if "cluster" not in str(exc).lower():
            raise
        print(f"[synth] 警告: 该域知识图无多跳 cluster，降级为 single_hop:1.0: {exc}",
              flush=True)
        return run([("single_hop_specific", 1.0)]), "single_hop_specific(fallback)"


def write_report(report: dict) -> None:
    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[synth] 报告已写入 {REPORT_PATH}", flush=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="RAGAS 合成评测候选生成（阶段一）")
    parser.add_argument("--size", type=int, default=PER_DOMAIN_SIZE, help="每个业务域生成条数")
    parser.add_argument("--force", action="store_true", help="刷新同 id 的已有 pending 行")
    parser.add_argument("--domains", default="", help="仅生成指定域，逗号分隔（调试用）")
    args = parser.parse_args(argv)

    started = time.time()
    base = {
        "tool": "ragas-testset-generator/ragas==0.4.3",
        "model": JUDGE_MODEL,
        "embeddingModel": EMBEDDING_MODEL,
        "endpoint": DASHSCOPE_COMPAT_BASE_URL,
        "corpus": str(CORPUS_PATH),
        "output": str(OUTPUT_PATH),
        "perDomainSize": args.size,
    }

    if not os.environ.get("DASHSCOPE_API_KEY", "").strip():
        write_report({**base, "status": "SKIPPED",
                      "reason": "DASHSCOPE_API_KEY 未配置，如实跳过，不产出任何合成样本"})
        print("[synth] SKIPPED: DASHSCOPE_API_KEY 未配置", flush=True)
        return 0

    try:
        distribution = parse_distribution(DEFAULT_DISTRIBUTION)
        corpus = dio.load_corpus(CORPUS_PATH)
    except Exception as exc:
        write_report({**base, "status": "ERROR", "reason": f"配置/语料加载失败: {exc}"})
        print(f"[synth] ERROR: {exc}", flush=True)
        return 1

    domains = [d.strip() for d in args.domains.split(",") if d.strip()] or list(corpus)
    unknown = [d for d in domains if d not in corpus]
    if unknown:
        write_report({**base, "status": "ERROR", "reason": f"语料中不存在的域: {unknown}"})
        print(f"[synth] ERROR: 域不存在 {unknown}", flush=True)
        return 1

    existing = dio.read_jsonl(OUTPUT_PATH)
    per_domain_counts: dict[str, int] = {}
    failed_domains: dict[str, str] = {}
    total_generated = total_added = total_skipped = 0
    try:
        for domain in domains:
            # 每域独立 generator：TestsetGenerator 内部持有 KnowledgeGraph 实例，
            # 跨域复用会让上一域的图污染下一域的 cluster 构建（实测导致多跳全部 miss）
            generator, llm = build_generator()
            chunks = [c["content"] for c in corpus[domain]]
            print(f"[synth] 域 {domain}: {len(chunks)} chunks × {args.size} 条 ...", flush=True)
            try:
                rows, used_distribution = generate_domain(generator, llm, chunks,
                                                          args.size, distribution)
            except Exception as exc:
                # 单域失败隔离：不拖垮其它域，已完成域的成果增量落盘保留
                failed_domains[domain] = f"{type(exc).__name__}: {str(exc)[:200]}"
                print(f"[synth] 域 {domain} 失败（跳过，继续其它域）: {failed_domains[domain]}",
                      flush=True)
                continue
            mapped = [map_synth_row(r, domain) for r in rows]
            if "fallback" in used_distribution:
                for s in mapped:
                    s.setdefault("tags", []).append("distribution:single-hop-fallback")
            # 同域同批去重（极小概率）
            seen, deduped = set(), []
            for s in mapped:
                if s["id"] not in seen and s["question"]:
                    seen.add(s["id"])
                    deduped.append(s)
            # 增量落盘：后续域失败/中断不丢已完成成果
            existing, added, skipped = dio.merge_candidates(existing, deduped, force=args.force)
            dio.write_jsonl(OUTPUT_PATH, existing)
            per_domain_counts[domain] = len(deduped)
            total_generated += len(deduped)
            total_added += added
            total_skipped += skipped
            print(f"[synth] 域 {domain} 完成: {len(deduped)} 条（新增 {added}）", flush=True)
    except Exception as exc:
        # generator 构建级致命错误（鉴权/网络）：无任何落盘价值时才走此分支
        write_report({**base, "status": "ERROR",
                      "reason": f"合成流程致命异常: {type(exc).__name__}: {exc}",
                      "perDomain": per_domain_counts, "failedDomains": failed_domains,
                      "generatedBeforeError": total_generated,
                      "durationSec": round(time.time() - started, 1)})
        print(f"[synth] ERROR: {type(exc).__name__}: {exc}", flush=True)
        return 1

    all_failed = len(per_domain_counts) == 0
    report = {
        **base,
        "status": "ERROR" if all_failed else "OK",
        "reason": (f"全部 {len(domains)} 个域合成失败" if all_failed else None),
        "distribution": dict((name, weight) for name, weight in distribution),
        "perDomain": per_domain_counts,
        "failedDomains": failed_domains,
        "generated": total_generated,
        "added": total_added,
        "skippedExisting": total_skipped,
        "candidateTotal": len(existing),
        "durationSec": round(time.time() - started, 1),
        "nextStep": "全部候选为 pending；专家审核：python3 eval/ragas/review_dataset.py approve <id> --by <审核人>",
    }
    write_report(report)
    if all_failed:
        print("[synth] ERROR: 无任何域成功", flush=True)
        return 1
    print(f"[synth] OK: 新增 {total_added} / 跳过已存在 {total_skipped} / "
          f"候选总计 {len(existing)} / 失败域 {len(failed_domains)}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
