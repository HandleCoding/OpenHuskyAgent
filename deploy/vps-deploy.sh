#!/usr/bin/env bash
# Experimental maintainer script for manually refreshing a VPS checkout.
set -euo pipefail

USE_NOHUP=false

for arg in "$@"; do
  case "$arg" in
    --nohup)
      USE_NOHUP=true
      ;;
    -h|--help)
      echo "Usage: $0 [--nohup]"
      echo "  --nohup  Run 'bin/husky serve' in background with nohup"
      exit 0
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      echo "Usage: $0 [--nohup]" >&2
      exit 1
      ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

stop_existing_husky() {
  local patterns=(
    "java .*${PROJECT_DIR}/service/target/husky-agent-service-.*\\.jar"
    "${PROJECT_DIR}/mvnw spring-boot:run -pl service"
    "${PROJECT_DIR}/bin/husky serve"
  )
  local pids=""

  for pattern in "${patterns[@]}"; do
    local matched
    matched="$(pgrep -f "$pattern" 2>/dev/null || true)"
    if [ -n "$matched" ]; then
      pids="$pids $matched"
    fi
  done

  pids="$(printf '%s\n' $pids | awk '!seen[$0]++')"
  if [ -z "$pids" ]; then
    echo "==> No existing Husky service process found"
    return 0
  fi

  echo "==> Stopping existing Husky service process: $pids"
  kill $pids 2>/dev/null || true

  for _ in $(seq 1 10); do
    local still_running=""
    for pid in $pids; do
      if kill -0 "$pid" 2>/dev/null; then
        still_running="$still_running $pid"
      fi
    done
    if [ -z "$still_running" ]; then
      echo "==> Existing Husky service stopped"
      return 0
    fi
    sleep 1
  done

  echo "==> Force stopping existing Husky service process"
  kill -9 $pids 2>/dev/null || true
}

if [ ! -x ./mvnw ]; then
  chmod +x ./mvnw
fi

if [ ! -x ./bin/husky ]; then
  chmod +x ./bin/husky
fi

echo "==> Pulling latest code"
git pull

echo "==> Building with Maven (skip tests)"
./mvnw install -DskipTests

stop_existing_husky

if [ "$USE_NOHUP" = true ]; then
  echo "==> Starting Husky in background via husky start"
  exec ./bin/husky start
else
  echo "==> Starting Husky in foreground"
  exec ./bin/husky serve
fi
