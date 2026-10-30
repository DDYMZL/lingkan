#!/usr/bin/env bash
# 重启玲看股息全部容器
set -euo pipefail

cd "$(dirname "$0")/.."

docker compose --env-file .env restart
docker compose ps
