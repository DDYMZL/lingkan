#!/usr/bin/env bash
# 停止玲看股息全部容器（保留数据卷）
set -euo pipefail

cd "$(dirname "$0")/.."

docker compose --env-file .env down
echo "[lingkan] 已停止（数据卷保留）"
