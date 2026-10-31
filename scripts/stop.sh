#!/usr/bin/env sh
set -eu

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required. Install and start Docker Desktop or Docker Engine, then retry." >&2
  exit 1
fi

docker stop pm-mvp
