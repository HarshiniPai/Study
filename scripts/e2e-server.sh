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
./mvnw -q -DskipTests package
DATABASE_PATH="$TEST_DATA_DIR/kanban.sqlite3" OPENROUTER_API_KEY="" \
  java -jar target/app.jar --server.address=127.0.0.1
