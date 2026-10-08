#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Bring up this line's datastore and load the platform's schema into it.
#
#   bash scripts/bootstrap-datastore.sh          # create the database if absent, load if empty
#   RESET=1 bash scripts/bootstrap-datastore.sh  # drop and reload (destructive, says so)
#
# The schema is upstream's `sql/postgresql/ruoyi-vue-pro.sql`, loaded **as upstream wrote it**. One
# thing about that file is worth knowing before reading a failure: it is **machine-converted** from
# MySQL — its header says so, its date is 2026-09-20, and it carries leftovers like a `dual` table.
# So a failure in here is a fact about the converted script, not about this line.
#
# 起本线的数据存储，并把平台的 schema 灌进去。
#
#   bash scripts/bootstrap-datastore.sh          # 库不在就建，空就灌
#   RESET=1 bash scripts/bootstrap-datastore.sh  # 删库重灌（破坏性，脚本会说出来）
#
# schema 是上游的 `sql/postgresql/ruoyi-vue-pro.sql`，**按上游所写原样**导入。读失败信息之前有一件事值得先知道：
# 那个文件是**从 MySQL 机器转换**来的——它的文件头自己写着、日期是 2026-09-20，还带着 `dual` 这样的残留。
# 所以这里出错，是**那份转换脚本**的事实，不是本线的事实。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE="$HERE/docker-compose.datastore.yml"
SCHEMA="$HERE/../sql/postgresql/ruoyi-vue-pro.sql"
PROJECT=keelbase-yudao
PG=keelbase-yudao-pg
# ⚠ 缓存容器叫 `-cache`：机器上已有一个 `keelbase-yudao-redis`（别人的、非 compose 建的），显式名会撞。
# The cache container is named `-cache`: a `keelbase-yudao-redis` already existed on this machine.
DB=ruoyi-vue-pro
USER=root

psql_() { docker exec -i -e PGPASSWORD=password "$PG" psql -U "$USER" -d "$@" ; }
psql_db() { psql_ "$DB" "$@" ; }

echo "== 1) 起栈（compose -p $PROJECT）=="
docker compose -p "$PROJECT" -f "$COMPOSE" up -d || exit 1

echo "== 2) 等 PG 就绪 =="
for i in $(seq 1 60); do
  if docker exec "$PG" pg_isready -U "$USER" -d "$DB" >/dev/null 2>&1; then echo "  ready after ${i}s"; break; fi
  sleep 1
  [ "$i" = 60 ] && { echo "  PG 未就绪，退出"; exit 1; }
done

echo "== 3) 向量扩展（知识层的语义路径要靠它）=="
psql_db -v ON_ERROR_STOP=1 -c 'CREATE EXTENSION IF NOT EXISTS vector;' || exit 1
psql_db -tAc "select 'vector ' || extversion from pg_extension where extname='vector';"

if [ "${RESET:-0}" = "1" ]; then
  echo "== 3b) RESET=1 ⇒ 删表重灌（破坏性）=="
  psql_db -v ON_ERROR_STOP=1 -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;' || exit 1
fi

COUNT="$(psql_db -tAc "select count(*) from information_schema.tables where table_schema='public';")"
echo "== 4) 现有表数 = $COUNT =="
if [ "$COUNT" -gt 5 ]; then
  echo "  已有 schema，跳过导入（要重灌用 RESET=1）"
else
  echo "== 4b) 导入上游 PG schema（$(wc -c < "$SCHEMA") 字节）=="
  docker exec -i -e PGPASSWORD=password "$PG" psql -U "$USER" -d "$DB" -v ON_ERROR_STOP=1 < "$SCHEMA"
  RC=$?
  if [ "$RC" != "0" ]; then
    echo "  ⚠ 导入在第 $RC 处失败——**记录下来**：上游那份是机器转换的（见文件头）" >&2
    exit "$RC"
  fi
  echo "  导入完成，表数 = $(psql_db -tAc "select count(*) from information_schema.tables where table_schema='public';")"
fi

echo "== 5) 收尾 =="
docker compose -p "$PROJECT" -f "$COMPOSE" ps --format '{{.Name}}  {{.Status}}' 2>/dev/null
