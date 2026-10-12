#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// Every module this host governs declares itself, and the name it declares is the one its own
// pointcuts point at.
//
//   node scripts/check-governance-registry.mjs
//
// The registry (`GovernedModuleRegistry`) collects the declarations Spring can see, so a running host
// can be asked what is governed. What it **cannot** do is tell "not governed" from "forgotten" — a
// module whose aspect never declares itself is simply absent from the set. That is the gap this check
// closes, and it closes it on the sources rather than at runtime:
//
//   * a class under our own `keelbase` package whose `@Around` pointcut names
//     `cn.iocoder.yudao.module.<x>.service.` **is** a governed module — that shape is the definition
//     the onboarding file writes down, and it is what is read here;
//   * it has to `implements GovernedModule`, or the set silently misses it;
//   * the name it declares has to be the `<x>` its own pointcuts name, or the set is countable but
//     wrong.
//
// Scope is stated, not implied: only `yudao-server`'s own `cn.iocoder.yudao.server.keelbase` tree, and
// only files whose pointcut goes through a module's `service.` layer. A governance that targets a tool
// path instead — `Ai1McpGovernance` points at `module.ai1.harness.mcp` — is not a governed *module* and
// is not read here. Upstream's modules are not scanned; we do not govern them, they are governed by us.
//
// 本宿主治理的每个模块都**自报**，而它报的名字**就是**它自己的 pointcut 所指的那一个。
//
//   node scripts/check-governance-registry.mjs
//
// 登记处（`GovernedModuleRegistry`）收的是 Spring 看得见的那些声明，故一台跑着的宿主可以被问到「管住了什么」。
// 它**做不到**的是：分出「不受治理」与「被忘了」——切面从没自报的模块，**就是不在**集合里。本检查堵的就是那个洞，
// 而它堵在**源码**上、不是运行时：
//
//   * 我们自己 `keelbase` 包下、`@Around` pointcut 点了 `cn.iocoder.yudao.module.<x>.service.` 的类，**就是**一个
//     受治理的模块——那个形状就是接入文件写下的定义，也是这里读的东西；
//   * 它必须 `implements GovernedModule`，否则那个集合会**静默地**漏掉它；
//   * 它报的名字必须是它自己 pointcut 里的 `<x>`，否则那个集合**数得出来、但是错的**。
//
// 射程是**写明的**、不是暗示的：只扫 `yudao-server` 自己的 `cn.iocoder.yudao.server.keelbase` 树，且只看 pointcut
// 走**模块 `service.` 层**的文件。打**工具路径**的治理——`Ai1McpGovernance` 点的是 `module.ai1.harness.mcp`——不是
// 一个受治理的**模块**，这里不读它。上游的模块不扫：我们不治理它们，是它们被我们治理。
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const OWN = 'yudao-server/src/main/java/cn/iocoder/yudao/server/keelbase'

/** A pointcut that goes through a module's own service layer — the shape that makes it a governed module. */
const MODULE_SERVICE = /execution\(\*\s*cn\.iocoder\.yudao\.module\.([a-z0-9]+)\.service\./g
/** The declaration this check requires. */
const DECLARES = /implements[^{]*\bGovernedModule\b/
/** The name it declares. */
const MODULE_METHOD = /String\s+module\(\)\s*\{\s*return\s*"([a-z0-9]+)"/

const javaFiles = []
const walk = (dir) => {
  let entries
  try {
    entries = readdirSync(dir)
  } catch {
    return
  }
  for (const e of entries) {
    const p = join(dir, e)
    if (statSync(p).isDirectory()) walk(p)
    else if (e.endsWith('.java')) javaFiles.push(p)
  }
}
walk(join(ROOT, OWN))

const governed = []
const offenders = []

for (const file of javaFiles) {
  const text = readFileSync(file, 'utf8')
  const path = relative(ROOT, file).split('\\').join('/')
  const modules = [...new Set([...text.matchAll(MODULE_SERVICE)].map((m) => m[1]))]
  const declares = DECLARES.test(text)

  if (modules.length === 0) {
    // It governs something, but not a module's service — or it is not a governance at all.
    if (declares) {
      offenders.push(`${path} declares itself a GovernedModule but names no module's ` +
        '`.service.` pointcut — either point it at the module it governs, or drop the declaration')
    }
    continue
  }
  if (modules.length > 1) {
    offenders.push(`${path} names more than one module (${modules.join(', ')}) — a declaration ` +
      'carries one name; split the aspect or say which module it governs')
    continue
  }
  const module = modules[0]
  if (!declares) {
    offenders.push(`${path} governs \`${module}\` but does not implement GovernedModule — ` +
      'the registry cannot count what was never declared')
    continue
  }
  const declared = MODULE_METHOD.exec(text)
  if (declared === null) {
    offenders.push(`${path} implements GovernedModule but declares no name — ` +
      '`module()` has to return the module it governs')
    continue
  }
  if (declared[1] !== module) {
    offenders.push(`${path} declares \`${declared[1]}\` and its pointcuts name \`${module}\` — ` +
      'the set would be countable and wrong')
    continue
  }
  governed.push({ module, path, file: file.split(/[\\/]/).pop() })
}

for (const g of governed.sort((a, b) => a.module.localeCompare(b.module))) {
  console.log(`  governed  ${g.module.padEnd(10)} ${g.file}`)
}

if (offenders.length > 0) {
  console.error()
  for (const o of offenders) console.error(`  UNDECLARED  ${o}`)
  console.error()
  console.error(`${offenders.length} governance(s) would make the governed set incomplete or wrong.`)
  console.log(`${governed.length} ok / ${offenders.length} failed`)
  process.exit(1)
}

console.log(`\n${governed.length} governed module(s), each declaring the name its pointcuts point at`
  + ` (${javaFiles.length} file(s) under ${OWN})`)
console.log(`${governed.length} ok / 0 failed`)
