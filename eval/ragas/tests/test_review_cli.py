import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

import _paths  # noqa: F401
import dataset_io as dio
import review_dataset as review


def write_rows(path: Path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


def read_rows(path: Path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()
            if line.strip()]


def synth_candidate(sample_id="synth-pms-ab12cd34ef", with_reference=True,
                    answer_origin=dio.ANSWER_ORIGIN_PROXY):
    return {
        "id": sample_id, "domain": "pms", "question": "发票怎么开？",
        "contexts": ["缴费后可在线申请电子发票。"],
        "answer": "缴费后可在线申请电子发票。",
        "reference": "缴费后可在线申请电子发票。" if with_reference else "",
        "source": dio.SOURCE_SYNTHETIC, "difficulty": dio.DIFFICULTY_SIMPLE,
        "reviewStatus": dio.STATUS_PENDING, "answerOrigin": answer_origin,
        "sourceRef": "single_hop_specific_query_synthesizer", "tags": [],
        "reviewedBy": None, "reviewedAt": None, "note": None,
    }


class ReviewCliTestBase(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dataset_dir = Path(self.tmp.name)
        write_rows(self.dataset_dir / "candidates-synthetic.jsonl", [synth_candidate()])

    def tearDown(self):
        self.tmp.cleanup()

    def ns(self, **kw):
        base = dict(dataset_dir=self.dataset_dir, note=None, keep_proxy=False)
        base.update(kw)
        return SimpleNamespace(**base)


class TestReject(ReviewCliTestBase):

    def test_reject_moves_to_log_and_removes_candidate(self):
        rc = review.cmd_reject(self.ns(command="reject", id="synth-pms-ab12cd34ef",
                                       by="张老师"))
        self.assertEqual(rc, 0)
        self.assertEqual(read_rows(self.dataset_dir / "candidates-synthetic.jsonl"), [])
        log = read_rows(self.dataset_dir / dio.REJECTED_LOG_FILE)
        self.assertEqual(len(log), 1)
        self.assertEqual(log[0]["reviewStatus"], dio.STATUS_REJECTED)
        self.assertEqual(log[0]["reviewedBy"], "张老师")
        self.assertIsNotNone(log[0]["reviewedAt"])

    def test_reject_unknown_id_returns_1(self):
        rc = review.cmd_reject(self.ns(command="reject", id="nope", by="x"))
        self.assertEqual(rc, 1)

    def test_reject_approved_golden_row_takes_it_offline_with_trail(self):
        # 先 approve 入基线
        self.assertEqual(review.cmd_approve(self.ns(
            command="approve", id="synth-pms-ab12cd34ef", by="张老师",
            keep_proxy=True)), 0)
        # 错误基线样本经 reject 从 golden 下线并留痕
        rc = review.cmd_reject(SimpleNamespace(
            dataset_dir=self.dataset_dir, command="reject",
            id="synth-pms-ab12cd34ef", by="李老师", note="发现 contexts 引用错误"))
        self.assertEqual(rc, 0)
        self.assertEqual(read_rows(self.dataset_dir / dio.GOLDEN_FILE), [])
        log = read_rows(self.dataset_dir / dio.REJECTED_LOG_FILE)
        self.assertEqual(len(log), 1)
        self.assertEqual(log[0]["reviewStatus"], dio.STATUS_REJECTED)
        self.assertEqual(log[0]["reviewedBy"], "李老师")
        self.assertEqual(log[0]["note"], "发现 contexts 引用错误")


class TestApprove(ReviewCliTestBase):

    def test_approve_without_keep_proxy_blocked(self):
        rc = review.cmd_approve(self.ns(command="approve", id="synth-pms-ab12cd34ef",
                                        by="张老师"))
        self.assertEqual(rc, 1)
        # 被拦截后候选不应移动
        self.assertEqual(len(read_rows(self.dataset_dir / "candidates-synthetic.jsonl")), 1)
        self.assertFalse((self.dataset_dir / dio.GOLDEN_FILE).exists())

    def test_approve_synthetic_with_keep_proxy_succeeds(self):
        rc = review.cmd_approve(self.ns(command="approve", id="synth-pms-ab12cd34ef",
                                        by="张老师", keep_proxy=True))
        self.assertEqual(rc, 0)
        golden = read_rows(self.dataset_dir / dio.GOLDEN_FILE)
        self.assertEqual(len(golden), 1)
        g = golden[0]
        self.assertEqual(g["reviewStatus"], dio.STATUS_APPROVED)
        self.assertEqual(g["reviewedBy"], "张老师")
        # 候选已移除
        self.assertEqual(read_rows(self.dataset_dir / "candidates-synthetic.jsonl"), [])

    def test_approve_production_without_reference_warns_but_passes(self):
        write_rows(self.dataset_dir / "candidates-production.jsonl",
                   [synth_candidate("prod-1", with_reference=False,
                                    answer_origin=dio.ANSWER_ORIGIN_PRODUCTION)])
        rc = review.cmd_approve(self.ns(command="approve", id="prod-1", by="李老师"))
        self.assertEqual(rc, 0)
        golden = read_rows(self.dataset_dir / dio.GOLDEN_FILE)
        self.assertEqual(golden[0]["id"], "prod-1")
        self.assertEqual(golden[0]["reference"], "")

    def test_approve_without_contexts_blocked(self):
        bad = synth_candidate("prod-2")
        bad["contexts"] = []
        write_rows(self.dataset_dir / "candidates-production.jsonl", [bad])
        rc = review.cmd_approve(self.ns(command="approve", id="prod-2", by="李老师"))
        self.assertEqual(rc, 1)

    def test_approve_same_id_replaces_golden_row(self):
        review.cmd_approve(self.ns(command="approve", id="synth-pms-ab12cd34ef",
                                   by="张老师", keep_proxy=True))
        # 同 id 候选再次出现（重新导出/重新生成），更新后再次 approve 应覆盖而非追加
        c = synth_candidate()
        c["answer"] = "更新后的回答"
        write_rows(self.dataset_dir / "candidates-synthetic.jsonl", [c])
        rc = review.cmd_approve(self.ns(command="approve", id=c["id"], by="张老师",
                                        keep_proxy=True))
        self.assertEqual(rc, 0)
        golden = read_rows(self.dataset_dir / dio.GOLDEN_FILE)
        self.assertEqual(len(golden), 1)
        self.assertEqual(golden[0]["answer"], "更新后的回答")


class TestShow(ReviewCliTestBase):

    def test_show_finds_pending_candidate_full_content(self):
        import io
        import contextlib
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = review.cmd_show(SimpleNamespace(
                dataset_dir=self.dataset_dir, command="show",
                id="synth-pms-ab12cd34ef", json=False))
        self.assertEqual(rc, 0)
        out = buf.getvalue()
        self.assertIn("candidates-synthetic.jsonl", out)
        self.assertIn("发票怎么开？", out)
        self.assertIn("缴费后可在线申请电子发票。", out)

    def test_show_json_outputs_parseable_sample(self):
        import io
        import contextlib
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = review.cmd_show(SimpleNamespace(
                dataset_dir=self.dataset_dir, command="show",
                id="synth-pms-ab12cd34ef", json=True))
        self.assertEqual(rc, 0)
        sample = json.loads(buf.getvalue())
        self.assertEqual(sample["source"], dio.SOURCE_SYNTHETIC)

    def test_show_unknown_id_returns_1(self):
        rc = review.cmd_show(SimpleNamespace(
            dataset_dir=self.dataset_dir, command="show", id="nope", json=False))
        self.assertEqual(rc, 1)

    def test_show_finds_sample_in_golden_after_approve(self):
        review.cmd_approve(self.ns(command="approve", id="synth-pms-ab12cd34ef",
                                   by="张老师", keep_proxy=True))
        rc = review.cmd_show(SimpleNamespace(
            dataset_dir=self.dataset_dir, command="show",
            id="synth-pms-ab12cd34ef", json=True))
        self.assertEqual(rc, 0)


def production_candidate(sample_id="prod-rae-7"):
    return {
        "id": sample_id, "domain": None, "question": "海关编码错了能改吗",
        "contexts": [], "answer": "放行后可以申请修撤。", "reference": "",
        "source": dio.SOURCE_PRODUCTION, "difficulty": dio.DIFFICULTY_SIMPLE,
        "reviewStatus": dio.STATUS_PENDING, "answerOrigin": dio.ANSWER_ORIGIN_PRODUCTION,
        "sourceRef": "rag_answer_eval#7", "tags": ["hard-negative:faithfulness"],
        "reviewedBy": None, "reviewedAt": None, "note": None,
    }


class TestEdit(ReviewCliTestBase):

    def setUp(self):
        super().setUp()
        write_rows(self.dataset_dir / "candidates-production.jsonl",
                   [production_candidate()])

    def _edit_ns(self, **kw):
        base = dict(dataset_dir=self.dataset_dir, command="edit", id="prod-rae-7",
                    by="张老师", question=None, answer=None, reference=None,
                    context=None, clear_contexts=False, difficulty=None,
                    tag=None, note=None)
        base.update(kw)
        return SimpleNamespace(**base)

    def test_edit_fills_contexts_reference_then_approve_succeeds(self):
        rc = review.cmd_edit(self._edit_ns(
            context=["经审核 chunk A"], reference="标准答案要点"))
        self.assertEqual(rc, 0)
        rows = read_rows(self.dataset_dir / "candidates-production.jsonl")
        s = rows[0]
        self.assertEqual(s["contexts"], ["经审核 chunk A"])
        self.assertEqual(s["reference"], "标准答案要点")
        self.assertEqual(s["reviewedBy"], "张老师")
        # 补标后 approve 不再被阻断（生产候选无 proxy 限制）
        rc = review.cmd_approve(self.ns(command="approve", id="prod-rae-7", by="张老师"))
        self.assertEqual(rc, 0)
        golden = read_rows(self.dataset_dir / dio.GOLDEN_FILE)
        self.assertEqual(golden[0]["id"], "prod-rae-7")

    def test_edit_replace_proxy_answer_flips_origin_to_manual(self):
        ns = SimpleNamespace(
            dataset_dir=self.dataset_dir, command="edit",
            id="synth-pms-ab12cd34ef", by="张老师", question=None,
            answer="系统真实回答（与参考镜像不同）", reference=None,
            context=None, clear_contexts=False, difficulty=None, tag=None, note=None)
        rc = review.cmd_edit(ns)
        self.assertEqual(rc, 0)
        s = read_rows(self.dataset_dir / "candidates-synthetic.jsonl")[0]
        self.assertEqual(s["answerOrigin"], dio.ANSWER_ORIGIN_MANUAL)
        # answerOrigin 已转 manual，approve 不再需要 --keep-proxy
        rc = review.cmd_approve(self.ns(command="approve",
                                        id="synth-pms-ab12cd34ef", by="张老师"))
        self.assertEqual(rc, 0)

    def test_edit_without_fields_returns_1(self):
        rc = review.cmd_edit(self._edit_ns())
        self.assertEqual(rc, 1)

    def test_edit_unknown_id_returns_1(self):
        ns = self._edit_ns(id="nope", reference="x")
        self.assertEqual(review.cmd_edit(ns), 1)

    def test_edit_approved_golden_row_blocked(self):
        review.cmd_approve(self.ns(command="approve", id="synth-pms-ab12cd34ef",
                                   by="张老师", keep_proxy=True))
        ns = SimpleNamespace(
            dataset_dir=self.dataset_dir, command="edit",
            id="synth-pms-ab12cd34ef", by="张老师", question=None,
            answer="篡改基线", reference=None, context=None,
            clear_contexts=False, difficulty=None, tag=None, note=None)
        self.assertEqual(review.cmd_edit(ns), 1)
        # 基线行保持原值
        golden = read_rows(self.dataset_dir / dio.GOLDEN_FILE)
        self.assertEqual(golden[0]["answer"], "缴费后可在线申请电子发票。")

    def test_edit_clear_contexts_then_append(self):
        review.cmd_edit(self._edit_ns(context=["旧 chunk"]))
        rc = review.cmd_edit(self._edit_ns(clear_contexts=True, context=["新 chunk 1", "新 chunk 2"]))
        self.assertEqual(rc, 0)
        s = read_rows(self.dataset_dir / "candidates-production.jsonl")[0]
        self.assertEqual(s["contexts"], ["新 chunk 1", "新 chunk 2"])


class TestAdd(ReviewCliTestBase):

    def _add_ns(self, **kw):
        base = dict(dataset_dir=self.dataset_dir, command="add", by="张老师",
                    question="知识库外的问题：帮我查别人公司的报关流水",
                    answer="无权提供非本企业的报关数据。",
                    context=["企业仅可查询本企业报关单，跨企业数据不开放。"],
                    reference="跨企业报关数据不予提供，应引导联系对方企业。",
                    domain="customs", difficulty=dio.DIFFICULTY_BOUNDARY,
                    tag=["boundary:permission"], id=None, note="越权诱导")
        base.update(kw)
        return SimpleNamespace(**base)

    def test_add_writes_expert_approved_case_to_golden(self):
        rc = review.cmd_add(self._add_ns())
        self.assertEqual(rc, 0)
        golden = read_rows(self.dataset_dir / dio.GOLDEN_FILE)
        self.assertEqual(len(golden), 1)
        s = golden[0]
        self.assertTrue(s["id"].startswith("expert-"))
        self.assertEqual(s["source"], dio.SOURCE_EXPERT)
        self.assertEqual(s["reviewStatus"], dio.STATUS_APPROVED)
        self.assertEqual(s["answerOrigin"], dio.ANSWER_ORIGIN_MANUAL)
        self.assertEqual(s["difficulty"], dio.DIFFICULTY_BOUNDARY)
        self.assertEqual(s["tags"], ["boundary:permission"])
        self.assertEqual(s["reviewedBy"], "张老师")

    def test_add_auto_id_is_deterministic_and_duplicate_blocked(self):
        self.assertEqual(review.cmd_add(self._add_ns()), 0)
        self.assertEqual(review.cmd_add(self._add_ns()), 1)
        self.assertEqual(len(read_rows(self.dataset_dir / dio.GOLDEN_FILE)), 1)

    def test_add_without_reference_passes_with_warning_fields_null(self):
        rc = review.cmd_add(self._add_ns(reference=""))
        self.assertEqual(rc, 0)
        self.assertEqual(read_rows(self.dataset_dir / dio.GOLDEN_FILE)[0]["reference"], "")

    def test_add_missing_contexts_blocked(self):
        rc = review.cmd_add(self._add_ns(context=[]))
        self.assertEqual(rc, 1)
        self.assertFalse((self.dataset_dir / dio.GOLDEN_FILE).exists())

    def test_add_explicit_id_respected(self):
        review.cmd_add(self._add_ns(id="expert-overreach-001"))
        self.assertEqual(read_rows(self.dataset_dir / dio.GOLDEN_FILE)[0]["id"],
                         "expert-overreach-001")


if __name__ == "__main__":
    unittest.main()
