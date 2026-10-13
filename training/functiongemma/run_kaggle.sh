#!/usr/bin/env bash
# Fine-tunes NILA's FunctionGemma router on Kaggle with the kaggle CLI.
#   1. builds data/nila_router.jsonl            2. creates or versions the private Kaggle dataset
#   3. pushes train.py as a private GPU kernel  4. waits, then downloads the .litertlm and eval.json to out/
# Needs: pip install kaggle, ~/.kaggle credentials, and the Gemma license accepted on
# https://www.kaggle.com/models/google/functiongemma for this Kaggle account.
set -euo pipefail
cd "$(dirname "$0")"
export PYTHONUTF8=1 PYTHONIOENCODING=utf-8
KAGGLE="python -m kaggle"
DATASET=gurupriyanda/nila-router-data
KERNEL=gurupriyanda/nila-functiongemma-router
POLL_SECONDS=60
# Outputs go to the D: drive by default; C: is nearly full on the dev PC.
OUT="${OUT:-D:/nila-kaggle/functiongemma}"

python make_dataset.py

if $KAGGLE datasets status "$DATASET" >/dev/null 2>&1; then
  $KAGGLE datasets version -p data -m "regenerated $(date +%F_%H%M)" -q
else
  $KAGGLE datasets create -p data -q
fi
# A new dataset version takes a moment before kernels can mount it.
until [ "$($KAGGLE datasets status "$DATASET" 2>/dev/null)" = "ready" ]; do sleep 10; done

cp train.py kernel/train.py
cp export_gate.py kernel/export_gate.py
$KAGGLE kernels push -p kernel

while true; do
  status=$($KAGGLE kernels status "$KERNEL" 2>&1)
  echo "$(date +%T) $status"
  case "$status" in
    *COMPLETE*|*complete*) break ;;
    *ERROR*|*error*|*CANCEL*|*cancel*) mkdir -p "$OUT"; $KAGGLE kernels logs "$KERNEL" > "$OUT/log.txt" 2>&1 || true; echo "failed; log in $OUT/log.txt"; exit 1 ;;
  esac
  sleep "$POLL_SECONDS"
done

mkdir -p "$OUT"
$KAGGLE kernels output "$KERNEL" -p "$OUT" -o --file-pattern '(\.litertlm|eval\.json)$'
$KAGGLE kernels logs "$KERNEL" > "$OUT/log.txt" 2>&1 || true
ls -l "$OUT"
