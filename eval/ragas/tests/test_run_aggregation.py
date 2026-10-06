import unittest

import _paths  # noqa: F401
import dataset_io as dio
import run_ragas_eval as runner


# metric_specs 在聚合函数中只取键名，metric 实例用 None 占位
SPECS = [(key, None, None) for key in dio.METRIC_KEYS]


def sample(sample_id, source=dio.SOURCE_SEED_MANUAL, status=dio.STATUS_APPROVED):
    return {"id": sample_id, "source": source, "reviewStatus": status}


def results(score_map):
    return [{"id": sid, "scores": {key: score_map.get(sid, {}).get(key)
                                   for key in dio.METRIC_KEYS}}
            for sid in score_map]


class TestAggregate(unittest.TestCase):

    def test_null_excluded_from_denominator(self):
        samples = [sample("a"), sample("b"), sample("c")]
        r = results({
            "a": {"faithfulness": 1.0},
            "b": {"faithfulness": 0.5},
            "c": {"faithfulness": None},   # 跳过/失败，不进分母
        })
        agg = runner.aggregate(samples, SPECS, r)
        self.assertEqual(agg["faithfulness"]["value"], 0.75)
        self.assertEqual(agg["faithfulness"]["scored"], 2)
        self.assertEqual(agg["faithfulness"]["missing"], 1)

    def test_all_null_yields_none_not_zero(self):
        samples = [sample("a")]
        r = results({"a": {key: None for key in dio.METRIC_KEYS}})
        agg = runner.aggregate(samples, SPECS, r)
        self.assertIsNone(agg["answer_relevancy"]["value"])
        self.assertEqual(agg["answer_relevancy"]["scored"], 0)


class TestAggregateBySource(unittest.TestCase):

    def test_groups_independent_denominators(self):
        samples = [
            sample("s1", dio.SOURCE_SEED_MANUAL),
            sample("s2", dio.SOURCE_SEED_MANUAL),
            sample("p1", dio.SOURCE_PRODUCTION),
        ]
        r = results({
            "s1": {"faithfulness": 1.0},
            "s2": {"faithfulness": 0.0},
            "p1": {"faithfulness": 0.8},
        })
        by_source = runner.aggregate_by_source(samples, SPECS, r)
        self.assertEqual(by_source["seed-manual"]["sampleCount"], 2)
        self.assertEqual(by_source["seed-manual"]["metrics"]["faithfulness"]["value"], 0.5)
        self.assertEqual(by_source["production"]["metrics"]["faithfulness"]["value"], 0.8)
        # 没有样本的来源不出现在报告中
        self.assertNotIn("expert", by_source)


class TestBuildAggregates(unittest.TestCase):

    def test_pending_never_enters_main_aggregate_and_grouped_by_source(self):
        samples = [
            sample("g1", dio.SOURCE_SEED_MANUAL, dio.STATUS_APPROVED),
            sample("y1", dio.SOURCE_SYNTHETIC, dio.STATUS_PENDING),
            sample("y2", dio.SOURCE_SYNTHETIC, dio.STATUS_PENDING),
            sample("d1", dio.SOURCE_PRODUCTION, dio.STATUS_PENDING),
        ]
        r = results({
            "g1": {"faithfulness": 1.0},
            "y1": {"faithfulness": 0.2},
            "y2": {"faithfulness": 0.4},
            "d1": {"faithfulness": 0.9},
        })
        out = runner.build_aggregates(samples, SPECS, r)

        # 主聚合只有 approved 一条；pending 分数绝不污染基线
        self.assertEqual(out["aggregates"]["faithfulness"]["scored"], 1)
        self.assertEqual(out["aggregates"]["faithfulness"]["value"], 1.0)
        self.assertEqual(set(out["aggregatesBySource"].keys()), {"seed-manual"})

        # pending 预览：总计 + 按来源分组（专家对比合成 vs 生产候选质量）
        review = out["aggregatesReviewPending"]
        self.assertEqual(review["all"]["faithfulness"]["scored"], 3)
        self.assertEqual(review["all"]["faithfulness"]["value"], 0.5)
        self.assertEqual(review["bySource"]["synthetic"]["sampleCount"], 2)
        self.assertEqual(review["bySource"]["synthetic"]["metrics"]["faithfulness"]["value"], 0.3)
        self.assertEqual(review["bySource"]["production"]["metrics"]["faithfulness"]["value"], 0.9)

    def test_no_pending_yields_none(self):
        samples = [sample("g1", dio.SOURCE_SEED_MANUAL, dio.STATUS_APPROVED)]
        r = results({"g1": {"faithfulness": 1.0}})
        out = runner.build_aggregates(samples, SPECS, r)
        self.assertIsNone(out["aggregatesReviewPending"])


if __name__ == "__main__":
    unittest.main()
