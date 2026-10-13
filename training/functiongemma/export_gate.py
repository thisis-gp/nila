"""Small, dependency-free export gate for the Kaggle training job."""


def export_gate(base_accuracy, tuned_accuracy, per_tool, expected_tools):
    missing = sorted(set(expected_tools) - set(per_tool))
    zero_hit = sorted(name for name, (hits, _) in per_tool.items() if hits == 0)
    if missing or zero_hit or tuned_accuracy <= base_accuracy:
        return False, (f"missing eval tools={missing}, zero-hit tools={zero_hit}, "
                       f"base={base_accuracy:.3f}, tuned={tuned_accuracy:.3f}")
    return True, "all tools scored and tuned accuracy improved"
