#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// Generate the offline Iconify collections the console ships with.
//
// The upstream console renders Iconify icons and registers only three collections (`ep`, `fa`,
// `fa-solid`). Everything else it renders it fetches at runtime from Iconify's public API — which
// means every visitor's browser talks to api.iconify.design / api.simplesvg.com, a host neither we
// nor the deployment controls. That is the vendor trace this script closes.
//
// It scans the checkout's source for icon references, keeps only the icons actually named (the
// upstream tree spans ~35 collections; bundling them whole would add ~70 MB), reads their bodies
// out of `@iconify/json`, and writes a single TypeScript module the build lays in as an overlay.
// The console's Icon component is then switched to `@iconify/vue/offline`, which never reaches the
// network — so a missing registration shows up as a blank icon rather than a silent API call.
//
//   node scripts/gen-offline-icons.mjs            # write the module
//   node scripts/gen-offline-icons.mjs --check    # verify only; non-zero on drift
//
// The checkout has to carry our delta (run a build, or apply it by hand) so icons our own patches
// introduce are seen too. The @iconify/json dev-dependency has to be installed in the checkout —
// it is what the bodies come from.
//
// 生成控制台自带的离线 Iconify 集合。
//
// 上游控制台渲染 Iconify 图标，却只注册三个集合（`ep`、`fa`、`fa-solid`）。其余的一律在运行时从
// Iconify 的公共 API 取——于是每位访客的浏览器都会去访问 api.iconify.design / api.simplesvg.com，
// 一个我们和部署都不控制的主机。这正是本脚本要关掉的厂商痕迹。
//
// 它扫描检出的源码里出现的图标引用，只保留真正被点名的那些（上游树跨约 35 个集合；整套打包要加约
// 70MB），从 `@iconify/json` 取出它们的图形数据，写成一个 TypeScript 模块、由构建作为覆盖铺进去。
// 随后把控制台的 Icon 组件切到 `@iconify/vue/offline`——它永不联网，故「漏注册」表现为一个空白图标，
// 而不是一次悄悄发出的 API 调用。
//
//   node scripts/gen-offline-icons.mjs            # 写出该模块
//   node scripts/gen-offline-icons.mjs --check    # 只校验；有漂移则非零退出
//
// 检出须带着我们的增量（跑一次构建，或手工铺上），这样我方补丁引入的图标才能被看见。
// `@iconify/json` 这个开发依赖须已在检出里装好——图形数据从它来。
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const check = process.argv.includes('--check')
const uiArg = process.argv.slice(2).find((a) => !a.startsWith('-'))
const UI_DIR = uiArg ? path.resolve(uiArg) : path.join(REPO_ROOT, 'target/yudao-ui-admin-vue3')

const OUT = path.join(REPO_ROOT, 'keelbase-ui/host-overlay/src/assets/keelbase-icons.ts')
// Data-driven icons — menu rows in the database, not source — cannot be found by scanning. They are
// listed by hand next to this delta so the scan stays source-only and reproducible.
// 数据驱动的图标——数据库里的菜单行，而非源码——扫不出来。它们手工列在这份增量旁边，
// 好让扫描保持「只看源码」且可复现。
const EXTRA = path.join(REPO_ROOT, 'keelbase-ui/host-icons-extra.txt')

const JSON_DIR = path.join(UI_DIR, 'node_modules/@iconify/json/json')
if (!fs.existsSync(JSON_DIR)) {
  console.error(`no @iconify/json at ${JSON_DIR}`)
  console.error('install the checkout\'s dependencies first (see scripts/build-host-ui.sh)')
  process.exit(2)
}
const collections = new Set(
  fs.readdirSync(JSON_DIR).filter((f) => f.endsWith('.json')).map((f) => f.slice(0, -5))
)

function walk(dir, acc = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) {
      if (/^(node_modules|dist|dist-prod|\.git)$/.test(e.name)) continue
      walk(p, acc)
    } else if (/\.(ts|vue|js|tsx)$/.test(e.name)) {
      // this generator's own output is laid in as an overlay before a build, so on a delta-applied
      // tree it is under src/ — reading it would be self-referential.
      // 本生成器自己的产物在构建前会作为覆盖铺进去，故在带增量的树上它就落在 src/ 下——读它等于自引用。
      if (e.name === path.basename(OUT)) continue
      acc.push(p)
    }
  }
  return acc
}

// A reference looks like `prefix:name` inside a line that mentions "icon", and the prefix has to be
// a collection @iconify/json actually carries — that is what keeps CSS (`display:flex`) and prose
// out of the result.
// 一条引用形如 `prefix:name`，所在行提到 "icon"，且前缀必须是 @iconify/json 真有的集合——
// 这正是把 CSS（`display:flex`）与散文挡在外面的东西。
const REF = /\b([a-z][a-z0-9]*(?:-[a-z0-9]+)*):([a-z0-9]+(?:-[a-z0-9]+)*)\b/g
const wanted = new Map() // prefix -> Set(name)
function add(prefix, name) {
  if (!collections.has(prefix)) return
  if (!wanted.has(prefix)) wanted.set(prefix, new Set())
  wanted.get(prefix).add(name)
}

for (const f of walk(path.join(UI_DIR, 'src'))) {
  for (const line of fs.readFileSync(f, 'utf8').split(/\r?\n/)) {
    if (!/icon/i.test(line)) continue
    for (const m of line.matchAll(REF)) add(m[1], m[2])
  }
}

if (fs.existsSync(EXTRA)) {
  for (const line of fs.readFileSync(EXTRA, 'utf8').split(/\r?\n/)) {
    const t = line.trim()
    if (!t || t.startsWith('#')) continue
    const i = t.indexOf(':')
    if (i > 0) add(t.slice(0, i), t.slice(i + 1))
  }
}

// Keep only the named icons. A name can be an alias, so the parent chain is followed and the alias
// entry is carried along — otherwise an aliased icon would silently render blank.
// 只留被点名的图标。名字可能是个别名，故沿 parent 链上溯、并把别名条目一并带上——
// 不然一个别名图标会悄悄变成空白。
function collect(prefix, names) {
  const src = JSON.parse(fs.readFileSync(path.join(JSON_DIR, `${prefix}.json`), 'utf8'))
  const out = { prefix, width: src.width, height: src.height, icons: {}, aliases: {} }
  const missing = []
  const seen = new Set()
  function take(name) {
    if (seen.has(name)) return true
    seen.add(name)
    if (src.icons && src.icons[name]) {
      out.icons[name] = src.icons[name]
      return true
    }
    const al = src.aliases && src.aliases[name]
    if (al) {
      out.aliases[name] = al
      if (al.parent) return take(al.parent)
      return true
    }
    return false
  }
  for (const n of names) if (!take(n)) missing.push(n)
  if (!Object.keys(out.aliases).length) delete out.aliases
  return { out, missing }
}

const out = []
const problems = []
for (const prefix of [...wanted.keys()].sort()) {
  const { out: c, missing } = collect(prefix, [...wanted.get(prefix)].sort())
  for (const m of missing) problems.push(`${prefix}:${m}`)
  c.icons = Object.fromEntries(Object.entries(c.icons).sort(([a], [b]) => (a < b ? -1 : 1)))
  if (c.aliases) c.aliases = Object.fromEntries(Object.entries(c.aliases).sort(([a], [b]) => (a < b ? -1 : 1)))
  out.push(c)
}
if (problems.length) {
  // Loud on purpose. A name the source references but no collection carries is not a missing
  // registration — it is a name that will render blank wherever it is used, and with the offline
  // component there is no CDN request left to betray it. `ep:question` sat in two upstream pages
  // that way until this check existed.
  // 刻意响亮。源码引用、而没有任何集合带的名字，**不是**漏注册——它是一个**在每处使用它的地方都渲染成
  // 空白**的名字；换到离线组件之后，再没有一次 CDN 请求会把它暴露出来。`ep:question` 就这样在上游两个页面
  // 里躺着，直到有了这道检查。
  console.error('these names are referenced but no collection carries them — they render blank:')
  for (const p of problems) console.error('  ' + p)
  console.error('fix the name at its use site (or the collection), rather than shipping a blank icon')
  process.exit(1)
}

const body = out.map((c) => '  ' + JSON.stringify(c)).join(',\n')
const header = `// SPDX-License-Identifier: Apache-2.0
//
// Generated by scripts/gen-offline-icons.mjs — do not edit by hand.
// 由 scripts/gen-offline-icons.mjs 生成——不要手改。
//
// Every Iconify icon this console renders, gathered into the offline collections it ships with, so
// the page never asks a CDN for one. Regenerate with
// \`node scripts/gen-offline-icons.mjs\`; verify with \`--check\`.
//
// 本控制台渲染的每一个 Iconify 图标，收进它自带的离线集合，页面因此永不向 CDN 索取。
// 用 \`node scripts/gen-offline-icons.mjs\` 重新生成；用 \`--check\` 校验。
import type { IconifyJSON } from '@iconify/vue/offline'

const collections: IconifyJSON[] = [
`
const footer = `\n]\n\nexport default collections\n`
const text = header + body + footer

const summary = out.map((c) => `${c.prefix}:${Object.keys(c.icons).length}`).join(' ')
const total = out.reduce((n, c) => n + Object.keys(c.icons).length, 0)

if (check) {
  const cur = fs.existsSync(OUT) ? fs.readFileSync(OUT, 'utf8') : ''
  if (cur !== text) {
    console.error(`offline icon set is stale — regenerate: ${path.relative(REPO_ROOT, OUT)}`)
    console.error(`would write ${total} icons across ${out.length} collections`)
    process.exit(1)
  }
  console.log(`offline icon set is current — ${total} icons across ${out.length} collections`)
  process.exit(0)
}

fs.mkdirSync(path.dirname(OUT), { recursive: true })
fs.writeFileSync(OUT, text)
console.log(`wrote ${path.relative(REPO_ROOT, OUT)}`)
console.log(`  ${total} icons across ${out.length} collections`)
console.log('  ' + summary)
