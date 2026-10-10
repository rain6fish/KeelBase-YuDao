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

# KeelBase's model loop is **off unless a key is given**, and that default is the point rather than a
# convenience: the scenario gates assert *which* tool ran (`list_crm_customers`, and so on), which a
# model would turn into a guess. Present a key and the runtime's rule-based planner stands down in
# favour of the model-driven one on `/ai/chat`, and `/ai/task` gains the governed multi-step loop.
# The key is read from the environment, never written here.
# KeelBase 的模型回路**不给 key 就不开**，而这个默认是**要点**、不是图省事：场景门禁断言的是**哪个**工具跑了
# （`list_crm_customers` 之类），换成模型就把这件事变成猜。给了 key，运行时那个规则版 planner 就让位给 `/ai/chat`
# 上的模型版，而 `/ai/task` 也拿到那条受治理的多步循环。key 从环境读，**不写在这里**。
AI_ARGS=()
if [ -n "${KEELBASE_MODEL_API_KEY:-}" ]; then
  # The host's own `application.yaml` switches `spring.ai.chat.client.enabled` **off** on purpose —
  # its comment says the builder wants a single `ChatModel` and the project makes several. That switch
  # is upstream's, and it stays untouched; what turns it back on is this parameter, and only together
  # with a key. If the host does make more than one, the startup fails loudly here rather than the
  # model loop quietly not existing.
  # 宿主自己的 `application.yaml` **故意**关掉了 `spring.ai.chat.client.enabled`——它的注释说 builder 要唯一一个
  # `ChatModel`、而项目会建多个。那个开关是上游的、**一字不动**；把它打开的正是这个参数，且只与 key 同时给。
  # 宿主真建了不止一个的话，这里会**启动失败并说清楚**，而不是让模型回路静静不存在。
  AI_ARGS+=("--spring.ai.chat.client.enabled=true")
  AI_ARGS+=("--spring.ai.openai.api-key=$KEELBASE_MODEL_API_KEY")
  AI_ARGS+=("--spring.ai.openai.base-url=${KEELBASE_MODEL_BASE_URL:-https://api.deepseek.com}")
  AI_ARGS+=("--spring.ai.openai.chat.options.model=${KEELBASE_MODEL_NAME:-deepseek-v4-flash}")
fi

exec bash "$HERE/jdk25.sh" java -jar "$ROOT/yudao-server/target/yudao-server.jar" \
  --server.port="$PORT" \
  --spring.flyway.baseline-on-migrate=true --spring.flyway.baseline-version=0 \
  --spring.datasource.dynamic.datasource.master.type=com.zaxxer.hikari.HikariDataSource \
  --spring.datasource.dynamic.datasource.master.url="$PG_URL" \
  --spring.datasource.dynamic.datasource.master.username="$PG_USER" \
  --spring.datasource.dynamic.datasource.master.password="$PG_PASSWORD" \
  --spring.datasource.dynamic.datasource.slave.lazy=true \
  --spring.datasource.dynamic.datasource.slave.url="$PG_URL" \
  --spring.datasource.dynamic.datasource.slave.username="$PG_USER" \
  --spring.datasource.dynamic.datasource.slave.password="$PG_PASSWORD" \
  --spring.data.redis.host="$REDIS_HOST" --spring.data.redis.port="$REDIS_PORT" \
  --spring.datasource.dynamic.druid.validation-query="SELECT 1" \
  --spring.datasource.druid.validation-query="SELECT 1" \
  --keelbase.audit.hmac-key="$AUDIT_KEY" \
  --keelbase.wire.exclude-packages=cn.iocoder.yudao \
  --keelbase.memory.max-per-owner="${MEMORY_CAP:-200}" \
  --keelbase.knowledge.chunk-size=800 --keelbase.knowledge.chunk-overlap=120 \
  --yudao.tenant.ignore-tables=products \
  --yudao.access-log.enable="${ACCESS_LOG:-true}" \
  ${AI_ARGS[@]+"${AI_ARGS[@]}"}
