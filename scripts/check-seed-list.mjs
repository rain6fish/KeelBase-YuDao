#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// 部署的种子清单**逐份归类**，并与它服务的事实对账。
//
//   node scripts/check-seed-list.mjs
//
// `YD-41` 的由来：控制台的页面是**界面构建时**铺进产物的（`keelbase-ui/host-overlay/`），而它们能不能
// 被点到，取决于 `system_menu` 里**有没有那一行**。库里的行由 `sql/keelbase-*-menu.sql` 落下，而哪些文件
// 会被落下，只写在 `deploy/demo-ecs/seed-manifest.txt` 里。于是存在一种**安静**的失效：页面在产物里、
// 清单里却没有它那一份 —— 部署看着正常，那一页却哪儿也到不了（`YD-27` 的边界页就是这样丢的）。
//
// 这道检查把清单与四处事实对账：
//
//   ⓪ **每份 SQL 都被归类**：`sql/keelbase-*.sql` 的每一份都必须在清单里出现且只出现一次
//      （`load` / `separate` / `skip`），`skip` 必须写理由。新增一份而不归类 ⇒ 红，**不静默忽略**。
//   ① **菜单行 → 清单**：任何一个 `sql/keelbase-*.sql`，只要它给一张 `keelbase-ui/host-overlay/` 里存在的
//      页面插了 `system_menu` 行，它本身就必须是 `load`。少一份 ⇒ 红。
//   ② **顶级路径 → 保留清单**：`load` 落下的任何**顶级**菜单行（`path` 以 `/` 开头），它的路径都必须
//      出现在 `deploy/demo-ecs/module-scope.sql` 的保留清单里 —— 否则一次引导会把那一支**静默**停用。
//   ③ **清单 → 脚本**：`bootstrap-db.sh` 必须从清单取 `load` 行（不再硬编码数组），且每个 `separate`
//      文件的名字都还出现在脚本里（它自己的步骤还在）。
//
// 它读的是**文件**（清单 + SQL + 产物里的 `.vue`），不连库：从零重建才是唯一的终判，但那要一套 PostgreSQL，
// 不适合当常规检查。这里钉住「清单与它服务的东西对不齐」这类**能静态判定**的失效。
//
// 与 `check-console-pages.mjs` 的分工：那个查「`ConsolePages` 里的路由能不能被我们发布的菜单解出来」，
// 这个查「部署的种子清单是否把每一份 SQL 都归了类、且归对了」。两条都在，互不代替。
//
// The deployment's seed manifest **classifies every file**, and is reconciled with what it serves.
//
//   node scripts/check-seed-list.mjs
//
// `YD-41`'s origin: the console's pages are laid into the artefact **when the UI is built**
// (`keelbase-ui/host-overlay/`), and whether they can be clicked depends on whether `system_menu` **has the
// row**. The rows come from `sql/keelbase-*-menu.sql`, and which of those files get applied is written in
// exactly one place: `deploy/demo-ecs/seed-manifest.txt`. So there is a **silent** failure mode: a page is
// in the artefact but its file is not `load` — the deployment looks fine, yet that page is unreachable
// (that is how `YD-27`'s boundary page was lost).
//
// This check reconciles the manifest with four facts:
//
//   ⓪ **every SQL file is classified**: each `sql/keelbase-*.sql` appears in the manifest exactly once
//      (`load` / `separate` / `skip`), and a `skip` carries a reason. A new file left unclassified ⇒ red,
//      never silently ignored.
//   ① **menu rows → manifest**: any `sql/keelbase-*.sql` that inserts a `system_menu` row for a page that
//      exists in `keelbase-ui/host-overlay/` must itself be `load`. One missing ⇒ red.
//   ② **top-level paths → keep list**: any **top-level** menu row (a `path` starting `/`) laid by a `load`
//      file must have its path on the keep list in `deploy/demo-ecs/module-scope.sql` — otherwise a
//      bootstrap **silently** switches that branch off.
//   ③ **manifest → script**: `bootstrap-db.sh` reads the `load` lines from the manifest (no hard-coded
//      array), and every `separate` file's name still appears in the script (its own step still exists).
//
// It reads **files** (the manifest + the SQL + the `.vue` files in the artefact), not a database: a
// from-scratch rebuild is the only final word, but that needs a PostgreSQL, so it is unfit as a routine
// check. This pins the failures that **can** be decided statically.
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const BOOTSTRAP = join('deploy', 'demo-ecs', 'bootstrap-db.sh')
const MANIFEST = join('deploy', 'demo-ecs', 'seed-manifest.txt')
const SCOPE = join('deploy', 'demo-ecs', 'module-scope.sql')
const SQL_DIR = join('sql')
const OVERLAY_VIEWS = join('keelbase-ui', 'host-overlay', 'src', 'views')

const read = (rel) => readFileSync(join(ROOT, rel), 'utf8')

// 一段文本里按序出现的所有字符串字面量。
// Every string literal in a fragment, in order.
const literals = (text) => [...text.matchAll(/'([^']*)'/g)].map((m) => m[1])

// 清单：`<load|separate|skip> <file> [# reason]`，`#` 开头为注释。
// The manifest: `<load|separate|skip> <file> [# reason]`; lines starting `#` are comments.
function manifest() {
  const entries = []
  const bad = []
  read(MANIFEST)
    .split(/\r?\n/)
    .forEach((line, i) => {
      const text = line.trim()
      if (!text || text.startsWith('#')) return
      const m = text.match(/^(load|separate|skip)\s+(\S+\.sql)\s*(?:#\s*(.*))?$/)
      if (!m) {
        bad.push(`${MANIFEST}:${i + 1}: not a manifest line: ${text}`)
        return
      }
      entries.push({ kind: m[1], file: m[2], reason: (m[3] || '').trim(), line: i + 1 })
    })
  return { entries, bad }
}

// `module-scope.sql` 里的保留清单：`coalesce(path, '') not in ('', '/system', …)`。
// The keep list in `module-scope.sql`: `coalesce(path, '') not in ('', '/system', …)`.
function keepList() {
  const m = read(SCOPE).match(/not in\s*\(([\s\S]*?)\)/)
  if (!m) throw new Error(`${SCOPE}: could not find the keep list`)
  return new Set(literals(m[1]))
}

// 产物里每一页的组件路径：`keelbase/governance/BoundaryPage` 等等。
// Component paths of every page in the artefact: `keelbase/governance/BoundaryPage`, etc.
function overlayPages(dir = OVERLAY_VIEWS) {
  const out = new Set()
  const abs = join(ROOT, dir)
  if (!existsSync(abs)) return out
  for (const e of readdirSync(abs, { withFileTypes: true })) {
    const rel = join(dir, e.name)
    if (e.isDirectory()) for (const p of overlayPages(rel)) out.add(p)
    else if (e.name.endsWith('.vue')) out.add(relative(OVERLAY_VIEWS, rel).replace(/\.vue$/, '').split('\\').join('/'))
  }
  return out
}

const sqlFiles = () =>
  readdirSync(join(ROOT, SQL_DIR))
    .filter((e) => e.startsWith('keelbase-') && e.endsWith('.sql'))
    .sort()

// 文本里的各条语句，按 `;` 切（对这些种子文件足够：一条语句以一个 `;` 收尾）。
// Statements in `text`, split on `;` (enough for these seed files: one statement ends in one `;`).
const statements = (text) => text.split(';')

const errors = []

const pages = overlayPages()
const keep = keepList()
const files = new Set(sqlFiles())
const { entries, bad } = manifest()
errors.push(...bad)

// ⓪ 每份 SQL 恰好归类一次；清单点名的文件必须存在；skip 必须有理由（中英各一块，用 ` | ` 分隔）。
// ⓪ each SQL file is classified exactly once; manifest entries name real files; a skip carries a reason
// (one block per language, separated by ` | `).
const classified = new Map()
for (const e of entries) {
  if (classified.has(e.file)) {
    errors.push(`${MANIFEST}:${e.line}: ${e.file} is classified twice (first at line ${classified.get(e.file).line})`)
  } else {
    classified.set(e.file, e)
  }
  if (!files.has(e.file)) errors.push(`${MANIFEST}:${e.line}: names sql/${e.file}, which does not exist`)
  if (e.kind === 'skip') {
    if (!e.reason) errors.push(`${MANIFEST}:${e.line}: ${e.file} is skipped without a reason — an unclassified file in disguise`)
    else if (!e.reason.includes(' | ')) errors.push(`${MANIFEST}:${e.line}: ${e.file}'s skip reason is not two language blocks separated by " | "`)
  }
}
for (const f of files) {
  if (!classified.has(f)) {
    errors.push(`sql/${f} is not classified in ${MANIFEST} (load / separate / skip + reason) — a new seed file must not be ignored silently`)
  }
}

const load = entries.filter((e) => e.kind === 'load').map((e) => e.file)
const loadSet = new Set(load)
const separate = entries.filter((e) => e.kind === 'separate').map((e) => e.file)

// 本部署必须落下的菜单文件（`YD-41` 的验收面）。overlay 规则会抓到其中大多数；这一份是显式底线。
// The menu files this deployment must lay (YD-41's acceptance surface). The overlay rule catches most of
// them; this list is the explicit floor.
const REQUIRED_IN_LOAD = [
  'keelbase-governance-menus.sql',
  'keelbase-governance-menu-grants.sql',
  'keelbase-boundary-menu.sql',
  'keelbase-trace-menu.sql',
  'keelbase-showcase-menu.sql',
  'keelbase-tool-inventory-menu.sql',
  'keelbase-operation-audit-menu.sql',
  'keelbase-policy-menu.sql',
  'keelbase-assistant-role-grants.sql',
  'keelbase-assistant-menus.sql',
]
for (const f of REQUIRED_IN_LOAD) {
  if (!loadSet.has(f)) errors.push(`${MANIFEST}: ${f} must be load — this deployment's pages need it`)
}

// 依赖顺序：父项必须先于子项/授予。
// Dependency order: the parent must precede its children and grants.
const BEFORE = [
  ['keelbase-ai1.sql', 'keelbase-assistant-agent.sql'],
  ['keelbase-governance-menus.sql', 'keelbase-governance-menu-grants.sql'],
  ['keelbase-governance-menus.sql', 'keelbase-boundary-menu.sql'],
  ['keelbase-governance-menus.sql', 'keelbase-trace-menu.sql'],
  ['keelbase-governance-menus.sql', 'keelbase-showcase-menu.sql'],
  ['keelbase-governance-menus.sql', 'keelbase-tool-inventory-menu.sql'],
  ['keelbase-governance-menus.sql', 'keelbase-operation-audit-menu.sql'],
  ['keelbase-governance-menus.sql', 'keelbase-policy-menu.sql'],
]
for (const [first, second] of BEFORE) {
  const i = load.indexOf(first)
  const j = load.indexOf(second)
  if (i >= 0 && j >= 0 && i > j) errors.push(`${MANIFEST}: ${first} must precede ${second} (dependency order)`)
}

// ① 菜单行 → 清单。
// ① menu rows → manifest.
for (const f of files) {
  const text = read(join(SQL_DIR, f))
  const seedsOverlay = statements(text).some(
    (s) => /insert\s+into\s+system_menu/i.test(s) && literals(s).some((v) => pages.has(v)),
  )
  if (seedsOverlay && !loadSet.has(f)) {
    errors.push(`sql/${f} seeds a system_menu row for a keelbase-ui page but is not load in ${MANIFEST}`)
  }
}

// ② 顶级路径 → 保留清单。
// ② top-level paths → keep list.
for (const f of load) {
  const text = read(join(SQL_DIR, f))
  for (const s of statements(text)) {
    if (!/insert\s+into\s+system_menu/i.test(s)) continue
    for (const v of literals(s)) {
      if (v.startsWith('/') && !keep.has(v)) {
        errors.push(`sql/${f} lays top-level menu path ${v}, which ${SCOPE} does not keep`)
      }
    }
  }
}

// ③ 清单 → 脚本：脚本从清单取 load 行，且 separate 的每一步还在。
// ③ manifest → script: the script takes the load lines from the manifest, and every separate step exists.
const bootstrap = read(BOOTSTRAP)
if (!bootstrap.includes('seed-manifest.txt') || !/MANIFEST=/.test(bootstrap)) {
  errors.push(`${BOOTSTRAP} does not read ${MANIFEST} — the manifest must be the single source`)
}
if (/ADDITIONS=\(\s*["']?\$REPO_ROOT\/sql\/keelbase-/.test(bootstrap)) {
  errors.push(`${BOOTSTRAP} still hard-codes keelbase entries in ADDITIONS — read them from ${MANIFEST}`)
}
for (const f of separate) {
  if (!bootstrap.includes(f)) errors.push(`${MANIFEST}: sql/${f} is separate, but ${BOOTSTRAP} never names it — its own step is gone`)
}

const skips = entries.filter((e) => e.kind === 'skip').length

if (errors.length) {
  console.error(`${errors.length} 处种子清单与部署需要不一致 / mismatch(es) between the seed manifest and what this deployment needs:\n`)
  for (const e of errors) console.error(`  ✗ ${e}`)
  console.error(`\n清单在 ${MANIFEST}；见 YD-41。\nThe manifest is ${MANIFEST}; see YD-41.`)
  process.exit(1)
}

console.log(
  `\u2713 ${load.length} 份 load · ${separate.length} 份 separate · ${skips} 份 skip · ${pages.size} 张 keelbase-ui 页面 · 每份 SQL 已归类且对齐` +
    `\n  ${load.length} load \u00b7 ${separate.length} separate \u00b7 ${skips} skip \u00b7 ${pages.size} keelbase-ui pages \u00b7 every SQL file classified and reconciled`,
)
