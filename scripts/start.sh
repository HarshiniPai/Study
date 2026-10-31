#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd "$SCRIPT_DIR/.." && pwd)
PORT=${PORT:-8000}

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required. Install and start Docker Desktop or Docker Engine, then retry." >&2
  exit 1
fi

docker build --tag pm-mvp --file "$PROJECT_ROOT/Dockerfile" "$PROJECT_ROOT"
set -- --rm --detach --name pm-mvp --publish "$PORT:8000" \
  --volume pm-data:/data --env DATABASE_PATH=/data/kanban.sqlite3
if [ -f "$PROJECT_ROOT/.env" ]; then
  set -- "$@" --env-file "$PROJECT_ROOT/.env"
fi
docker run "$@" pm-mvp
printf 'Project Management MVP is running at http://localhost:%s\n' "$PORT"
