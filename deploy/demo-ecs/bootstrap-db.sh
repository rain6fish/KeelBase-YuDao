#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Create the database this stack runs on, inside the host's existing PostgreSQL container, and load
# the schema into it.
#
#   bash deploy/demo-ecs/bootstrap-db.sh
#   RESET=1 bash deploy/demo-ecs/bootstrap-db.sh    # drop and reload (destructive; says so first)
#
# What goes in, in this order, and why each is a separate file:
#
#   1. `sql/postgresql/ruoyi-vue-pro.sql` — upstream's own script, loaded **as upstream wrote it**.
#      Its header says it is machine-converted from MySQL (it even keeps a `dual` table), so a failure
#      in it is a fact about that conversion, not about this line.
#   2. This line's own additions (`sql/keelbase-*.sql`): the licence-free names the deployment shows, the
#      tables for the modules this line enables but upstream publishes no DDL for, and the console menu
#      rows its pages hang from. **The list below is the source — this prose names no count**, because a
#      count here drifts: it said "five" while the list held nine.
#
# The runtime's own migrations are **not** here: the server applies them itself on startup (Flyway,
# baselined at 0). This script only gets the tables and seed rows in place.
#
# 在宿主既有的 PostgreSQL 容器里建出本栈要用的库，并把 schema 灌进去。
#
#   bash deploy/demo-ecs/bootstrap-db.sh
#   RESET=1 bash deploy/demo-ecs/bootstrap-db.sh    # 删库重灌（破坏性；动手前脚本会说出来）
#
# 灌进去的东西与顺序，以及为什么每个是一个独立文件：
#
#   1. `sql/postgresql/ruoyi-vue-pro.sql` —— 上游自己的脚本，**按上游所写原样**导入。它的文件头写着
#      它是从 MySQL 机器转换来的（甚至留着 `dual` 表），所以它出错是**那份转换**的事实，不是本线的事实。
#   2. 本线自己的增补（`sql/keelbase-*.sql`）：本部署显示的无版权名、本线启用而上游不发布建表脚本的那些模块的
#      表、以及控制台页面挂着的那些菜单行。**清单在下面就是单源 —— 这段散文里不写数目**，因为写数目就会漂：
#      它曾写着「五份」，而清单列着九份。
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
ADDITIONS=(
  "$REPO_ROOT/sql/keelbase-crm.sql"
  "$REPO_ROOT/sql/keelbase-products.sql"
  "$REPO_ROOT/sql/keelbase-ai1.sql"
  "$REPO_ROOT/sql/keelbase-assistant-agent.sql"
  "$REPO_ROOT/sql/keelbase-assistant-role-grants.sql"
  "$REPO_ROOT/sql/keelbase-assistant-menus.sql"
  # 治理控制台的菜单行（`YD-41` 把这份清单补全）：先目录、再授予、再各页。**依赖顺序**：`governance-menus`
  # 建目录 `20001` 与它下面三页；`governance-menu-grants` 把目录与其中两页授予普通角色——**子项没有父项可挂
  # 就进不了侧栏**；其余各页全挂在 `20001` 下，故必须排在目录之后。少了它们，从零重建后整个治理区**一条都到
  # 不了**（页面在产物里、库里没有行）。
  # The governance console's menu rows (`YD-41` completes this list): directory first, then its grants, then
  # the pages. **Dependency order**: `governance-menus` creates the directory `20001` and the three pages
  # under it; `governance-menu-grants` hands the directory and two of those pages to the ordinary role — a
  # child with no parent to hang from stays out of the sidebar; every other page hangs under `20001`, so it
  # must follow the directory. Without them a from-scratch run cannot reach the governance area at all (the
  # pages are in the artefact, the rows are not).
  "$REPO_ROOT/sql/keelbase-governance-menus.sql"
  # 授予：目录 `20001` + 行动中心 + 写入台账，发给普通角色（role 2）。**必须在目录之后**。
  # Grants: the directory `20001` plus the action centre and the write ledger, to the ordinary role (id 2).
  # **Must come after the directory.**
  "$REPO_ROOT/sql/keelbase-governance-menu-grants.sql"
  # 其余治理页（都挂在 `20001` 下）：边界（连同它的授予）· 决策轨迹（连同授予）· 对抗证明 · 工具清单 ·
  # 操作审计 · 策略中心。
  # The remaining governance pages (all under `20001`): boundary (with its grant), conversation trace (with
  # its grant), security showcase, tool inventory, operation audit, policy centre.
  "$REPO_ROOT/sql/keelbase-boundary-menu.sql"
  "$REPO_ROOT/sql/keelbase-trace-menu.sql"
  "$REPO_ROOT/sql/keelbase-showcase-menu.sql"
  "$REPO_ROOT/sql/keelbase-tool-inventory-menu.sql"
  "$REPO_ROOT/sql/keelbase-operation-audit-menu.sql"
  "$REPO_ROOT/sql/keelbase-policy-menu.sql"
  "$REPO_ROOT/sql/keelbase-brand-names.sql"
  "$REPO_ROOT/sql/keelbase-operate-log-nullable.sql"
  "$REPO_ROOT/sql/keelbase-local-images.sql"
)

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
