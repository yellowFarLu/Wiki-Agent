#!/usr/bin/env python3
"""阶段三：领域专家审核工作流 CLI（评测数据集是持续更新的活文档）。

数据集布局（默认 eval/ragas/datasets/，可用 --dataset-dir 覆盖）：
  golden-ragas.jsonl            approved 主基线（评分默认只评此文件）
  candidates-synthetic.jsonl    阶段一合成候选（pending）
  candidates-production.jsonl   阶段二生产日志候选（pending，通常由 JVM 写到 target/ 后放入）
  reviewed-rejected.jsonl       reject 留痕（永不参与评分）

子命令：
  stats                                        数据集来源/状态/难度分布总览
  list [--status pending|approved|rejected] [--source X]
  show <id> [--json]                           查看单条样本全文（任意文件，审核前必看）
  edit <id> --by <审核人> [字段选项]           补标/修订 pending 候选（见下方字段选项）
  add  --by <审核人> --question ... --answer ... --context ... [--reference ...]
                                               专家直接录入边界 case → golden（source=expert）
  approve <id> --by <审核人> [--note ...]      候选 → golden（校验 question/answer/contexts）
  reject  <id> --by <审核人> [--note ...]      候选/golden → reviewed-rejected.jsonl 留痕
                                               （对 golden 行执行即把错误样本从基线下线）

edit 字段选项（只作用于 pending 候选；每次保存后打印距 approve 的剩余检查项）：
  --question Q            修订问题措辞（归一化去重不会自动重跑，注意避免与他条重复）
  --answer A              替换回答；合成候选替换代理答案后 answerOrigin 自动改 manual
  --reference R           补 ground truth（传空串可显式清空：三项参考指标将跳过）
  --context C             追加经审核上下文 chunk，可重复传多次
  --clear-contexts        先清空原有 contexts 再追加本次 --context
  --difficulty D          simple|reasoning|multi_hop|boundary
  --tag T                 追加标签，可重复（如 boundary:kb-out-of-scope）
  --note N                审核备注

approve 规则（诚实铁律）：
  - question/answer/contexts 缺失则拒绝入库（answer/contexts 是 RAGAS 评分硬前提）；
  - reference 缺失只告警不禁入：该样本将只参与三项无参考指标，
    context_recall/factual_correctness/semantic_similarity 记 null（不计分母）；
  - source=synthetic 且 answerOrigin=reference-proxy 时必须显式确认（--keep-proxy），
    提醒其 answer 是参考答案镜像、只证明接线，专家应先替换为系统真实回答。

典型工作流（阶段三）：
  # 1) 总览 → 2) 列待审 → 3) 看全文 → 4) 补标/替换答案 → 5) 通过或驳回
  python3 eval/ragas/review_dataset.py stats
  python3 eval/ragas/review_dataset.py list --status pending
  python3 eval/ragas/review_dataset.py show prod-rae-7
  python3 eval/ragas/review_dataset.py edit prod-rae-7 --by 张老师 \
      --context "经审核的关键 chunk 文本" --reference "标准答案要点"
  python3 eval/ragas/review_dataset.py approve prod-rae-7 --by 张老师

  # 专家直接录入边界 case（无需先造候选文件）
  python3 eval/ragas/review_dataset.py add --by 张老师 --domain customs \
      --difficulty boundary --tag boundary:kb-out-of-scope \
      --question "我家猫走丢了能查海关宠物入境记录找猫吗？" \
      --answer "海关宠物入境记录仅限检疫目的，不提供走失寻宠查询……" \
      --reference "海关宠物入境检疫记录仅限检疫目的查询……" \
      --context "携带宠物入境须申报并接受检疫，档案不用于个人寻宠。"
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import dataset_io as dio

HERE = Path(__file__).resolve().parent
DEFAULT_DATASET_DIR = HERE / "datasets"


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def load_all(dataset_dir: Path) -> dict[str, list[dict]]:
    files = [dio.GOLDEN_FILE, *dio.CANDIDATE_FILES, dio.REJECTED_LOG_FILE]
    return {name: [dio.normalize_sample(r) for r in dio.read_jsonl(dataset_dir / name)]
            for name in files}


def find_candidate(buckets: dict[str, list[dict]], sample_id: str) -> tuple[str, dict] | None:
    for name in dio.CANDIDATE_FILES:
        for sample in buckets[name]:
            if sample["id"] == sample_id:
                return name, sample
    return None


def find_any(buckets: dict[str, list[dict]], sample_id: str) -> tuple[str, dict] | None:
    """在候选 + golden + rejected 全部文件中查找（show 命令用）。"""
    for name in [*dio.CANDIDATE_FILES, dio.GOLDEN_FILE, dio.REJECTED_LOG_FILE]:
        for sample in buckets[name]:
            if sample["id"] == sample_id:
                return name, sample
    return None


def print_sample_full(bucket_name: str, sample: dict) -> None:
    print(f"文件: {bucket_name}")
    print(f"id: {sample['id']}")
    print(f"source: {sample['source']}    difficulty: {sample['difficulty']}    "
          f"reviewStatus: {sample['reviewStatus']}    answerOrigin: {sample['answerOrigin']}")
    if sample.get("domain"):
        print(f"domain: {sample['domain']}")
    if sample.get("sourceRef"):
        print(f"sourceRef: {sample['sourceRef']}")
    if sample.get("tags"):
        print(f"tags: {', '.join(sample['tags'])}")
    if sample.get("reviewedBy"):
        print(f"reviewedBy: {sample['reviewedBy']}    reviewedAt: {sample.get('reviewedAt')}")
    if sample.get("note"):
        print(f"note: {sample['note']}")
    print(f"\n【question】\n{sample['question']}")
    print(f"\n【answer】\n{sample['answer'] or '（空）'}")
    print(f"\n【reference】\n{sample['reference'] or '（空：三项参考指标将跳过 null 不计分母）'}")
    print(f"\n【contexts】共 {len(sample['contexts'])} 条")
    for i, ctx in enumerate(sample["contexts"], 1):
        print(f"  [{i}] {ctx}")


def cmd_show(args) -> int:
    buckets = load_all(args.dataset_dir)
    found = find_any(buckets, args.id)
    if found is None:
        print(f"[review] 未在任何数据集文件中找到 id={args.id}", file=sys.stderr)
        return 1
    bucket_name, sample = found
    if args.json:
        print(json.dumps(sample, ensure_ascii=False, indent=2))
    else:
        print_sample_full(bucket_name, sample)
    return 0


def _approve_checklist(sample: dict) -> None:
    """保存后/审批前统一提示：阻断项 + reference 缺失警告 + proxy 警告。"""
    problems = dio.validate_for_approve(sample)
    if problems:
        print("[review] 距 approve 还差：", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
    else:
        print("[review] question/answer/contexts 已齐备，可执行 approve")
    if not dio.has_reference(sample):
        print("[review] 提醒：reference 缺失，approve 后 context_recall/factual_correctness/"
              "semantic_similarity 将跳过（null 不计分母）")
    if sample["source"] == dio.SOURCE_SYNTHETIC and \
            sample["answerOrigin"] == dio.ANSWER_ORIGIN_PROXY:
        print("[review] 提醒：answer 仍是 reference 代理；approve 需 --keep-proxy，"
              "或先用 edit --answer 替换为系统真实回答")


def cmd_edit(args) -> int:
    buckets = load_all(args.dataset_dir)
    found = find_candidate(buckets, args.id)
    if found is None:
        if any(s["id"] == args.id for s in buckets[dio.GOLDEN_FILE]):
            print(f"[review] {args.id} 已在 golden 基线中；已入基线样本不允许 edit，"
                  f"如需修订请先 reject 该 id 下线留痕，修正后用 add（expert）或"
                  f"重新生成/导出候选再走审核", file=sys.stderr)
        else:
            print(f"[review] 未在 pending 候选文件中找到 id={args.id}", file=sys.stderr)
        return 1
    bucket_name, sample = found

    changed = []
    if args.question is not None:
        sample["question"] = args.question.strip()
        changed.append("question")
    if args.answer is not None:
        sample["answer"] = args.answer.strip()
        # 专家替换了回答 → 不再是参考镜像；来源仍为 synthetic，但 answerOrigin 归 manual
        if sample["answerOrigin"] == dio.ANSWER_ORIGIN_PROXY:
            sample["answerOrigin"] = dio.ANSWER_ORIGIN_MANUAL
            changed.append("answer(answerOrigin: reference-proxy→manual)")
        else:
            changed.append("answer")
    if args.reference is not None:
        sample["reference"] = args.reference.strip()
        changed.append("reference")
    if args.clear_contexts:
        sample["contexts"] = []
    if args.context:
        for ctx in args.context:
            ctx = ctx.strip()
            if ctx and ctx not in sample["contexts"]:
                sample["contexts"].append(ctx)
        changed.append("contexts")
    if args.difficulty is not None:
        sample["difficulty"] = args.difficulty
        changed.append("difficulty")
    if args.tag:
        for tag in args.tag:
            tag = tag.strip()
            if tag and tag not in sample["tags"]:
                sample["tags"].append(tag)
        changed.append("tags")
    if args.note is not None:
        sample["note"] = args.note
        changed.append("note")

    if not changed:
        print("[review] 未指定任何修订字段（见 edit --help）", file=sys.stderr)
        return 1
    # 编辑留痕：记录最后一次补标人/时间（approve 时还会再盖审核戳）
    sample["reviewedBy"] = args.by
    sample["reviewedAt"] = utc_now_iso()

    rows = buckets[bucket_name]
    dio.write_jsonl(args.dataset_dir / bucket_name, rows)
    print(f"[review] 已保存 {args.id}（{bucket_name}）修订字段: {', '.join(changed)}")
    _approve_checklist(sample)
    return 0


def cmd_add(args) -> int:
    buckets = load_all(args.dataset_dir)
    sample_id = args.id or "expert-" + hashlib.sha1(
        args.question.encode("utf-8")).hexdigest()[:10]
    if any(s["id"] == sample_id for s in buckets[dio.GOLDEN_FILE]):
        print(f"[review] golden 已存在 id={sample_id}，拒绝重复录入", file=sys.stderr)
        return 1

    contexts = []
    for ctx in args.context or []:
        ctx = ctx.strip()
        if ctx and ctx not in contexts:
            contexts.append(ctx)
    tags = []
    for tag in args.tag or []:
        tag = tag.strip()
        if tag and tag not in tags:
            tags.append(tag)

    sample = dio.normalize_sample({
        "id": sample_id,
        "question": args.question.strip(),
        "contexts": contexts,
        "answer": args.answer.strip(),
        "reference": (args.reference or "").strip(),
        "domain": args.domain,
        "source": dio.SOURCE_EXPERT,
        "difficulty": args.difficulty,
        "reviewStatus": dio.STATUS_APPROVED,
        "answerOrigin": dio.ANSWER_ORIGIN_MANUAL,
        "tags": tags,
        "reviewedBy": args.by,
        "reviewedAt": utc_now_iso(),
        "note": args.note,
    })
    problems = dio.validate_for_approve(sample)
    if problems:
        print("[review] 边界 case 校验失败，未写入：", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 1

    golden = buckets[dio.GOLDEN_FILE]
    golden.append(sample)
    dio.write_jsonl(args.dataset_dir / dio.GOLDEN_FILE, golden)
    print(f"[review] 已录入专家边界 case: {sample_id}（expert/{sample['difficulty']}，"
          f"{len(contexts)} 条 contexts）→ {dio.GOLDEN_FILE}")
    if not dio.has_reference(sample):
        print("[review] 提醒：未提供 reference，三项参考指标将跳过（null 不计分母）")
    return 0


def cmd_stats(args) -> int:
    buckets = load_all(args.dataset_dir)
    print(f"数据集目录: {args.dataset_dir}\n")
    grand: list[dict] = []
    for name, rows in buckets.items():
        print(f"  {name}: {len(rows)} 条")
        grand.extend(rows)
    print()
    stats = dio.provenance_stats(grand)
    print(f"  总计: {stats['total']}")
    print(f"  按来源 source: {stats['bySource']}")
    print(f"  按状态 reviewStatus: {stats['byReviewStatus']}")
    print(f"  按难度 difficulty: {stats['byDifficulty']}")
    pending = [s for s in grand if s["reviewStatus"] == dio.STATUS_PENDING]
    no_reference = [s for s in grand if s["reviewStatus"] == dio.STATUS_APPROVED
                    and not dio.has_reference(s)]
    if pending:
        print(f"\n  待审核 pending: {len(pending)} 条（不进基线主聚合）")
    if no_reference:
        print(f"  提醒: {len(no_reference)} 条 approved 样本缺 reference，三项参考类指标将跳过")
    return 0


def cmd_list(args) -> int:
    buckets = load_all(args.dataset_dir)
    shown = 0
    for name, rows in buckets.items():
        for sample in rows:
            if args.status and sample["reviewStatus"] != args.status:
                continue
            if args.source and sample["source"] != args.source:
                continue
            tags = ",".join(sample["tags"])
            print(f"{sample['id']:<40} {sample['reviewStatus']:<9} "
                  f"{sample['source']:<12} {sample['difficulty']:<10} "
                  f"{str(sample['domain'] or '-'):<18} [{name}] {sample['question'][:50]}"
                  f"{('  ' + tags) if tags else ''}")
            shown += 1
    if shown == 0:
        print("（无符合条件的样本）")
    return 0


def _remove_and_save(dataset_dir: Path, bucket_name: str, sample_id: str,
                     buckets: dict[str, list[dict]]) -> None:
    rows = [s for s in buckets[bucket_name] if s["id"] != sample_id]
    dio.write_jsonl(dataset_dir / bucket_name, rows)


def cmd_approve(args) -> int:
    buckets = load_all(args.dataset_dir)
    found = find_candidate(buckets, args.id)
    if found is None:
        # 已在 golden 中给出明确提示，避免误报成功
        if any(s["id"] == args.id for s in buckets[dio.GOLDEN_FILE]):
            print(f"[review] {args.id} 已在 {dio.GOLDEN_FILE}，无需重复 approve")
            return 0
        print(f"[review] 未在候选文件中找到 id={args.id}", file=sys.stderr)
        return 1
    bucket_name, sample = found

    problems = dio.validate_for_approve(sample)
    if problems:
        print(f"[review] 审核拒绝入库 {args.id}，存在以下问题：", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 1
    # reference 缺失只告警不禁入：该样本三项参考类指标记 null 不计分母（诚实口径支持）
    if not dio.has_reference(sample):
        print("[review] 提醒：reference 缺失，context_recall/factual_correctness/"
              "semantic_similarity 将跳过（null 不计分母）")
    # proxy 代理答案必须显式知悉：防止把"接线 sanity 近满分"误读为质量基线
    if sample["source"] == dio.SOURCE_SYNTHETIC and \
            sample["answerOrigin"] == dio.ANSWER_ORIGIN_PROXY and not args.keep_proxy:
        print(f"[review] {args.id} 的 answer 仍是 reference 代理（非系统真实回答）；"
              f"请先在候选文件中替换 answer，或显式加 --keep-proxy 知悉后果", file=sys.stderr)
        return 1

    sample["reviewStatus"] = dio.STATUS_APPROVED
    sample["reviewedBy"] = args.by
    sample["reviewedAt"] = utc_now_iso()
    if args.note:
        sample["note"] = args.note

    golden = buckets[dio.GOLDEN_FILE]
    replaced = False
    for i, existing in enumerate(golden):
        if existing["id"] == sample["id"]:
            golden[i] = sample
            replaced = True
            break
    if not replaced:
        golden.append(sample)
    dio.write_jsonl(args.dataset_dir / dio.GOLDEN_FILE, golden)
    _remove_and_save(args.dataset_dir, bucket_name, args.id, buckets)
    print(f"[review] approved: {args.id}（{sample['source']}/{sample['difficulty']}）"
          f"{' 已覆盖同 id 旧行' if replaced else ''}；来源文件 {bucket_name} 已移除")
    return 0


def cmd_reject(args) -> int:
    buckets = load_all(args.dataset_dir)
    # 驳回范围包含 golden：错误基线样本可经 reject 下线并留痕（基线生命周期闭环）
    found = find_candidate(buckets, args.id)
    from_golden = False
    if found is None:
        for sample in buckets[dio.GOLDEN_FILE]:
            if sample["id"] == args.id:
                found = (dio.GOLDEN_FILE, sample)
                from_golden = True
                break
    if found is None:
        print(f"[review] 未在候选或 golden 中找到 id={args.id}", file=sys.stderr)
        return 1
    bucket_name, sample = found
    sample["reviewStatus"] = dio.STATUS_REJECTED
    sample["reviewedBy"] = args.by
    sample["reviewedAt"] = utc_now_iso()
    if args.note:
        sample["note"] = args.note

    log_rows = buckets[dio.REJECTED_LOG_FILE]
    for i, existing in enumerate(log_rows):
        if existing["id"] == sample["id"]:
            log_rows[i] = sample
            break
    else:
        log_rows.append(sample)
    dio.write_jsonl(args.dataset_dir / dio.REJECTED_LOG_FILE, log_rows)
    _remove_and_save(args.dataset_dir, bucket_name, args.id, buckets)
    print(f"[review] rejected: {args.id}（来源 {bucket_name}），已留痕 {dio.REJECTED_LOG_FILE}"
          f"{'；该样本已从 golden 基线下线' if from_golden else ''}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="RAGAS 评测数据集专家审核 CLI（阶段三）")
    parser.add_argument("--dataset-dir", type=Path, default=DEFAULT_DATASET_DIR)
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("stats", help="数据集分布总览").set_defaults(func=cmd_stats)

    p_list = sub.add_parser("list", help="列出样本")
    p_list.add_argument("--status", choices=[dio.STATUS_PENDING, dio.STATUS_APPROVED,
                                             dio.STATUS_REJECTED])
    p_list.add_argument("--source", choices=list(dio.SOURCES))
    p_list.set_defaults(func=cmd_list)

    p_show = sub.add_parser("show", help="查看单条样本全文（候选/golden/驳回留痕均可）")
    p_show.add_argument("id")
    p_show.add_argument("--json", action="store_true", help="输出原始 JSON")
    p_show.set_defaults(func=cmd_show)

    p_edit = sub.add_parser("edit", help="补标/修订 pending 候选（approve 前）")
    p_edit.add_argument("id")
    p_edit.add_argument("--by", required=True, help="补标人标识（留痕）")
    p_edit.add_argument("--question", default=None)
    p_edit.add_argument("--answer", default=None, help="替换回答（合成代理答案会自动转 manual）")
    p_edit.add_argument("--reference", default=None, help="补 ground truth；空串表示显式清空")
    p_edit.add_argument("--context", action="append", default=None,
                        help="追加经审核上下文 chunk，可重复")
    p_edit.add_argument("--clear-contexts", action="store_true",
                        help="先清空 contexts 再追加本次 --context")
    p_edit.add_argument("--difficulty",
                        choices=[dio.DIFFICULTY_SIMPLE, dio.DIFFICULTY_REASONING,
                                 dio.DIFFICULTY_MULTI_HOP, dio.DIFFICULTY_BOUNDARY],
                        default=None)
    p_edit.add_argument("--tag", action="append", default=None, help="追加标签，可重复")
    p_edit.add_argument("--note", default=None)
    p_edit.set_defaults(func=cmd_edit)

    p_add = sub.add_parser("add", help="专家直接录入边界 case → golden（source=expert）")
    p_add.add_argument("--by", required=True, help="专家标识（留痕）")
    p_add.add_argument("--question", required=True)
    p_add.add_argument("--answer", required=True)
    p_add.add_argument("--context", action="append", required=True,
                       help="经审核上下文 chunk，至少一条，可重复")
    p_add.add_argument("--reference", default="", help="标准答案；缺失则三项参考指标跳过")
    p_add.add_argument("--domain", default=None)
    p_add.add_argument("--difficulty",
                       choices=[dio.DIFFICULTY_SIMPLE, dio.DIFFICULTY_REASONING,
                                dio.DIFFICULTY_MULTI_HOP, dio.DIFFICULTY_BOUNDARY],
                       default=dio.DIFFICULTY_BOUNDARY)
    p_add.add_argument("--tag", action="append", default=None)
    p_add.add_argument("--id", default=None, help="缺省自动生成 expert-<sha1(question)前10位>")
    p_add.add_argument("--note", default=None)
    p_add.set_defaults(func=cmd_add)

    p_approve = sub.add_parser("approve", help="审核通过候选并移入 golden 主集")
    p_approve.add_argument("id")
    p_approve.add_argument("--by", required=True, help="审核人标识（诚实留痕）")
    p_approve.add_argument("--note", default=None)
    p_approve.add_argument("--keep-proxy", action="store_true",
                           help="知悉合成样本 answer 为 reference 代理仍予通过")
    p_approve.set_defaults(func=cmd_approve)

    p_reject = sub.add_parser("reject", help="拒绝候选并留痕")
    p_reject.add_argument("id")
    p_reject.add_argument("--by", required=True)
    p_reject.add_argument("--note", default=None)
    p_reject.set_defaults(func=cmd_reject)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
