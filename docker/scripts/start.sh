#!/usr/bin/env bash
# 启动玲看股息全部容器
set -euo pipefail

cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  echo "[lingkan] 未找到 .env，正在从 .env.example 复制"
  cp .env.example .env
  echo "[lingkan] 请先修改 .env 中的数据库密码，然后重新执行本脚本"
  exit 1
fi

echo "[lingkan] 启动容器..."
docker compose --env-file .env up -d --build

echo "[lingkan] 等待健康检查..."
sleep 5
docker compose ps

echo "[lingkan] 后端健康检查: http://127.0.0.1:${BACKEND_PORT:-8080}/actuator/health"
echo "[lingkan] 完成"
