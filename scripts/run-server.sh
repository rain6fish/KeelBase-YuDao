#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Run the server against this line's datastore, on JDK 25, with everything passed as parameters.
#
#   bash scripts/run-server.sh
#
# Nothing here is written into upstream's `application-local.yaml`: that file stays untouched, and the
# same checkout can be pointed at another stack without editing anything. See KEELBASE-DEV.md for why
# each parameter is needed.
#
# 在 JDK 25 上、对着本线的数据存储跑服务，一切都走参数。
#
#   bash scripts/run-server.sh
#
# 这里没有任何东西写进上游的 `application-local.yaml`：那个文件保持一字未动，同一份检出可以指向另一套栈而
# 不必改任何东西。每个参数为什么需要，见 KEELBASE-DEV.md。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

PORT="${PORT:-48080}"
PG_HOST="${PG_HOST:-127.0.0.1}"
PG_PORT="${PG_PORT:-54329}"
PG_DB="${PG_DB:-ruoyi-vue-pro}"
PG_USER="${PG_USER:-root}"
PG_PASSWORD="${PG_PASSWORD:-password}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-56389}"
AUDIT_KEY="${KEELBASE_AUDIT_KEY:-dev-audit-key-not-for-deployment}"

PG_URL="jdbc:postgresql://$PG_HOST:$PG_PORT/$PG_DB"

exec bash "$HERE/jdk25.sh" java -jar "$ROOT/yudao-server/target/yudao-server.jar" \
  --server.port="$PORT" \
  --spring.flyway.baseline-on-migrate=true --spring.flyway.baseline-version=0 \
  --spring.datasource.dynamic.datasource.master.url="$PG_URL" \
  --spring.datasource.dynamic.datasource.master.username="$PG_USER" \
  --spring.datasource.dynamic.datasource.master.password="$PG_PASSWORD" \
  --spring.datasource.dynamic.datasource.slave.lazy=true \
  --spring.datasource.dynamic.datasource.slave.url="$PG_URL" \
  --spring.datasource.dynamic.datasource.slave.username="$PG_USER" \
  --spring.datasource.dynamic.datasource.slave.password="$PG_PASSWORD" \
  --spring.data.redis.host="$REDIS_HOST" --spring.data.redis.port="$REDIS_PORT" \
  --keelbase.audit.hmac-key="$AUDIT_KEY" \
  --keelbase.wire.exclude-packages=cn.iocoder.yudao \
  --keelbase.memory.max-per-owner="${MEMORY_CAP:-200}" \
  --keelbase.knowledge.chunk-size=800 --keelbase.knowledge.chunk-overlap=120 \
  --yudao.tenant.ignore-tables=products
