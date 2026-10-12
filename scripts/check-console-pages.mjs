#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// Every page the assistant offers is a page the menus we ship can resolve.
//
//   node scripts/check-console-pages.mjs
//
// `ConsolePages` is a curated list, and a curated list is a second place to forget. What makes it
// safe is this: each route it names is looked up in the menu tree the SQL we ship defines, composed
// the way the console's router composes it — a parent's path joined to a child's. A page that moves
// or is renamed upstream turns this red rather than leaving a route in the tool that goes nowhere,
// which is the failure mode a list like this has and a runtime lookup would not.
//
// Scope is stated rather than implied: it reads our own `ConsolePages.java` for the routes, and the
// PostgreSQL seed plus our own `sql/*.sql` for the trees. The other dialects carry the same menus in
// other spellings; reading them would add nothing but time. What it does **not** check is whether a
// caller may open the page — that is the console's own menu permission and is deliberately not
// second-guessed here (see `ConsolePages`'s class comment).
//
// 助手提供的每一页，都是**我们发布的菜单**解得出来的一页。
//
//   node scripts/check-console-pages.mjs
//
// `ConsolePages` 是一份策展清单，而策展清单就是**第二个可以忘的地方**。让它安全的是这一条：它点名的每条路径，都在
// **我们发布的 SQL** 所定义的菜单树里查一遍，并且按**控制台路由的拼法**拼——父路径接子路径。上游把某个页面挪走或改名，
// 这道检查就**变红**，而不是在工具里留下一条哪儿也去不了的路径——那正是这类清单会有的失效方式，而运行时查表不会有。
//
// 射程是**写明**的：路径读我们自己那份 `ConsolePages.java`，菜单树读 PostgreSQL 种子加上我们自己的 `sql/*.sql`。
// 别的方言用别的拼法带着同一批菜单，读它们只会多花时间。它**不**查的一件事是：某个调用方能不能打开那一页——那是控制台
// 自己的菜单权限，且**刻意**不在这里替它猜（见 `ConsolePages` 的类注释）。
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const SOURCE = 'yudao-server/src/main/java/cn/iocoder/yudao/server/keelbase/ConsolePages.java'

/** `new Page("key", "name", "route", "description")` — one line each, so a line-wise read is enough. */
const PAGE = /new Page\("([^"]+)",\s*"[^"]*",\s*"([^"]+)",/g

/** One `system_menu` row: id, name, permission, type, sort, parent, path. */
const MENU_ROW = /\((\d+),\s*'[^']*',\s*'[^']*',\s*(\d+),\s*(\d+),\s*(\d+),\s*'([^']*)'/g

const sqlFiles = () => {
  const files = []
  for (const dir of ['sql', join('sql', 'postgresql')]) {
    let entries
    try {
      entries = readdirSync(join(ROOT, dir))
    } catch {
      continue
    }
    for (const e of entries) {
      if (e.endsWith('.sql')) files.push(join(dir, e))
    }
  }
  return files
}

/**
 * The routes the shipped menu tree can produce, composed parent-then-child.
 *
 * A row whose parent is not in the same set (another dialect's fragment, a row we filtered out) is
 * composed as far as it goes rather than dropped: the check is about whether a route exists, and
 * composing half a path would report a miss that is really a parser's limit.
 */
const routesFromMenus = () => {
  const rows = new Map()
  for (const file of sqlFiles()) {
    const sql = readFileSync(join(ROOT, file), 'utf8')
    for (const statement of sql.matchAll(/INSERT INTO system_menu[^;]*;/gs)) {
      for (const row of statement[0].matchAll(MENU_ROW)) {
        rows.set(row[1], { parent: row[4], path: row[5] })
      }
    }
  }

  const routeOf = (id, seen = new Set()) => {
    const row = rows.get(id)
    if (!row || !row.path || seen.has(id)) return null
    seen.add(id)
    if (row.parent === '0') return '/' + row.path.replace(/^\/+/, '')
    const parent = routeOf(row.parent, seen)
    return parent === null ? null : parent.replace(/\/+$/, '') + '/' + row.path
  }

  return new Set([...rows.keys()].map((id) => routeOf(id)).filter((r) => r !== null))
}

const source = readFileSync(join(ROOT, SOURCE), 'utf8')
const pages = [...source.matchAll(PAGE)].map((m) => ({ key: m[1], route: m[2] }))
if (pages.length === 0) {
  console.error(`no \`new Page(...)\` entries found in ${SOURCE} — the reader, not the list, is wrong`)
  process.exit(1)
}

const routes = routesFromMenus()
const unresolved = pages.filter((p) => !routes.has(p.route))

for (const page of pages) {
  const mark = routes.has(page.route) ? 'ok      ' : 'MISSING '
  console.log(`  ${mark} ${page.key.padEnd(16)} ${page.route}`)
}

if (unresolved.length > 0) {
  console.error()
  for (const page of unresolved) {
    console.error(`  ${page.route}  (${page.key}) is not a route this deployment's menus compose`)
  }
  console.error()
  console.error(`${unresolved.length} page(s) the assistant would offer and the console cannot open.`)
  console.log(`${pages.length - unresolved.length} ok / ${unresolved.length} failed`)
  process.exit(1)
}

console.log(`\n${pages.length} page(s), each a route the shipped menus compose `
  + `(${routes.size} routes across ${sqlFiles().length} SQL file(s) in ${relative(ROOT, ROOT) || '.'})`)
console.log(`${pages.length} ok / 0 failed`)
