#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd "$SCRIPT_DIR/.." && pwd)
TEST_DATA_DIR=$(mktemp -d)

cleanup() {
  rm -rf "$TEST_DATA_DIR"
}
trap cleanup EXIT HUP INT TERM

cd "$PROJECT_ROOT/backend"
DATABASE_PATH="$TEST_DATA_DIR/kanban.sqlite3" \
  uv run uvicorn app.main:app --host 127.0.0.1 --port 8000
