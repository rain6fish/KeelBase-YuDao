#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Vendors the KeelBase visual tokens into this repository, and verifies they have not drifted.
#
#   bash scripts/sync-ui-theme.sh          # write the vendored copy
#   bash scripts/sync-ui-theme.sh --check  # verify only; non-zero exit on drift
#
# The tokens are authored once, in Web-Admin-Vue. This repository holds a copy so the host UI can be
# rendered with our visual layer without editing an upstream file. Override the source location with
# KEELBASE_WEB_ADMIN_VUE when it is not a sibling checkout.
#
# 把 KeelBase 视觉 token 收进本仓，并校验它没有漂移。源在 Web-Admin-Vue——token 只在那里写一次。
# 不带参数 = 写入副本；--check = 只校验（漂移即非零退出）。不是同级检出时用
# KEELBASE_WEB_ADMIN_VUE 覆盖源路径。
#
# Line endings are normalised before comparing. This repository is checked out with autocrlf on
# Windows, so comparing raw bytes would report a drift that is not one.
# 比较前先归一换行：本仓在 Windows 上以 autocrlf 检出，直接比原始字节会报出并不存在的漂移。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_ROOT="${KEELBASE_WEB_ADMIN_VUE:-$REPO_ROOT/../KeelBase/Web-Admin-Vue}"
SRC_STYLES="$SRC_ROOT/src/styles"
DEST="$REPO_ROOT/keelbase-ui/theme"

FILES=(
  "element-theme.scss"
  "themes/_blue.scss"
  "themes/_teal.scss"
  "themes/_graphite.scss"
)

CHECK=0
if [[ "${1:-}" == "--check" ]]; then CHECK=1; fi

if [[ ! -d "$SRC_STYLES" ]]; then
  echo "source not found: $SRC_STYLES" >&2
  echo "set KEELBASE_WEB_ADMIN_VUE to the Web-Admin-Vue checkout" >&2
  exit 2
fi

hash_of() { tr -d '\r' < "$1" | md5sum | cut -d' ' -f1; }

drift=0
for f in "${FILES[@]}"; do
  src="$SRC_STYLES/$f"
  dst="$DEST/$f"
  if [[ ! -f "$src" ]]; then
    echo "missing in source: $f" >&2
    exit 2
  fi

  if [[ "$CHECK" == "1" ]]; then
    dst_hash="MISSING"
    if [[ -f "$dst" ]]; then dst_hash="$(hash_of "$dst")"; fi
    if [[ "$(hash_of "$src")" == "$dst_hash" ]]; then
      echo "ok    $f"
    else
      echo "DRIFT $f" >&2
      drift=1
    fi
  else
    mkdir -p "$(dirname "$dst")"
    cp "$src" "$dst"
    echo "wrote $f"
  fi
done

if [[ "$CHECK" == "1" ]]; then
  if [[ "$drift" == "1" ]]; then
    echo "" >&2
    echo "vendored tokens have drifted from Web-Admin-Vue:" >&2
    echo "  edit them in Web-Admin-Vue, then run: bash scripts/sync-ui-theme.sh" >&2
    exit 1
  fi
  echo "vendored tokens match the source."
fi
