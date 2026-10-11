#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// Blank every URL that points at another product's host, in the checkout the build is about to
// bundle.
//
// The delta can add files and patch files, but it cannot patch 350 of them, and that is how many
// carry a link into the platform this host was forked from — mostly `doc-alert` URLs in module
// pages. Those never render (the build turns the banner off with a flag), so what is left is a
// string in a lazily-loaded chunk: no request, but the vendor's site is still named in a page we
// ship. This step removes the string; the build then asserts none of the hosts reached the artefact,
// which is what catches a chunk nobody thought to grep.
//
// Only the URL is blanked, never surrounding code, so `<doc-alert url="…" />` becomes
// `<doc-alert url="" />` and `image: '…'` becomes `image: ''`. Empty is deliberate: we have no
// equivalent page to point at, and inventing one would be worse than saying nothing.
//
//   node scripts/sweep-vendor-hosts.mjs [UI_DIR]
//
// 把检出里每一条指向**别的产品主机**的 URL 清空——构建马上要打包的就是这棵树。
//
// 增量能新增文件、也能打补丁，但打不了 350 个——而载有一条指向**被 fork 的那个平台**的链接的，正有
// 这么多，多半是各模块页面里的 `doc-alert` URL。它们从不渲染（构建用开关关掉了提示条），于是剩下的是
// 懒加载分片里的一个字符串：没有请求，但我们发出去的页面上仍写着厂商的站点。这一步去掉那个字符串；
// 随后构建断言这些主机一条都没进产物——正是这一步逮住「没人想到去 grep 的那个分片」。
//
// 只清 URL、不动周围的代码，故 `<doc-alert url="…" />` 变成 `<doc-alert url="" />`、
// `image: '…'` 变成 `image: ''`。**空**是刻意的：我们没有等价页面可指，编一个比什么都不说更糟。
//
//   node scripts/sweep-vendor-hosts.mjs [UI_DIR]
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const UI_DIR = process.argv[2] ? path.resolve(process.argv[2]) : path.join(REPO_ROOT, 'target/yudao-ui-admin-vue3')
const LIST = path.join(REPO_ROOT, 'keelbase-ui/host-vendor-hosts.txt')

if (!fs.existsSync(LIST)) {
  console.error(`no host list at ${LIST}`)
  process.exit(2)
}
const domains = fs
  .readFileSync(LIST, 'utf8')
  .split(/\r?\n/)
  .map((l) => l.trim())
  .filter((l) => l && !l.startsWith('#'))
if (!domains.length) {
  console.error('the host list is empty — refusing to sweep')
  process.exit(2)
}

// Where the bundle comes from. Anything outside these is not shipped and is left alone.
const ROOTS = ['src', 'public'].map((d) => path.join(UI_DIR, d)).filter((d) => fs.existsSync(d))
if (!ROOTS.length) {
  console.error(`nothing to sweep under ${UI_DIR} — is the checkout there?`)
  process.exit(2)
}

const EXT = /\.(vue|ts|tsx|js|mjs|json|scss|css|html)$/
function walk(dir, acc = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) walk(p, acc)
    else if (EXT.test(e.name)) acc.push(p)
  }
  return acc
}

// A URL on one of the hosts: scheme, any subdomains, the domain, then everything up to the first
// character that cannot be inside it (a quote, a backtick, whitespace, an angle bracket, a close
// paren). An interpolated template literal ends at `${`, so only the literal prefix is removed.
const HOSTS = domains.map((d) => d.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')
const URL_RE = new RegExp(`https?://(?:[a-z0-9-]+\\.)*(?:${HOSTS})[^\\s"'\`<>)]*`, 'gi')

let files = 0
let total = 0
const perHost = new Map()

const targets = []
for (const root of ROOTS) targets.push(...walk(root))
// Vite inlines what the `.env` files hold, so a vendor host there reaches the bundle exactly as one
// written in a component does — that is how VITE_MALL_H5_DOMAIN did. They sit beside the checkout
// root rather than under src/, which is why the walk above misses them.
// Vite 会把 `.env` 文件里的值内联进包，故那里的厂商主机与写在组件里的一样会进产物——VITE_MALL_H5_DOMAIN
// 就是这么进去的。它们落在检出根旁边而不是 src/ 下，这正是上面那次遍历漏掉它们的原因。
for (const e of fs.readdirSync(UI_DIR, { withFileTypes: true })) {
  if (e.isFile() && /^\.env/.test(e.name)) targets.push(path.join(UI_DIR, e.name))
}

for (const f of targets) {
  const before = fs.readFileSync(f, 'utf8')
  if (!URL_RE.test(before)) continue
  URL_RE.lastIndex = 0
  const after = before.replace(URL_RE, (m) => {
    total++
    let h
    try {
      h = new URL(m).host
    } catch {
      h = m
    }
    perHost.set(h, (perHost.get(h) || 0) + 1)
    return ''
  })
  fs.writeFileSync(f, after)
  files++
}

console.log(`swept ${total} vendor URL(s) out of ${files} file(s) under ${path.relative(REPO_ROOT, UI_DIR)}`)
for (const [h, n] of [...perHost.entries()].sort((a, b) => b[1] - a[1])) console.log(`  ${String(n).padStart(5)}  ${h}`)
if (!total) console.log('  (none — nothing on those hosts was found)')
