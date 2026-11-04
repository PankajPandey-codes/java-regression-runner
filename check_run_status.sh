#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

MAX_AGE_MINUTES="${MAX_AGE_MINUTES:-30}"

latest_run="$(ls -1 results 2>/dev/null | sort | tail -n1 || true)"
if [[ -z "${latest_run:-}" ]]; then
  echo "No run folder found"
  exit 2
fi

status_file="results/${latest_run}/run-status.json"
if [[ ! -f "$status_file" ]]; then
  echo "Status file not found: $status_file"
  exit 2
fi

python3 - "$status_file" "$MAX_AGE_MINUTES" <<'PY'
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

status_path = Path(sys.argv[1])
max_age_minutes = int(sys.argv[2])
data = json.loads(status_path.read_text())

status = data.get("status", "")
updated_at = data.get("updatedAt")
if not updated_at:
    print("run-status.json missing updatedAt")
    sys.exit(2)

ts = datetime.fromisoformat(updated_at.replace("Z", "+00:00"))
age = datetime.now(timezone.utc) - ts
if age > timedelta(minutes=max_age_minutes):
    print(f"Run status stale: status={status} updatedAt={updated_at} ageMinutes={int(age.total_seconds() // 60)}")
    sys.exit(2)

print(json.dumps({
    "status": status,
    "suiteStatus": data.get("suiteStatus"),
    "suitePassed": data.get("suitePassed"),
    "runFolder": data.get("runFolder"),
    "updatedAt": updated_at,
    "stepsExecuted": data.get("stepsExecuted"),
    "stepsPassed": data.get("stepsPassed"),
    "stepsFailed": data.get("stepsFailed"),
    "infrastructureFailures": data.get("infrastructureFailures"),
}, indent=2))

if status == "FAILED_INFRA":
    sys.exit(2)
if status in {"RUNNING", "COMPLETED_SUCCESS", "COMPLETED_WITH_TEST_FAILURES"}:
    sys.exit(0)
if status == "INTERRUPTED":
    sys.exit(2)
sys.exit(2)
PY
