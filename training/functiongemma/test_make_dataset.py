import random
import unittest

from make_dataset import call, prompt_family, split_by_template


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


if __name__ == "__main__":
    unittest.main()
