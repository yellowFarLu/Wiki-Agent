import json
import tempfile
import unittest
from pathlib import Path

import _paths  # noqa: F401
import dataset_io as dio


def write_rows(path: Path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


class TestNormalize(unittest.TestCase):

    def test_legacy_12_sample_gets_approved_defaults(self):
        """2026-10-05 首批无 provenance 字段的样本须被解释为 seed-manual approved。"""
        s = dio.normalize_sample({
            "id": "x", "question": "q", "contexts": ["c"],
            "answer": "a", "reference": "r"})
        self.assertEqual(s["source"], dio.SOURCE_SEED_MANUAL)
        self.assertEqual(s["reviewStatus"], dio.STATUS_APPROVED)
        self.assertEqual(s["difficulty"], dio.DIFFICULTY_SIMPLE)
        self.assertEqual(s["answerOrigin"], dio.ANSWER_ORIGIN_MANUAL)
        self.assertEqual(s["tags"], [])

    def test_missing_reference_and_contexts_normalized_empty(self):
        s = dio.normalize_sample({"id": "x", "question": "q"})
        self.assertEqual(s["reference"], "")
        self.assertEqual(s["contexts"], [])

    def test_comment_rows_skipped(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "f.jsonl"
            write_rows(p, [{"_comment": "说明"},
                           {"id": "a", "question": "q", "answer": "a"}])
            rows = dio.read_jsonl(p)
            self.assertEqual(len(rows), 1)


class TestSkippedMetrics(unittest.TestCase):

    def test_full_sample_skips_nothing(self):
        s = {"reference": "r", "contexts": ["c"]}
        self.assertEqual(dio.skipped_metrics(s), {})

    def test_no_reference_skips_three_reference_metrics(self):
        s = {"reference": "", "contexts": ["c"]}
        skipped = dio.skipped_metrics(s)
        self.assertEqual(set(skipped), set(dio.METRICS_NEEDING_REFERENCE))

    def test_no_contexts_skips_context_metrics(self):
        s = {"reference": "r", "contexts": []}
        skipped = dio.skipped_metrics(s)
        self.assertIn("faithfulness", skipped)
        self.assertIn("context_precision_without_reference", skipped)
        self.assertIn("context_recall", skipped)
        # answer_relevancy / factual_correctness / semantic_similarity 不依赖 contexts
        self.assertNotIn("answer_relevancy", skipped)
        self.assertNotIn("factual_correctness", skipped)

    def test_validate_approve_requires_contexts(self):
        problems = dio.validate_for_approve(
            {"question": "q", "answer": "a", "contexts": [], "reference": ""})
        self.assertTrue(any("contexts" in p for p in problems))
        # reference 缺失不在 approve 硬校验内（只跳过指标）
        self.assertFalse(any("reference" in p for p in problems))

    def test_validate_scoring_requires_answer(self):
        problems = dio.validate_scoring_input({"question": "q", "answer": ""})
        self.assertTrue(any("answer" in p for p in problems))


class TestDatasetDirLoading(unittest.TestCase):

    def _build_dir(self, d: Path):
        write_rows(d / dio.GOLDEN_FILE, [
            {"id": "g1", "question": "q", "answer": "a", "contexts": ["c"],
             "reviewStatus": "approved", "source": "seed-manual"},
            {"id": "g2", "question": "q", "answer": "a", "contexts": ["c"],
             "reviewStatus": "rejected"},
        ])
        write_rows(d / "candidates-synthetic.jsonl", [
            {"id": "s1", "question": "q", "answer": "a", "contexts": ["c"],
             "reviewStatus": "pending", "source": "synthetic"},
        ])
        write_rows(d / "candidates-production.jsonl", [
            {"id": "p1", "question": "q", "answer": "a", "contexts": ["c"],
             "reviewStatus": "pending", "source": "production"},
        ])

    def test_default_only_golden_non_rejected(self):
        with tempfile.TemporaryDirectory() as name:
            d = Path(name)
            self._build_dir(d)
            samples, manifest = dio.load_dataset_dir(d)
            ids = [s["id"] for s in samples]
            self.assertEqual(ids, ["g1"])
            # 默认模式不加载候选文件，manifest 只登记 golden
            self.assertEqual(set(manifest), {dio.GOLDEN_FILE})

    def test_include_pending_loads_candidates(self):
        with tempfile.TemporaryDirectory() as name:
            d = Path(name)
            self._build_dir(d)
            samples, _ = dio.load_dataset_dir(d, include_pending=True)
            ids = {s["id"] for s in samples}
            self.assertEqual(ids, {"g1", "s1", "p1"})

    def test_missing_dir_returns_empty_golden_entry(self):
        with tempfile.TemporaryDirectory() as name:
            samples, manifest = dio.load_dataset_dir(Path(name) / "nope")
            self.assertEqual(samples, [])
            self.assertEqual(manifest[dio.GOLDEN_FILE], 0)


class TestMergeCandidates(unittest.TestCase):

    def test_new_ids_added(self):
        merged, added, skipped = dio.merge_candidates(
            [{"id": "a", "reviewStatus": "pending"}],
            [{"id": "b", "reviewStatus": "pending"}])
        self.assertEqual((added, skipped), (1, 0))
        self.assertEqual({s["id"] for s in merged}, {"a", "b"})

    def test_duplicate_id_skipped_by_default(self):
        _, added, skipped = dio.merge_candidates(
            [{"id": "a", "reviewStatus": "pending", "v": 1}],
            [{"id": "a", "reviewStatus": "pending", "v": 2}])
        self.assertEqual((added, skipped), (0, 1))

    def test_force_overwrites_pending_but_never_approved(self):
        existing = [
            {"id": "p", "reviewStatus": "pending", "v": 1},
            {"id": "a", "reviewStatus": "approved", "v": 1},
        ]
        merged, added, skipped = dio.merge_candidates(
            existing,
            [{"id": "p", "reviewStatus": "pending", "v": 2},
             {"id": "a", "reviewStatus": "pending", "v": 9}],
            force=True)
        by_id = {s["id"]: s for s in merged}
        self.assertEqual(by_id["p"]["v"], 2)       # pending 被刷新
        self.assertEqual(by_id["a"]["v"], 1)       # approved 永不覆盖
        self.assertEqual(added, 0)
        self.assertEqual(skipped, 2)               # 1 行覆盖 + 1 行保护跳过


class TestCorpus(unittest.TestCase):

    def test_grouped_by_domain_and_min_two_chunks(self):
        with tempfile.TemporaryDirectory() as name:
            p = Path(name) / "c.jsonl"
            write_rows(p, [
                {"id": "1", "domain": "d1", "content": "aaa"},
                {"id": "2", "domain": "d1", "content": "bbb"},
            ])
            grouped = dio.load_corpus(p)
            self.assertEqual(len(grouped["d1"]), 2)

    def test_single_chunk_domain_rejected(self):
        with tempfile.TemporaryDirectory() as name:
            p = Path(name) / "c.jsonl"
            write_rows(p, [{"id": "1", "domain": "d1", "content": "aaa"}])
            with self.assertRaises(ValueError):
                dio.load_corpus(p)


if __name__ == "__main__":
    unittest.main()
