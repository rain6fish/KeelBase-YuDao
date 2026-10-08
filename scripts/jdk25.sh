#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Resolve a JDK 25 for one command, without touching the machine's default JDK.
#
# Why this exists. The platform in this repository compiles to Java 25, while `keelbase4j-core` and
# this machine's default are on 17. Both have to be usable at once, and on a machine that several
# sessions share, changing JAVA_HOME or PATH would change what everyone else builds with. So this
# resolves 25 for the duration of one command and leaves both alone.
#
# Resolution order — the first that works wins, and nothing is installed unless none of them do:
#
#   1. $KEELBASE_JDK25           an explicit JDK 25 home
#   2. $JDK25_HOME               a conventional name for the same thing
#   3. /d/Develop/jdk-25         where this machine keeps it
#   4. download + unpack         into ${KEELBASE_JDK25_DIR:-/d/Develop}, from the Adoptium API
#
# Usage:
#
#   bash scripts/jdk25.sh java -version
#   bash scripts/jdk25.sh mvn -pl yudao-server -am -DskipTests package
#
# 为**一条命令**解析出一个 JDK 25，**不动机器默认的 JDK**。
#
# 为什么需要它。本仓的平台编译目标是 Java 25，而 `keelbase4j-core` 与本机默认都在 17。两者必须同时可用，
# 而在**多个会话共用**的机器上改 JAVA_HOME 或 PATH 会改变**别人**构建所用的东西。故本脚本只在**一条命令**
# 的期间里解析出 25，两者一律不动。
#
# 解析顺序——**先成的胜出**；四者皆不成才装：
#
#   1. `$KEELBASE_JDK25`         显式给出 JDK 25 的 home
#   2. `$JDK25_HOME`             同一件事的惯用名
#   3. `/d/Develop/jdk-25`       本机放它的地方
#   4. 下载并解包              到 `${KEELBASE_JDK25_DIR:-/d/Develop}`，取自 Adoptium API
#
# 用法：
#
#   bash scripts/jdk25.sh java -version
#   bash scripts/jdk25.sh mvn -pl yudao-server -am -DskipTests package
set -euo pipefail

ADOPTIUM='https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jdk/hotspot/normal/eclipse'

is_jdk25() { # dir
  [ -x "$1/bin/java" ] || return 1
  "$1/bin/java" -version 2>&1 | head -1 | grep -q '"25\.' || return 1
}

resolve() {
  local candidate
  for candidate in "${KEELBASE_JDK25:-}" "${JDK25_HOME:-}" "/d/Develop/jdk-25"; do
    if [ -n "$candidate" ] && is_jdk25 "$candidate"; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

download() {
  local root="${KEELBASE_JDK25_DIR:-/d/Develop}"
  local zip="$root/temurin25.zip" tmp="$root/jdk25-extract"
  echo "no JDK 25 found — downloading into $root" >&2
  mkdir -p "$root" "$tmp"
  curl -fsSL -o "$zip" "$ADOPTIUM"
  unzip -q -o "$zip" -d "$tmp"
  rm -rf "$root/jdk-25"
  mv "$tmp"/* "$root/jdk-25"
  rmdir "$tmp"
  rm -f "$zip"
  is_jdk25 "$root/jdk-25" || { echo "downloaded JDK is not 25 — aborting" >&2; return 1; }
  echo "$root/jdk-25"
}

JDK="$(resolve || download)"

if [ "$#" -eq 0 ]; then
  echo "JDK 25: $JDK"
  echo "JAVA_HOME (unchanged): ${JAVA_HOME:-<unset>}"
  exit 0
fi

# JAVA_HOME for one command; PATH and the machine's default are left as they were.
# 只为一条命令设 JAVA_HOME；PATH 与机器默认保持原样。
exec env JAVA_HOME="$JDK" PATH="$JDK/bin:$PATH" "$@"
