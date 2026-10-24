import random
import re
import unittest
from pathlib import Path

from make_dataset import TOOLS, call, prompt_family, rows, split_by_template
from export_gate import export_gate


class PromptSplitTest(unittest.TestCase):
    def test_related_entity_variants_stay_in_one_split(self):
        examples = [
            ("order biryani", call("search_food", {"query": "biryani"})),
            ("order pizza", call("search_food", {"query": "pizza"})),
            ("find biryani near me", call("search_food", {"query": "biryani"})),
            ("wake me up at 6", call("create_alarm", {"hour": 6})),
            ("wake me up at 7", call("create_alarm", {"hour": 7})),
        ]

        split = split_by_template(examples, 0.4, random.Random(7))
        labels = {user: metadata for metadata, user, _ in split}

        self.assertEqual(labels["order biryani"], labels["order pizza"])
        self.assertEqual(labels["wake me up at 6"], labels["wake me up at 7"])
        families = {}
        for metadata, user, _ in split:
            families.setdefault(prompt_family(user), set()).add(metadata)
        self.assertTrue(all(len(metadata) == 1 for metadata in families.values()))
        self.assertEqual(len(split), len(examples))

    def test_all_native_capabilities_have_train_and_held_out_examples(self):
        root = Path(__file__).resolve().parents[2]
        source = (root / "app/src/main/java/com/nila/launcher/NilaEngine.kt").read_text(encoding="utf-8")
        native = set(re.findall(r'CapabilityDescriptor\(\s*id\s*=\s*"([^"]+)"', source, re.S))
        names = {entry["function"]["name"] for entry in TOOLS}
        examples = split_by_template(rows(random.Random(7)), 0.1, random.Random(7))
        train = {answer["tool_calls"][0]["function"]["name"] for split, _, answer in examples if split == "train"}
        evaluation = {answer["tool_calls"][0]["function"]["name"] for split, _, answer in examples if split == "eval"}

        self.assertEqual(native | {"chat"}, names)
        self.assertEqual(names, train)
        self.assertEqual(names, evaluation)

    def test_export_gate_rejects_zero_hit_or_missing_tools(self):
        expected = {"search_food", "chat"}
        self.assertFalse(export_gate(0.2, 0.8, {"search_food": (1, 1)}, expected)[0])
        self.assertFalse(export_gate(0.2, 0.8, {"search_food": (1, 1), "chat": (0, 1)}, expected)[0])
        self.assertFalse(export_gate(0.2, 0.2, {"search_food": (1, 1), "chat": (1, 1)}, expected)[0])
        self.assertTrue(export_gate(0.2, 0.8, {"search_food": (1, 1), "chat": (1, 1)}, expected)[0])


if __name__ == "__main__":
    unittest.main()
