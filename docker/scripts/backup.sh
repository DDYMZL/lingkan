#!/usr/bin/env bash
# 备份 openGauss 数据库到 ./backups
set -euo pipefail

cd "$(dirname "$0")/.."

# shellcheck disable=SC1091
source .env
BACKUP_DIR="../backups"
mkdir -p "$BACKUP_DIR"
STAMP=$(date +%Y%m%d_%H%M%S)
FILE="${BACKUP_DIR}/lingkan_${STAMP}.sql"

echo "[lingkan] 导出数据库到 ${FILE}"
docker compose exec -T opengauss bash -c \
  "su - omm -c \"gs_dump -U ${DB_USER} -W '${DB_PASSWORD}' -f /tmp/export.sql -F p ${DB_NAME}\""

docker compose cp opengauss:/tmp/export.sql "$FILE"
docker compose exec -T opengauss bash -c "rm -f /tmp/export.sql"

echo "[lingkan] 备份完成: ${FILE}"
