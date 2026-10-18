#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Create the database this stack runs on, inside the host's existing PostgreSQL container, and load
# the schema into it.
#
#   bash deploy/demo-ecs/bootstrap-db.sh
#   RESET=1 bash deploy/demo-ecs/bootstrap-db.sh    # drop and reload (destructive; says so first)
#   bash deploy/demo-ecs/bootstrap-db.sh --print-additions   # show the `load` list, touch nothing
#
# What goes in, in this order, and why each is a separate file:
#
#   1. `sql/postgresql/ruoyi-vue-pro.sql` — upstream's own script, loaded **as upstream wrote it**.
#      Its header says it is machine-converted from MySQL (it even keeps a `dual` table), so a failure
#      in it is a fact about that conversion, not about this line.
#   2. This line's own additions (`sql/keelbase-*.sql`): the licence-free names the deployment shows, the
#      tables for the modules this line enables but upstream publishes no DDL for, and the console menu
#      rows its pages hang from. **`seed-manifest.txt` is the source — this prose names no count**,
#      because a count here drifts: it said "five" while the list held nine.
#
# The runtime's own migrations are **not** here: the server applies them itself on startup (Flyway,
# baselined at 0). This script only gets the tables and seed rows in place.
#
# 在宿主既有的 PostgreSQL 容器里建出本栈要用的库，并把 schema 灌进去。
#
#   bash deploy/demo-ecs/bootstrap-db.sh
#   RESET=1 bash deploy/demo-ecs/bootstrap-db.sh    # 删库重灌（破坏性；动手前脚本会说出来）
#   bash deploy/demo-ecs/bootstrap-db.sh --print-additions   # 只打印 load 次序，一行不跑
#
# 灌进去的东西与顺序，以及为什么每个是一个独立文件：
#
#   1. `sql/postgresql/ruoyi-vue-pro.sql` —— 上游自己的脚本，**按上游所写原样**导入。它的文件头写着
#      它是从 MySQL 机器转换来的（甚至留着 `dual` 表），所以它出错是**那份转换**的事实，不是本线的事实。
#   2. 本线自己的增补（`sql/keelbase-*.sql`）：本部署显示的无版权名、本线启用而上游不发布建表脚本的那些模块的
#      表、以及控制台页面挂着的那些菜单行。**清单在 `seed-manifest.txt`、它才是单源 —— 这段散文里不写数目**，
#      因为写数目就会漂：它曾写着「五份」，而清单列着九份。
#
# 运行时的迁移**不在这里**：服务启动时自己应用（Flyway，基线 0）。本脚本只负责把表与种子数据放好。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"

# Host's running PostgreSQL container, and the role/database this stack uses. Defaults are the demo
# host's; override for another host.
# 宿主正在跑的 PostgreSQL 容器，以及本栈使用的角色与库。默认值是 demo 机器的；换机器时覆盖。
PG_CONTAINER="${PG_CONTAINER:-keelbase-postgres-1}"
PG_USER="${PG_USER:-front_app}"
PG_DB="${PG_DB:-ruoyi-vue-pro}"

UPSTREAM="$REPO_ROOT/sql/postgresql/ruoyi-vue-pro.sql"
# The list lives in `seed-manifest.txt` beside this file: **every** `sql/keelbase-*.sql` is classified
# there once — `load` (applied by step 4, in that file's order, which is the dependency order),
# `separate` (run by its own step below, because it takes a parameter), or `skip` (with a reason).
# This script reads the `load` lines and nothing else; `scripts/check-seed-list.mjs` holds the manifest
# against the SQL directory, the UI pages, and the keep list below.
#
# 清单住在本文件旁边的 `seed-manifest.txt`：**每一份** `sql/keelbase-*.sql` 都在那里被归类一次 ——
# `load`（第 4 步按那份文件的顺序导入，顺序即依赖顺序）· `separate`（由下面自己的步骤跑，因为它要参数）
# · `skip`（带理由）。本脚本只读 `load` 行；`scripts/check-seed-list.mjs` 拿这份清单与 SQL 目录、
# 界面页面、以及下面的保留清单对账。
MANIFEST="$HERE/seed-manifest.txt"
if [ ! -f "$MANIFEST" ]; then
  echo "⚠ 找不到 $MANIFEST —— 没有清单就不灌增补" >&2
  echo "  $MANIFEST is missing — refusing to load an empty seed list" >&2
  exit 1
fi
ADDITIONS=()
while IFS= read -r name; do
  [ -n "$name" ] || continue
  ADDITIONS+=("$REPO_ROOT/sql/$name")
done < <(awk '$1 == "load" { print $2 }' "$MANIFEST" | tr -d '\r')
if [ "${#ADDITIONS[@]}" -eq 0 ]; then
  echo "⚠ $MANIFEST 里没有 load 行 —— 没有清单就不灌增补" >&2
  echo "  $MANIFEST has no load lines — refusing to load an empty seed list" >&2
  exit 1
fi

# `--print-additions`：只把清单解出来的次序打出来，一行也不执行。给核对与 dry-run 用。
# `--print-additions`: print the order the manifest resolves to and do nothing else. For review and dry runs.
if [ "${1:-}" = "--print-additions" ]; then
  printf '%s\n' "${ADDITIONS[@]}"
  exit 0
fi

# This deployment runs a subset of the modules — the ones its jar actually carries — so the menus
# upstream seeded for the others are turned off. That is a property of this deployment rather than of
# the line, which is why it is a file here and not one under `sql/`.
# 本部署只跑它 jar 里真正有的那些模块，故上游为其余模块播下的菜单要关掉。这是**本部署**的属性、不是
# 这条线的属性，所以它是这里的文件、而不是 `sql/` 下的一个。
SCOPE="$HERE/module-scope.sql"
# The file-storage fix is the line's, not this deployment's: it takes the origin as a parameter, so
# the same file serves any host that runs this stack.
# 文件存储那处修法是**这条线**的、不是本部署的：它把 origin 作为参数收下，故同一个文件也伺候别的跑这套
# 栈的机器。
STORAGE="$REPO_ROOT/sql/keelbase-file-storage.sql"
# Where this deployment is reached — a stored file's URL is built from it.
# 本部署被访问的源——存下来的文件 URL 是由它拼出来的。
ORIGIN="${PUBLIC_ORIGIN:-https://demo2.keelbase.com.cn}"

psql_super() { docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d postgres "$@"; }
psql_db()    { docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" "$@"; }

echo "== 1) 确保库在（$PG_DB @ $PG_CONTAINER）=="
if psql_super -tAc "select 1 from pg_database where datname='$PG_DB'" | grep -q 1; then
  echo "  已存在"
else
  psql_super -v ON_ERROR_STOP=1 -c "CREATE DATABASE \"$PG_DB\" OWNER $PG_USER;" || exit 1
  echo "  已建"
fi

echo "== 2) 向量扩展（知识层的语义路径要靠它）=="
psql_db -v ON_ERROR_STOP=1 -c 'CREATE EXTENSION IF NOT EXISTS vector;' || exit 1
psql_db -tAc "select '  vector ' || extversion from pg_extension where extname='vector';"

if [ "${RESET:-0}" = "1" ]; then
  echo "== 2b) RESET=1 ⇒ 删 schema 重灌（破坏性）=="
  psql_db -v ON_ERROR_STOP=1 -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;' || exit 1
  psql_db -v ON_ERROR_STOP=1 -c 'CREATE EXTENSION IF NOT EXISTS vector;' || exit 1
fi

COUNT="$(psql_db -tAc "select count(*) from information_schema.tables where table_schema='public';")"
if [ "$COUNT" -gt 5 ]; then
  echo "== 3) 已有 schema（$COUNT 张表），跳过导入；要重灌用 RESET=1 =="
  exit 0
fi

echo "== 3) 导入上游 PG schema（$(wc -c < "$UPSTREAM") 字节）=="
docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -q < "$UPSTREAM" || {
  echo "  ⚠ 上游那份是机器转换的（见文件头）；失败处见上" >&2; exit 1; }

echo "== 4) 导入本线的增补（${#ADDITIONS[@]} 份，按序）=="
for f in "${ADDITIONS[@]}"; do
  printf '  %s\n' "$(basename "$f")"
  docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -q < "$f" || {
    echo "  ⚠ $f 导入失败" >&2; exit 1; }
done

echo "== 4b) 把指向厂商文件服务器的行改掉（头像写成绝对 URL，故要传 origin）=="
docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -q \
  -v origin="$ORIGIN" < "$REPO_ROOT/sql/keelbase-local-images.sql" || exit 1

echo "== 5) 收掉本部署不跑的模块的菜单 =="
if [ -f "$SCOPE" ]; then
  docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -q < "$SCOPE" || exit 1
else
  echo "  ⚠ 找不到 $SCOPE —— 菜单不会收，侧栏会出现「模块已禁用」的死页" >&2
fi

echo "== 6) 上传落到一个真能用的存储（master = 数据库存储，origin=$ORIGIN）=="
if [ -f "$STORAGE" ]; then
  docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -q \
    -v origin="$ORIGIN" < "$STORAGE" || exit 1
else
  echo "  ⚠ 找不到 $STORAGE —— 上传仍会落到厂商的示例存储上" >&2
fi

echo "== 7) 收尾 =="
psql_db -tAc "select '  表数 = ' || count(*) from information_schema.tables where table_schema='public';"
psql_db -tAc "select '  租户 1 = ' || name from system_tenant where id=1;"
