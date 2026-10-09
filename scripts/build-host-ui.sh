#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Builds the host's admin UI and lays the KeelBase visual layer over the built page — without editing
# a single upstream file.
#
# The order is the point. The UI is built from an untouched checkout; then the vendored tokens are
# compiled and linked into the built page **last**, so they override the app's own stylesheets. The
# checkout is never modified — the visual layer exists only in the artefact.
#
#   bash scripts/build-host-ui.sh              # fetch if needed, install, build, inject
#   bash scripts/build-host-ui.sh --skip-build # inject into an existing out dir (no install/build)
#
# 构建宿主的后台界面，并把 KeelBase 视觉层铺到**构建产物**上——不改任何上游文件。
# 顺序是关键：先在**未改动**的检出上构建；随后编译入库的令牌，并**最后**链进构建好的页面，
# 从而覆盖应用自己的样式表。检出始终不动——视觉层只存在于产物里。
#   --skip-build = 在已有的产物目录上做注入（不装依赖、不构建）。
#
# Two facts differ from the RuoYi host, and both shape this script:
#
#   1. The admin UI is not in this repository. Upstream keeps `yudao-ui/yudao-ui-admin-vue3` as a
#      placeholder directory (a README and a few leftovers); the real tree lives in its own repository.
#      So this script fetches it. The checkout goes under `target/`, which upstream's .gitignore
#      already ignores — nothing is added to any tracked file to keep it out of the index.
#   2. The app writes its own theme colour onto `<html>` as an **inline style** at mount
#      (`ConfigGlobal` → `appStore.setCssVarTheme()`), and an inline style beats any stylesheet on
#      its own. A last-linked stylesheet therefore carries everything *except* the primary colour
#      family. `!important` does beat a non-important inline style, so the compiled tokens have it
#      added to that family alone — the rest of the layer stays overridable at runtime.
#
# 有两条与 RuoYi 宿主不同的事实，它们决定了这个脚本的形状：
#   1. **界面不在本仓**——上游把 `yudao-ui/yudao-ui-admin-vue3` 留成占位目录（一个 README 加几件残留），
#      真正的树在它自己的仓里。故本脚本按需拉取，检出落在 `target/`（上游 `.gitignore` 已忽略）——
#      **不为把它挡在索引外而改动任何受跟踪文件**。
#   2. 应用在挂载时把自己的主题色写成 `<html>` 的**内联样式**，而内联样式本身就压过任何样式表。
#      于是「最后链一个 CSS」能带上除**主色族**以外的一切；`!important` 能压过非重要的内联样式，
#      所以只给该族加上它——其余层保持在运行时可被覆盖。
#
# The tokens themselves are vendored by scripts/sync-ui-theme.sh; edit them in Web-Admin-Vue, never
# here. 令牌由 scripts/sync-ui-theme.sh 入库；要改请改 Web-Admin-Vue，不要改这里。
#
# Overridable:
#   KEELBASE_YUDAO_UI        checkout location   (default <repo>/target/yudao-ui-admin-vue3)
#   KEELBASE_YUDAO_UI_REPO   clone URL           (default the Gitee mirror)
#   KEELBASE_YUDAO_UI_REF    branch/tag to clone  (default the remote's default branch)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UI_DIR="${KEELBASE_YUDAO_UI:-$REPO_ROOT/target/yudao-ui-admin-vue3}"
UI_REPO="${KEELBASE_YUDAO_UI_REPO:-https://gitee.com/yudaocode/yudao-ui-admin-vue3.git}"
UI_REF="${KEELBASE_YUDAO_UI_REF:-}"
BUILD_SCRIPT="${KEELBASE_YUDAO_UI_BUILD:-build:prod}"
OUT_NAME="${KEELBASE_YUDAO_UI_OUT:-dist-prod}"
PNPM_VERSION="${KEELBASE_YUDAO_UI_PNPM:-9.15.9}"
REGISTRY="${KEELBASE_YUDAO_UI_REGISTRY:-https://registry.npmmirror.com}"

THEME_SCSS="$REPO_ROOT/keelbase-ui/theme/element-theme.scss"
THEME_CSS_NAME="keelbase-theme.css"
MARKER="<!-- keelbase-theme -->"

SKIP_BUILD=0
if [[ "${1:-}" == "--skip-build" ]]; then SKIP_BUILD=1; fi

if [[ ! -f "$THEME_SCSS" ]]; then
  echo "vendored tokens missing: $THEME_SCSS" >&2
  echo "run: bash scripts/sync-ui-theme.sh" >&2
  exit 2
fi

# --- 1. the checkout ------------------------------------------------------------------------------
if [[ "$SKIP_BUILD" == "0" && ! -d "$UI_DIR" ]]; then
  echo "fetching the admin UI into $UI_DIR (upstream keeps it out of this repository)…"
  mkdir -p "$(dirname "$UI_DIR")"
  clone_args=(--depth 1)
  if [[ -n "$UI_REF" ]]; then clone_args+=(--branch "$UI_REF"); fi
  git clone "${clone_args[@]}" "$UI_REPO" "$UI_DIR"
fi

if [[ ! -d "$UI_DIR" ]]; then
  echo "no checkout at $UI_DIR — run without --skip-build, or set KEELBASE_YUDAO_UI" >&2
  exit 2
fi

UI_SHA="$(git -C "$UI_DIR" rev-parse --short HEAD 2>/dev/null || echo 'unknown')"
echo "admin UI checkout: $UI_DIR @ $UI_SHA"

# --- 2. build the app, from an untouched tree ------------------------------------------------------
if [[ "$SKIP_BUILD" == "0" ]]; then
  # The upstream tree ships a pnpm lockfile (v9) and no npm one, so pnpm is the faithful package
  # manager — and it is pinned to the major that lockfile belongs to. This machine's corepack default
  # is pnpm 12, which refuses this lockfile twice over: it rejects the 159 tarball URLs the lockfile
  # records against the npmmirror host (ERR_PNPM_TARBALL_URL_MISMATCH — and by its own wording it
  # refuses "whatever the configured policies"), and it blocks dependency build scripts outright
  # (ERR_PNPM_IGNORED_BUILDS, esbuild among them). Pinning avoids both instead of special-casing each.
  # The registry is still passed on the install, because the mirror is what the lockfile names and it
  # is markedly faster from here; npm stays the fallback, not the first choice.
  # 上游只带 pnpm 的 lockfile（v9）、不带 npm 的，故 pnpm 才是忠实的包管理器，并钉在属于那份 lockfile
  # 的大版本上。本机 corepack 默认是 pnpm 12，它对这份 lockfile 有两道拒绝：一，lockfile 把 159 个
  # tarball 记在 npmmirror 主机上，它认为与 registry 发布的元数据不符而拒
  # （ERR_PNPM_TARBALL_URL_MISMATCH，且按其原文「无论配了什么策略」都拒）；二，它默认禁止依赖的构建
  # 脚本（ERR_PNPM_IGNORED_BUILDS，esbuild 在其中）。钉版本一次避开两道，不必逐个特判。装依赖时仍显式
  # 传 registry——那面镜像是 lockfile 自己记的，且从这里快得多；npm 只作兜底，不作首选。
  # corepack asks before downloading a package manager; there is no one to answer here.
  export COREPACK_ENABLE_DOWNLOAD_PROMPT=0
  if command -v corepack >/dev/null 2>&1; then PM=(corepack "pnpm@$PNPM_VERSION"); else PM=(pnpm); fi
  if ! "${PM[@]}" --version >/dev/null 2>&1; then
    echo "no pnpm — falling back to npm (the lockfile is pnpm's, so the tree may resolve differently)" >&2
    PM=(npm)
  fi

  # Unconditional, not gated on node_modules existing: a checkout whose last install died partway
  # leaves a node_modules that looks present and is not usable, and the failure surfaces only later,
  # as a missing module during the build. pnpm makes a satisfied install a no-op.
  echo "installing the app's dependencies (this is upstream's own toolchain)…"
  ( cd "$UI_DIR" && "${PM[@]}" --registry="$REGISTRY" install )
  ( cd "$UI_DIR" && "${PM[@]}" run "$BUILD_SCRIPT" )
fi

DIST="$UI_DIR/$OUT_NAME"
INDEX="$DIST/index.html"
if [[ ! -f "$INDEX" ]]; then
  echo "no built page at $INDEX — run without --skip-build, or set KEELBASE_YUDAO_UI_OUT" >&2
  exit 2
fi

# --- 3. find a sass to compile the tokens with -----------------------------------------------------
# the UI's own toolchain first (step 2 installed it anyway), then the sibling checkout the tokens are
# authored in, then whatever is on PATH.
SASS=""
for candidate in \
  "$UI_DIR/node_modules/.bin/sass" \
  "$REPO_ROOT/../KeelBase/Web-Admin-Vue/node_modules/.bin/sass"
do
  if [[ -x "$candidate" ]]; then SASS="$candidate"; break; fi
done
if [[ -z "$SASS" ]] && command -v sass >/dev/null 2>&1; then SASS="$(command -v sass)"; fi
if [[ -z "$SASS" ]]; then
  echo "no sass found — install the UI's dependencies (see the checkout's own install)" >&2
  exit 2
fi

# --- 4. compile, then mark the primary family as unoverridable-at-mount ----------------------------
# Fact 2 in the header: only the primary family is written inline by the app at mount, so only it
# needs `!important`. The transform is deliberately narrow — it rewrites declarations of
# `--el-color-primary` / `-light-N` / `-dark-N` and nothing else. The tokens never reference the
# family inside `var()`, so a line-level rewrite cannot catch a value by accident.
raw="$(mktemp)"
tmp=""
trap 'rm -f "$raw" ${tmp:+"$tmp"}' EXIT
"$SASS" "$THEME_SCSS" "$raw"
awk '
  /^[ \t]*--el-color-primary(-light-[0-9]+|-dark-[0-9]+)?[ \t]*:[^;]*;[ \t]*$/ { sub(/;[ \t]*$/, " !important;") }
  { print }
' "$raw" > "$DIST/$THEME_CSS_NAME"

got="$(grep -c '^[ \t]*--el-color-primary.*!important;' "$DIST/$THEME_CSS_NAME" || true)"
want="$(grep -c '^[ \t]*--el-color-primary' "$raw" || true)"
if [[ "$got" != "$want" ]]; then
  echo "expected $want primary declarations to carry !important, found $got" >&2
  exit 1
fi
echo "compiled $THEME_CSS_NAME ($want primary declarations pinned)"

# --- 5. link it into the built page, last, idempotently ---------------------------------------------
# Any block we wrote before is dropped first, so re-running does not stack links.
tmp="$(mktemp)"
awk -v css="$THEME_CSS_NAME" -v marker="$MARKER" '
  $0 ~ marker { getline; next }                       # drop the marker and the line we put under it
  {
    if (!done && $0 ~ /<\/head>/) {
      print "    " marker
      print "    <link rel=\"stylesheet\" href=\"" css "\" />"
      done = 1
    }
    print
  }
' "$INDEX" > "$tmp"
mv "$tmp" "$INDEX"

# --- 6. assert, rather than report ------------------------------------------------------------------
count="$(grep -c -- "$MARKER" "$INDEX" || true)"
if [[ "$count" != "1" ]]; then
  echo "expected exactly one theme link in $INDEX, found $count" >&2
  exit 1
fi
if ! grep -q -- "$THEME_CSS_NAME" "$INDEX"; then
  echo "theme link missing from $INDEX" >&2
  exit 1
fi

echo "linked $THEME_CSS_NAME into $INDEX"
echo "note: the layer is in the artefact only — the UI's own dev server renders upstream's look."
