#!/usr/bin/env bash
# 从备份文件恢复数据库
# 用法: ./restore.sh ../backups/lingkan_20261030_153000.sql
set -euo pipefail

cd "$(dirname "$0")/.."

if [ $# -lt 1 ]; then
  echo "用法: $0 <备份文件路径>"
  exit 1
fi

FILE="$1"
if [ ! -f "$FILE" ]; then
  echo "[lingkan] 备份文件不存在: ${FILE}"
  exit 1
fi

# shellcheck disable=SC1091
source .env

echo "[lingkan] 即将把 ${FILE} 恢复到数据库 ${DB_NAME}（现有同名对象可能报错，请确认是空库或已备份）"
read -r -p "确认继续? (yes/no) " CONFIRM
if [ "$CONFIRM" != "yes" ]; then
  echo "[lingkan] 已取消"
  exit 0
fi

docker compose cp "$FILE" opengauss:/tmp/restore.sql
docker compose exec -T opengauss bash -c \
  "su - omm -c \"gsql -d ${DB_NAME} -p 5432 -f /tmp/restore.sql\""
docker compose exec -T opengauss bash -c "rm -f /tmp/restore.sql"

echo "[lingkan] 恢复完成"
