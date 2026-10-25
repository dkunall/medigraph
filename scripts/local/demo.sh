#!/usr/bin/env bash
# Runs the MediGraph demo against the running stack (all calls via the gateway on :8080).
# Writes demo-output.txt and patient-journey.md to $OUT_DIR (default: the directory you run it from).
#   ./demo.sh           run against whatever data is there
#   ./demo.sh --fresh   wipe the local containers first (./start.sh down && ./start.sh all) for a clean transcript
set -euo pipefail
OUT_DIR=${OUT_DIR:-$PWD}
cd "$(dirname "$0")"
if [[ "${1:-}" == "--fresh" ]]; then
  shift
  ./start.sh down && ./start.sh all
  sleep 10   # let the ledger pick a leader
fi
curl -sf -m 5 localhost:8080/actuator/health/readiness >/dev/null || { echo "gateway not ready; run ./start.sh all" >&2; exit 1; }
python3 -u medigraph_demo.py --out "$OUT_DIR/demo-output.txt" --journey "$OUT_DIR/patient-journey.md" "$@"
