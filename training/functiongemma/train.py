"""Kaggle kernel: fine-tune FunctionGemma 270M as NILA's router and export a .litertlm for the phone.

Follows Google's "Fine-tune FunctionGemma 270M for Mobile Actions" notebook (gemma-cookbook), adapted to
Kaggle: the base model comes from Kaggle Models (no HF token) and the data from the nila-router-data dataset.

Outputs in /kaggle/working:
  nila-router-functiongemma-run8-q8-ekv2048.litertlm   the exact filename NILA's ModelCatalog loads
  eval.json                         base vs tuned accuracy per tool on the held-out split
"""

import os

# 12 tool schemas make ~1800-token prompts; fragmentation, not size, broke batch 2 on the T4.
os.environ.setdefault("PYTORCH_CUDA_ALLOC_CONF", "expandable_segments:True")

import glob
import json
import os
import re
import shutil
import subprocess
import sys

subprocess.run([sys.executable, "-m", "pip", "install", "-q", "transformers==4.57.1", "trl==0.25.1", "datasets==4.4.1", "liger-kernel"], check=True)

import torch  # noqa: E402
from datasets import load_dataset  # noqa: E402
from transformers import AutoModelForCausalLM, AutoTokenizer  # noqa: E402
from trl import SFTConfig, SFTTrainer  # noqa: E402
from export_gate import export_gate  # noqa: E402

WORK = "/kaggle/working"
CHECKPOINT = f"{WORK}/nila-router"
EPOCHS = 2
LEARNING_RATE = 1e-5


def find_one(pattern, what):
    hits = sorted(glob.glob(pattern, recursive=True))
    if not hits:
        sys.exit(f"{what} not found: {pattern}. Is the source attached in kernel-metadata.json?")
    return hits[0]


model_dir = os.path.dirname(find_one("/kaggle/input/**/functiongemma*/**/config.json", "FunctionGemma model"))
data_file = find_one("/kaggle/input/**/nila_router.jsonl", "NILA router dataset")
print("model:", model_dir, "\ndata:", data_file)

tokenizer = AutoTokenizer.from_pretrained(model_dir)
raw = load_dataset("json", data_files=data_file)["train"]


def apply_format(sample):
    full = tokenizer.apply_chat_template(sample["messages"], tools=sample["tools"], tokenize=False, add_generation_prompt=False)
    prompt = tokenizer.apply_chat_template(sample["messages"][:-1], tools=sample["tools"], tokenize=False, add_generation_prompt=True)
    return {"prompt": prompt, "completion": full[len(prompt):], "split": sample["metadata"]}


data = raw.map(apply_format, remove_columns=[c for c in raw.column_names if c not in ("messages",)])
train = data.filter(lambda r: r["split"] == "train")
evaluation = data.filter(lambda r: r["split"] == "eval")
held_out = evaluation
longest = max(len(tokenizer.tokenize(r["prompt"] + r["completion"])) for r in data)
expected_tools = {t["function"]["name"] for t in raw[0]["tools"]}
eval_tools = {r["messages"][-1]["tool_calls"][0]["function"]["name"] for r in held_out}
if eval_tools != expected_tools:
    sys.exit(f"Held-out split does not cover every declared tool: missing={sorted(expected_tools - eval_tools)}, "
             f"unexpected={sorted(eval_tools - expected_tools)}")
print(f"train={len(train)} eval={len(held_out)} longest={longest} tokens")
print("sample prompt tail:", train[0]["prompt"][-300:])
print("sample completion:", train[0]["completion"])

CALL = re.compile(r"<start_function_call>call:([\w-]+)\{(.*?)\}<end_function_call>", re.DOTALL)


def predicted_name(model, prompt):
    ids = tokenizer(prompt, return_tensors="pt", add_special_tokens=False).to(model.device)
    with torch.no_grad():
        out = model.generate(**ids, max_new_tokens=64, do_sample=False)
    text = tokenizer.decode(out[0][ids["input_ids"].shape[1]:], skip_special_tokens=False)
    match = CALL.search(text)
    return (match.group(1) if match else None), text


def score(model, label):
    model.eval()
    per_tool, misses = {}, []
    for row in held_out:
        want = row["messages"][-1]["tool_calls"][0]["function"]["name"]
        got, text = predicted_name(model, row["prompt"])
        hit, total = per_tool.get(want, (0, 0))
        per_tool[want] = (hit + (got == want), total + 1)
        if got != want and len(misses) < 15:
            misses.append({"user": row["messages"][1]["content"], "want": want, "got": got, "raw": text[:120]})
    correct = sum(h for h, _ in per_tool.values()) / max(1, sum(t for _, t in per_tool.values()))
    print(f"\n[{label}] tool-name accuracy {correct:.3f}")
    for name, (hit, total) in sorted(per_tool.items()):
        print(f"  {name:15s} {hit}/{total}")
    for miss in misses:
        print("  miss:", miss)
    return {"accuracy": correct, "per_tool": per_tool, "misses": misses}


# T4 has no bf16: train in fp32, the model is small enough.
model = AutoModelForCausalLM.from_pretrained(model_dir, dtype=torch.float32, attn_implementation="eager", device_map="auto")
model.config.pad_token_id = tokenizer.pad_token_id
base = score(model, "base")

trainer = SFTTrainer(
    model=model,
    args=SFTConfig(
        output_dir=CHECKPOINT,
        num_train_epochs=EPOCHS,
        per_device_train_batch_size=4,
        gradient_accumulation_steps=8,
        # 12 tool schemas make ~1800-token prompts: the full 262k-vocab logits ran out of T4 memory even at
        # batch 1. Liger's fused loss never builds them. No fp16: Gemma overflowed in it and scored 0% after training.
        use_liger_kernel=True,
        learning_rate=LEARNING_RATE,
        lr_scheduler_type="cosine",
        warmup_ratio=0.05,
        max_length=longest + 32,
        packing=False,
        completion_only_loss=True,
        gradient_checkpointing=True,
        logging_steps=10,
        eval_strategy="no",  # scored after training instead; trainer eval OOMs on a T4
        save_strategy="no",
        report_to="none",
    ),
    train_dataset=train.remove_columns(["messages", "split"]),
    processing_class=tokenizer,
)
trainer.train()
trainer.save_model(CHECKPOINT)
tokenizer.save_pretrained(CHECKPOINT)
# The LiteRT converter reads the SentencePiece file directly.
if not os.path.exists(f"{CHECKPOINT}/tokenizer.model"):
    shutil.copy(find_one(f"{model_dir}/**/tokenizer.model", "tokenizer.model"), CHECKPOINT)

tuned = score(model, "tuned")
with open(f"{WORK}/eval.json", "w") as f:
    json.dump({"base": base, "tuned": tuned}, f, indent=1)

allowed, reason = export_gate(base["accuracy"], tuned["accuracy"], tuned["per_tool"], expected_tools)
if not allowed:
    sys.exit(f"Tuned model failed export gate ({reason}); skipping export.")

# Export in a fresh process: the converter pins its own torch build.
del model, trainer
torch.cuda.empty_cache()
subprocess.run([sys.executable, "-m", "pip", "uninstall", "-y", "-q", "tensorflow"], check=False)
# As in Google's notebook: force-reinstall pulls the torch build the converter expects; Kaggle's torchao
# was too old (No module named 'torchao.quantization.pt2e').
subprocess.run([sys.executable, "-m", "pip", "install", "-q", "--force-reinstall", "ai-edge-torch-nightly"], check=True)
subprocess.run([sys.executable, "-m", "pip", "install", "-q", "-U", "torchao", "ai-edge-litert-nightly"], check=True)

CONVERT = r'''
import os, sys
from ai_edge_torch.generative.examples.gemma3 import gemma3
from ai_edge_torch.generative.utilities import converter
from ai_edge_torch.generative.utilities.export_config import ExportConfig
from ai_edge_torch.generative.layers import kv_cache

checkpoint, out = sys.argv[1], sys.argv[2]
metadata = os.path.join(out, "base_llm_metadata.textproto")
with open(metadata, "w") as f:
    f.write("""start_token: { token_ids: { ids: [ 2 ] } }
stop_tokens: { token_str: "<end_of_turn>" }
stop_tokens: { token_str: "<start_function_response>" }
llm_model_type: { function_gemma: {} }
""")
config = ExportConfig()
config.kvcache_layout = kv_cache.KV_LAYOUT_TRANSPOSED
config.mask_as_input = True
converter.convert_to_litert(
    gemma3.build_model_270m(checkpoint),
    output_path=out,
    output_name_prefix="nila-router-functiongemma-run8-q8-ekv2048",
    prefill_seq_len=256,
    kv_cache_max_len=2048,
    quantize="dynamic_int8",
    export_config=config,
    tokenizer_model_path=os.path.join(checkpoint, "tokenizer.model"),
    base_llm_metadata_path=metadata,
    output_format="litertlm",
)
'''
with open(f"{WORK}/convert.py", "w") as f:
    f.write(CONVERT)
subprocess.run([sys.executable, f"{WORK}/convert.py", CHECKPOINT, WORK], check=True)
shutil.rmtree(CHECKPOINT, ignore_errors=True)  # keep the output small: only the .litertlm and eval.json
print("outputs:", os.listdir(WORK))
