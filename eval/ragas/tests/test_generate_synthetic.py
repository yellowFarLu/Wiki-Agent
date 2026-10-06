import unittest

import _paths  # noqa: F401
import dataset_io as dio
import generate_synthetic as gen


class TestParseDistribution(unittest.TestCase):

    def test_default_spec_parsed(self):
        pairs = gen.parse_distribution(
            "single_hop_specific:0.5,multi_hop_abstract:0.25,multi_hop_specific:0.25")
        self.assertEqual([n for n, _ in pairs],
                         ["single_hop_specific", "multi_hop_abstract", "multi_hop_specific"])

    def test_bad_name_rejected(self):
        with self.assertRaises(ValueError):
            gen.parse_distribution("unknown:1.0")

    def test_bad_format_rejected(self):
        with self.assertRaises(ValueError):
            gen.parse_distribution("single_hop_specific")

    def test_non_positive_weight_rejected(self):
        with self.assertRaises(ValueError):
            gen.parse_distribution("single_hop_specific:0")


class TestMapSynthRow(unittest.TestCase):

    def test_single_hop_mapping(self):
        row = {
            "user_input": "审核要几天？",
            "reference_contexts": ["c1"],
            "reference": "三个工作日。",
            "synthesizer_name": "single_hop_specific_query_synthesizer",
            "query_style": "MISSPELLED",
            "persona_name": "商家",
        }
        s = gen.map_synth_row(row, "merchant_center")
        self.assertEqual(s["source"], dio.SOURCE_SYNTHETIC)
        self.assertEqual(s["reviewStatus"], dio.STATUS_PENDING)
        self.assertEqual(s["difficulty"], dio.DIFFICULTY_SIMPLE)
        self.assertEqual(s["domain"], "merchant_center")
        self.assertEqual(s["answer"], "三个工作日。")      # reference 代理
        self.assertEqual(s["reference"], "三个工作日。")
        self.assertEqual(s["answerOrigin"], dio.ANSWER_ORIGIN_PROXY)
        self.assertIn("noise:misspelled", s["tags"])
        self.assertTrue(s["id"].startswith("synth-merchant_center-"))
        self.assertEqual(s["contexts"], ["c1"])

    def test_multi_hop_abstract_is_reasoning(self):
        row = {"user_input": "q", "reference_contexts": ["a", "b"], "reference": "r",
               "synthesizer_name": "multi_hop_abstract_query_synthesizer"}
        s = gen.map_synth_row(row, "pms")
        self.assertEqual(s["difficulty"], dio.DIFFICULTY_REASONING)
        self.assertEqual(len(s["contexts"]), 2)

    def test_multi_hop_specific_is_multi_hop(self):
        row = {"user_input": "q", "reference_contexts": ["a", "b"], "reference": "r",
               "synthesizer_name": "multi_hop_specific_query_synthesizer"}
        s = gen.map_synth_row(row, "customs")
        self.assertEqual(s["difficulty"], dio.DIFFICULTY_MULTI_HOP)

    def test_id_stable_for_same_question(self):
        row = lambda: {"user_input": "同一问题", "reference_contexts": ["c"],
                       "reference": "r", "synthesizer_name": "x"}
        self.assertEqual(gen.make_sample_id("d1", "同一问题"),
                         gen.make_sample_id("d1", "同一问题"))


if __name__ == "__main__":
    unittest.main()
