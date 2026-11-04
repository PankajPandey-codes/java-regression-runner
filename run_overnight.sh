#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

JAR="build/libs/java-regression-runner-0.1.0-all.jar"
LOG="run.log"
PID="run.pid"
DEFAULT_LOCK="$SCRIPT_DIR/../regression-runner.lock"
ENV_FILE="${JR_ENV_FILE:-$SCRIPT_DIR/runner.env}"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

JAVA_CMD="${JR_JAVA_BIN:-java}"
THREADS="${JR_THREADS:-3}"
PARTIAL_REPORT_INTERVAL_SECONDS="${JR_PARTIAL_REPORT_INTERVAL_SECONDS:-300}"
CONNECT_TIMEOUT_MS="${JR_CONNECT_TIMEOUT_MS:-60000}"
READ_TIMEOUT_MS="${JR_READ_TIMEOUT_MS:-900000}"
FAIL_ON_TEST_FAILURES="${JR_FAIL_ON_TEST_FAILURES:-false}"
RESPONSE_CHAINING_ENABLED="${JR_RESPONSE_CHAINING_ENABLED:-true}"
LOCK="${JR_RUN_LOCK:-$DEFAULT_LOCK}"

java_major_version() {
  "$JAVA_CMD" -version 2>&1 | awk -F'"' '/version/ {
    split($2, parts, ".")
    if (parts[1] == "1") print parts[2]; else print parts[1]
    exit
  }'
}

JAVA_MAJOR="$(java_major_version || true)"
require_java17() {
  if [[ -z "$JAVA_MAJOR" || "$JAVA_MAJOR" -lt 17 ]]; then
    echo "Java 17+ is required to run $JAR."
    echo "Current JR_JAVA_BIN/java: $JAVA_CMD"
    "$JAVA_CMD" -version 2>&1 || true
    echo "Set JR_JAVA_BIN to a Java 17 binary, for example:"
    echo "  export JR_JAVA_BIN=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java"
    exit 1
  fi

  if [[ ! -f "$JAR" ]]; then
    echo "Jar not found: $JAR"
    echo "Build first: ./gradlew clean fatJar"
    exit 1
  fi
}

if [[ -f "$PID" ]] && kill -0 "$(cat "$PID")" 2>/dev/null; then
  if [[ "${1:-start}" == "start" ]]; then
    echo "Already running PID $(cat "$PID")"
    exit 0
  fi
fi

if [[ "${1:-start}" == "start" ]]; then
  require_java17
  : > "$LOG"
  nohup bash -lc "
    exec flock -n '$LOCK' '$JAVA_CMD' -jar '$JAR' \
      --projectRoot=.. \
      --envFile='$ENV_FILE' \
      --mode=execute \
      --threads='$THREADS' \
      --partialReportIntervalSeconds='$PARTIAL_REPORT_INTERVAL_SECONDS' \
      --connectTimeoutMs='$CONNECT_TIMEOUT_MS' \
      --readTimeoutMs='$READ_TIMEOUT_MS' \
      --failOnTestFailures='$FAIL_ON_TEST_FAILURES' \
      --responseChaining.enabled='$RESPONSE_CHAINING_ENABLED'
  " </dev/null >> "$LOG" 2>&1 &
  echo $! > "$PID"
  sleep 2
  if kill -0 "$(cat "$PID")" 2>/dev/null; then
    echo "Started PID $(cat "$PID")"
    exit 0
  fi
  echo "Process exited during startup. Check $LOG"
  tail -n 50 "$LOG" || true
  exit 1
fi

if [[ "${1:-}" == "status" ]]; then
  if [[ -f "$PID" ]] && kill -0 "$(cat "$PID")" 2>/dev/null; then
    echo "RUNNING PID $(cat "$PID")"
  else
    echo "NOT RUNNING"
  fi
  exit 0
fi

if [[ "${1:-}" == "stop" ]]; then
  if [[ -f "$PID" ]] && kill -0 "$(cat "$PID")" 2>/dev/null; then
    kill "$(cat "$PID")"
    for _ in {1..20}; do
      if ! kill -0 "$(cat "$PID")" 2>/dev/null; then
        break
      fi
      sleep 1
    done
    if kill -0 "$(cat "$PID")" 2>/dev/null; then
      kill -9 "$(cat "$PID")" 2>/dev/null || true
    fi
    echo "Stopped PID $(cat "$PID")"
    latest_run="$(ls -1 results 2>/dev/null | sort | tail -n1 || true)"
    if [[ -n "${latest_run:-}" ]]; then
      require_java17
      "$JAVA_CMD" -jar "$JAR" --projectRoot=.. --mode=render --runFolder="$latest_run" >/dev/null 2>&1 || true
      echo "Rendered report from runFolder $latest_run"
    fi
  else
    echo "No running process"
  fi
  exit 0
fi

if [[ "${1:-}" == "tail" ]]; then
  tail -f "$LOG"
  exit 0
fi

if [[ "${1:-}" == "latest-status" ]]; then
  latest_run="$(ls -1 results 2>/dev/null | sort | tail -n1 || true)"
  if [[ -z "${latest_run:-}" ]]; then
    echo "No run folder found"
    exit 1
  fi
  status_file="results/${latest_run}/run-status.json"
  if [[ ! -f "$status_file" ]]; then
    echo "Status file not found: $status_file"
    exit 1
  fi
  cat "$status_file"
  exit 0
fi

echo "Usage: $0 [start|status|stop|tail|latest-status]"
exit 1
