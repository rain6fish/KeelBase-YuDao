#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# One command to re-run the generator after changing a Spec. The generator itself is Java
# (`keelbase-codegen`); this is only the launcher, for the same reason `jdk25.sh` and `run-server.sh`
# exist — so the documented flow is one line rather than a Maven invocation nobody remembers.
#
#   bash scripts/gen-from-spec.sh specs/products.json
#   bash scripts/gen-from-spec.sh specs/products.json --check
#   bash scripts/gen-from-spec.sh --check-boundary
#
# It resolves JDK 25 for the one command, runs from the repository root, and passes the arguments
# through untouched.
#
# 改完 Spec 后一条命令重跑生成器。生成器本体是 Java（`keelbase-codegen`），这里只是启动器——`jdk25.sh` 与
# `run-server.sh` 存在的理由相同：让文档化的流程是一行，而不是一条没人记得住的 Maven 调用。
#
#   bash scripts/gen-from-spec.sh specs/products.json
#   bash scripts/gen-from-spec.sh specs/products.json --check
#   bash scripts/gen-from-spec.sh --check-boundary
#
# 它为这一条命令解析 JDK 25、在仓库根运行、参数原样透传。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."

# `compile` is named explicitly: `exec:java` runs whatever is in `target/classes` and does not build it,
# so without this the first run after a checkout fails with ClassNotFoundException.
# `compile` 是**点名**的：`exec:java` 只跑 `target/classes` 里已有的东西、不会先构建，故没有它，
# 一次全新检出后的第一次运行会以 ClassNotFoundException 失败。
exec bash "$HERE/jdk25.sh" mvn -q -pl keelbase-codegen compile exec:java -Dexec.args="$*"
