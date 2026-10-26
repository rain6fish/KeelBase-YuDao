#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// 渲染出来的一行字里，不得有字面的 Markdown 记号。
//
//   node scripts/check-rendered-copy.mjs [UI 检出目录]
//
// 2026-10-25/26 实测：`**没有会话**`、`**本部署**`、`**声明返回类型**`、`**一个**` 这些**成对的星号**，
// 以及若干反引号，被原样画在中文页面上。字符串级抓不住它，因为**字符串本身是对的**：词条里写
// `{'@'}Bean` 是为了让 vue-i18n 不要把它当链接、渲染成 `@Bean`；写 `**x**` 也只是数据。记号是在
// **渲染那一刻**才变成读者眼前的字面的。故本闸门走两条真实的渲染路径：
//
//   1. 把每个 `src/locales/*.ts` 的默认导出交给 vue-i18n 的 `t()`（带占位符），断言渲出的每一个
//      词条里都没有 `**`、也没有反引号；
//   2. 把**这一层发布的**每个 `.vue`（覆盖目录 + 补丁改到的）的 `<template>` 交给
//      `@vue/compiler-sfc` + `@vue/compiler-dom` 解析，断言文本节点与静态属性值里没有同样两种记号
//      （HTML 注释不算 —— 源码注释里的强调是给维护者看的）。
//
// 它要一个 UI 检出（`target/yudao-ui-admin-vue3`，或第一个参数 / `KEELBASE_YUDAO_UI`），因为渲染需要
// 那个检出自带的 `vue-i18n` 与 Vue 编译器。缺检出时**退出 2**：不明说「跳过」，否则这个闸门会在没跑的
// 时候显示为绿。`scripts/build-host-ui.sh` 在构建之后调用它，故正常流水线里它总会跑。
//
// ── English ─────────────────────────────────────────────────────────────────────
//
// No rendered line of copy may carry a literal Markdown marker.
//
//   node scripts/check-rendered-copy.mjs [UI checkout]
//
// Measured on 2026-10-25/26: `**没有会话**`, `**本部署**`, `**声明返回类型**`, `**一个**` — **paired
// asterisks** — and several backticks were drawn literally on Chinese pages. A string-level scan cannot
// see it, because **the string itself is right**: `{'@'}Bean` is written that way so vue-i18n does not
// take it for a link and renders it as `@Bean`; `**x**` is just data too. The markers become literal
// text only **at render time**. So this gate takes two real rendering paths:
//
//   1. every `src/locales/*.ts` default export goes through vue-i18n's `t()` (with its placeholders),
//      and every rendered message must contain neither `**` nor a backtick;
//   2. every `.vue` **this layer ships** (overlay + patched) goes through `@vue/compiler-sfc` +
//      `@vue/compiler-dom`, and no text node or static attribute value may contain either marker (HTML
//      comments do not count — emphasis in a source comment is for the maintainer).
//
// It needs a UI checkout (`target/yudao-ui-admin-vue3`, or the first argument / `KEELBASE_YUDAO_UI`),
// because rendering needs that checkout's own `vue-i18n` and Vue compilers. A missing checkout **exits
// 2** rather than saying "skipped", so this gate cannot read as green when it never ran.
// `scripts/build-host-ui.sh` calls it after the build, so the normal pipeline always runs it.
import { readFileSync, readdirSync, realpathSync, statSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const UI = process.argv[2] || process.env.KEELBASE_YUDAO_UI || join(ROOT, 'target', 'yudao-ui-admin-vue3')

const MARKERS = [
  ['**', 'paired asterisks (Markdown bold)'],
  ['`', 'a backtick (Markdown code span)'],
]

const missing = (what) => {
  console.error(`not run: ${what} is missing under ${UI}`)
  console.error('this gate renders through the checkout\'s own vue-i18n and Vue compilers; run it after')
  console.error('`bash scripts/build-host-ui.sh`, or pass the checkout as the first argument.')
  process.exit(2)
}

let vuePkg
try {
  vuePkg = realpathSync(join(UI, 'node_modules', 'vue', 'package.json'))
} catch {
  missing('node_modules/vue')
}
const requireFromUi = createRequire(pathToFileURL(vuePkg))
let sfc
let parseTemplate
let i18nModuleUrl
try {
  sfc = requireFromUi('@vue/compiler-sfc')
  parseTemplate = requireFromUi('@vue/compiler-dom').parse
  i18nModuleUrl = pathToFileURL(realpathSync(join(UI, 'node_modules', 'vue-i18n', 'dist', 'vue-i18n.mjs'))).href
} catch (e) {
  missing(`the Vue toolchain (${e.message})`)
}
const { createI18n } = await import(i18nModuleUrl)

const violations = []
const note = (where, text, why) => violations.push({ where, text, why })

/** Every leaf string of a message tree, with the dotted key it sits under. */
function leaves(tree, prefix = '', out = []) {
  for (const [key, value] of Object.entries(tree)) {
    const path = prefix ? `${prefix}.${key}` : key
    if (value && typeof value === 'object') leaves(value, path, out)
    else if (typeof value === 'string') out.push([path, value])
  }
  return out
}

/** The named placeholders a message declares — `{'@'}` is an escape, not a placeholder. */
function placeholders(message) {
  const params = {}
  for (const match of message.matchAll(/\{([a-zA-Z_][\w]*)\}/g)) params[match[1]] = 'X'
  return params
}

function checkRendered(where, text, why) {
  const found = MARKERS.filter(([marker]) => text.includes(marker)).map(([marker, label]) => `${marker} — ${label}`)
  if (found.length > 0) note(where, text, `${why} [${found.join('; ')}]`)
}

/** 1. Locale messages, rendered the way the page renders them. */
function checkLocales() {
  const dir = join(UI, 'src', 'locales')
  let files
  try {
    files = readdirSync(dir).filter((f) => f.endsWith('.ts'))
  } catch {
    console.error(`no src/locales under ${UI} — the reader, not the checkout, is wrong`)
    process.exit(1)
  }

  let rendered = 0
  let renderedKeelbase = 0
  for (const file of files) {
    const locale = file.replace(/\.ts$/, '')
    const source = readFileSync(join(dir, file), 'utf8')
    const body = source.replace(/^\s*export\s+default\s*/, '')
    let messages
    try {
      // The locale files are `export default { … }` and nothing else — no imports, no type
      // annotations. Evaluated as data, then handed to vue-i18n so `{'@'}` and `{n}` are resolved
      // exactly as they are on the page.
      messages = new Function(`return (${body})`)()
    } catch (e) {
      console.error(`${relative(UI, join(dir, file))}: cannot read the message tree as data (${e.message})`)
      process.exit(1)
    }
    const i18n = createI18n({ legacy: false, locale, messages: { [locale]: messages } })
    for (const [key, message] of leaves(messages)) {
      let text
      try {
        text = i18n.global.t(key, placeholders(message))
      } catch (e) {
        note(`${file}:${key}`, String(e.message), 'vue-i18n could not render it')
        continue
      }
      rendered += 1
      if (key.startsWith('keelbase.')) renderedKeelbase += 1
      checkRendered(`${file}:${key}`, text, 'rendered locale copy carries a literal marker')
    }
  }
  console.log(`  rendered ${rendered} locale message(s) from ${files.length} locale file(s)`)
  // 一个 `keelbase.*` 都没有，说的是**这一层没铺上**，不是「拷贝干净」——判红，别让它空跑变绿。
  // Zero `keelbase.*` messages means **this layer is not laid down**, not that the copy is clean — red,
  // rather than a run that passes by scanning nothing.
  if (renderedKeelbase === 0) {
    console.error(`no keelbase.* message under ${UI}/src/locales — our delta is not laid down (rebuild without --skip-build)`)
    process.exit(1)
  }
}

/**
 * 我们这一层铺上去的 `.vue`：覆盖目录里的那些，加上补丁改到的那些。上游自己的模板不在射程内 ——
 * 它带着自己的书写习惯（实测有三处代码生成页的 placeholder 用反引号包字段名），而那不是这一层要改的事；
 * 全树扫描的完整清单见本次提交的说明。射程**写明**，不是为了让它悄悄变绿。
 *
 * The `.vue` files this layer lays down: the overlay's own, plus every one a patch edits. Upstream's own
 * templates are out of scope — they carry their own writing habits (three code-generator pages put a
 * backtick around a field name in a placeholder, measured), and changing those is not this layer's job;
 * the full whole-tree list is in this commit's description. The scope is stated, not quietly shortened.
 */
function deltaVueFiles() {
  const files = new Set()
  const overlay = join(ROOT, 'keelbase-ui', 'host-overlay')
  const walk = (dir) => {
    for (const entry of readdirSync(dir)) {
      const path = join(dir, entry)
      const stat = statSync(path)
      if (stat.isDirectory()) walk(path)
      else if (entry.endsWith('.vue')) files.add(join(UI, relative(overlay, path)))
    }
  }
  walk(overlay)

  const patches = join(ROOT, 'keelbase-ui', 'host-patches')
  for (const patch of readdirSync(patches).filter((f) => f.endsWith('.patch'))) {
    for (const line of readFileSync(join(patches, patch), 'utf8').split('\n')) {
      const match = /^\+\+\+ b\/(.+\.vue)\r?$/.exec(line)
      if (match) files.add(join(UI, match[1]))
    }
  }
  return [...files]
}

/** 2. Template text and static attribute values, through Vue's own parser. */
function checkTemplates() {
  const files = deltaVueFiles()

  const visit = (node) => {
    if (!node) return
    if (node.type === 2 /* TEXT */) checkRendered('template', node.content, 'template text carries a literal marker')
    if (node.type === 1 /* ELEMENT */) {
      for (const prop of node.props ?? []) {
        if (prop.type === 6 /* static attribute */ && prop.value?.type === 2) {
          checkRendered('template attribute', prop.value.content, 'a static attribute value carries a literal marker')
        }
      }
    }
    for (const child of node.children ?? []) visit(child)
  }

  for (const file of files) {
    const { descriptor } = sfc.parse(readFileSync(file, 'utf8'))
    if (!descriptor.template) continue
    let ast
    try {
      ast = parseTemplate(descriptor.template.content)
    } catch (e) {
      console.error(`${relative(UI, file)}: template does not parse (${e.message})`)
      violations.push({ where: relative(UI, file), text: e.message, why: 'template does not parse' })
      continue
    }
    visit(ast)
  }
  console.log(`  scanned ${files.length} .vue template(s) this layer ships`)
}

checkLocales()
checkTemplates()

if (violations.length > 0) {
  console.error(`\n${violations.length} rendered line(s) carry a literal Markdown marker:\n`)
  for (const v of violations) {
    console.error(`  ${v.where}`)
    console.error(`    ${v.text.trim().slice(0, 200)}`)
    console.error(`    ${v.why}`)
  }
  console.error('\nWrite the emphasis in the template/component that renders it, or drop it — a reader')
  console.error('must never see the markers themselves.')
  process.exit(1)
}

console.log('\n0 rendered line(s) carry a literal `**` or backtick.')
