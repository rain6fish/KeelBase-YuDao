#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// 本部署实际需要的菜单行，与部署自己的种子清单**对得齐**。
//
//   node scripts/check-seed-list.mjs
//
// `YD-41` 的由来：控制台的页面是**界面构建时**铺进产物的（`keelbase-ui/host-overlay/`），而它们能不能
// 被点到，取决于 `system_menu` 里**有没有那一行**。库里的行由 `sql/keelbase-*-menu.sql` 落下，而哪些文件
// 会被落下，只写在 `deploy/demo-ecs/bootstrap-db.sh` 的 `ADDITIONS` 里。于是存在一种**安静**的失效：页面在
// 产物里、清单里却没有它那一份 —— 部署看着正常，那一页却哪儿也到不了（`YD-27` 的边界页就是这样丢的）。
//
// 这道检查把那份清单与它服务的两处事实对账，**两个方向**：
//
//   ① **菜单行 → 清单**：任何一个 `sql/keelbase-*.sql`，只要它给一张 `keelbase-ui/host-overlay/` 里存在的
//      页面插了 `system_menu` 行，它本身就必须出现在 `ADDITIONS` 里。少一份 ⇒ 红。
//   ② **顶级路径 → 保留清单**：`ADDITIONS` 里落下的任何**顶级**菜单行（`path` 以 `/` 开头），它的路径都必须
//      出现在 `deploy/demo-ecs/module-scope.sql` 的保留清单里 —— 否则一次引导会把那一支**静默**停用。
//
// 它读的是**文件**（清单 + SQL + 产物里的 `.vue`），不连库：从零重建才是唯一的终判，但那要一套 PostgreSQL，
// 不适合当常规检查。这里只钉住「清单漏了一份」这条**能静态判定**的失效。
//
// 与 `check-console-pages.mjs` 的分工：那个查「`ConsolePages` 里的路由能不能被我们发布的菜单解出来」，
// 这个查「我们这个部署的种子清单与它要落的东西对不对齐」。两条都在，互不代替。
//
// The menu rows this deployment needs line up with the deployment's own seed list.
//
//   node scripts/check-seed-list.mjs
//
// `YD-41`'s origin: the console's pages are laid into the artefact **when the UI is built**
// (`keelbase-ui/host-overlay/`), and whether they can be clicked depends on whether `system_menu` **has the
// row**. The rows come from `sql/keelbase-*-menu.sql`, and which of those files get applied is written in
// exactly one place: `ADDITIONS` in `deploy/demo-ecs/bootstrap-db.sh`. So there is a **silent** failure mode:
// a page is in the artefact but its file is not in the list — the deployment looks fine, yet that page is
// unreachable (that is how `YD-27`'s boundary page was lost).
//
// This check reconciles the list against the two facts it serves, **both directions**:
//
//   ① **menu rows → list**: any `sql/keelbase-*.sql` that inserts a `system_menu` row for a page that exists
//      in `keelbase-ui/host-overlay/` must itself appear in `ADDITIONS`. One missing ⇒ red.
//   ② **top-level paths → keep list**: any **top-level** menu row (a `path` starting with `/`) laid by an
//      `ADDITIONS` file must have its path on the keep list in `deploy/demo-ecs/module-scope.sql` — otherwise
//      a bootstrap **silently** switches that branch off.
//
// It reads **files** (the list + the SQL + the `.vue` files in the artefact), not a database: a from-scratch
// rebuild is the only final word, but that needs a PostgreSQL, so it is unfit as a routine check. This pins
// the one failure that **can** be decided statically: the list is missing a file.
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const BOOTSTRAP = join('deploy', 'demo-ecs', 'bootstrap-db.sh')
const SCOPE = join('deploy', 'demo-ecs', 'module-scope.sql')
const OVERLAY_VIEWS = join('keelbase-ui', 'host-overlay', 'src', 'views')

const read = (rel) => readFileSync(join(ROOT, rel), 'utf8')

// 一段文本里按序出现的所有字符串字面量。
// Every string literal in a fragment, in order.
const literals = (text) => [...text.matchAll(/'([^']*)'/g)].map((m) => m[1])

// `ADDITIONS=( … )` —— 部署自己的种子清单。返回它点名的 `sql/<name>` 文件名集合。
// `ADDITIONS=( … )` — the deployment's own seed list. Returns the `sql/<name>` basenames it names.
function additions() {
  const block = read(BOOTSTRAP).match(/ADDITIONS=\(([\s\S]*?)\n\)/)
  if (!block) throw new Error(`${BOOTSTRAP}: could not find the ADDITIONS block`)
  return new Set([...block[1].matchAll(/\$REPO_ROOT\/sql\/([\w.-]+)/g)].map((m) => m[1]))
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

const menuSqlFiles = () =>
  readdirSync(join(ROOT, 'sql'))
    .filter((e) => e.startsWith('keelbase-') && e.endsWith('.sql'))
    .map((e) => join('sql', e))

// 文本里的各条语句，按 `;` 切（对这些种子文件足够：一条语句以一个 `;` 收尾）。
// Statements in `text`, split on `;` (enough for these seed files: one statement ends in one `;`).
const statements = (text) => text.split(';')

const errors = []

const pages = overlayPages()
const list = additions()
const keep = keepList()

// 清单点名的每一份都必须存在 —— 清单里一个笔误，就是同一类静默丢页。
// Every entry the list names has to exist — a typo in the list is the same class of silent loss.
for (const f of list) {
  if (!existsSync(join(ROOT, 'sql', f))) errors.push(`ADDITIONS names sql/${f}, which does not exist`)
}

// ① 菜单行 → 清单。
// ① menu rows → list.
for (const rel of menuSqlFiles()) {
  const text = read(rel)
  const base = rel.slice('sql/'.length)
  const seedsOverlay = statements(text).some(
    (s) => /insert\s+into\s+system_menu/i.test(s) && literals(s).some((v) => pages.has(v)),
  )
  if (seedsOverlay && !list.has(base)) {
    errors.push(`${rel} seeds a system_menu row for a keelbase-ui page but is not in ADDITIONS (${BOOTSTRAP})`)
  }
}

// ② 顶级路径 → 保留清单。
// ② top-level paths → keep list.
for (const base of list) {
  if (!base.endsWith('.sql')) continue
  const text = read(join('sql', base))
  for (const s of statements(text)) {
    if (!/insert\s+into\s+system_menu/i.test(s)) continue
    for (const v of literals(s)) {
      if (v.startsWith('/') && !keep.has(v)) {
        errors.push(`sql/${base} lays top-level menu path ${v}, which ${SCOPE} does not keep`)
      }
    }
  }
}

if (errors.length) {
  console.error(`${errors.length} 处种子清单与部署需要不一致 / mismatch(es) between the seed list and what this deployment needs:\n`)
  for (const e of errors) console.error(`  ✗ ${e}`)
  console.error(`\n清单在 ${BOOTSTRAP}；见 YD-41。\nThe list is in ${BOOTSTRAP}; see YD-41.`)
  process.exit(1)
}

console.log(
  `\u2713 ${list.size} 份增补 · ${pages.size} 张 keelbase-ui 页面 · 清单与部署需要已对齐` +
    `\n  ${list.size} additions \u00b7 ${pages.size} keelbase-ui pages \u00b7 list and deployment needs agree`,
)
