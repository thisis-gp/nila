# FunctionGemma router

`make_dataset.py` creates English-first synthetic examples from the native NILA capabilities in `NilaEngine.kt`. The test fails if a native capability is missing from the schema or from either split. Keep examples synthetic; never add user messages or private MCP arguments.

Run locally:

```powershell
python -m unittest -v test_make_dataset.py
python make_dataset.py --seed 7
```

For training, `run_kaggle.sh` uploads the generated dataset to the configured private Kaggle dataset/kernel. It needs Kaggle CLI credentials and accepted FunctionGemma model terms. `train.py` evaluates the entire held-out split, prints base and tuned tool-name accuracy per tool, and exports only when overall tuned accuracy beats base and every declared tool has a nonzero hit. Export uses the exact filename NILA loads (`nila-router-functiongemma-run8-q8-ekv2048.litertlm`) and a 2048-token KV cache. Copy only a passing output to the app-private `models/` directory; model artifacts are not committed.

The runtime offers every built-in capability schema plus currently registered MCP schemas to FunctionGemma. Built-ins are represented in this fine-tune set; newly installed MCP tools are prompt-only and have no per-server training guarantee. Keep their schemas bounded and validate every proposed call through `NilaEngine` before execution. FunctionGemma selects a typed capability; it never supplies executable code.

Existing artifacts under `D:\nila-kaggle\functiongemma` are historical experiments, not proof that the current dataset passed this gate. Retrain from this exact source before shipping a replacement model.
