#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// No tool of ours may be handed to Spring AI as a bean.
//
//   node scripts/check-ungoverned-tool-beans.mjs
//
// `YD-T14` (D42) ruled that this host keeps a boundary rather than a guard: `yudao-module-ai` carries a
// model-to-tool path of its own — its chat takes a role's `toolIds`, resolves the registered names
// through Spring AI's own `ToolCallbackResolver` and hands the callbacks to the model — and KeelBase's
// governance does not see any of it. The aspect points at
// `cn.iocoder.yudao.module.ai1.harness.mcp.Ai1McpClientTool.callTool`, and nothing in that module
// references KeelBase at all.
//
// What keeps that path harmless today is not a check but a fact: the only `ToolCallback` bean in this
// build is upstream's own sample, so the resolver has nothing of ours to find. That fact is the whole
// boundary, and it is one `@Bean` away from no longer being true — a governed tool registered that way
// would become reachable by a model, with no engine, no identity check, no confirmation and no audit.
// This is the check that reads it.
//
// The scope is a stated boundary, not an implied one:
//
//   - only **our own** sources: `yudao-server` and the `ai1` variant this line carries and patches;
//   - **not** upstream's `yudao-module-ai`, which declares the sample bean on purpose and is not ours
//     to change — its presence is the fact the boundary rests on, not a violation of it;
//   - the shape is what is read, not a name: an `@Bean` method whose **declared return type** hands out
//     a `ToolCallback` (or `FunctionCallback` / `ToolCallbacks`). A method that merely *returns* one —
//     `Ai1McpToolFactory.buildTools` does — is not a bean, is never resolved by name, and must not be
//     reported: the seam this line relies on goes through exactly that method.
//
// 我们自己写的工具，一个都不许以 bean 的形式交给 Spring AI。
//
//   node scripts/check-ungoverned-tool-beans.mjs
//
// `YD-T14`（`D42`）裁的是**记边界**而不是加守卫：`yudao-module-ai` 自带一条模型→工具的路——它的 chat 取角色的
// `toolIds`、经 Spring AI 自己的 `ToolCallbackResolver` 把注册名解析成 callback 交给模型——而 KeelBase 的治理
// 一点都看不见。切面的切点是 `cn.iocoder.yudao.module.ai1.harness.mcp.Ai1McpClientTool.callTool`，而那个模块
// 里**没有任何东西引用 KeelBase**。
//
// 让那条路今天无害的**不是检查、是一个事实**：本构建里唯一的 `ToolCallback` bean 是**上游自己的示例**，故解析器
// 找不到我们的东西。**那个事实就是整条边界**，而它离「不再成立」只差一个 `@Bean` —— 以那种方式注册的受治理工具
// 会变得**模型够得到，且不经引擎、不经身份、不经确认、不留审计**。这个脚本就是读它的那道检查。
//
// 射程是**写明的边界**，不是暗示的：
//
//   - 只扫**我们自己的**源码：`yudao-server` 与本线携带并打过补丁的 `ai1` 变体；
//   - **不扫**上游的 `yudao-module-ai` —— 它**刻意**声明那个示例 bean，且不是我们该改的；它的存在正是边界所倚的
//     那个事实，**不是**对边界的违反；
//   - 读的是**形状**不是名字：`@Bean` 方法的**声明返回类型**交出 `ToolCallback`（或 `FunctionCallback` /
//     `ToolCallbacks`）。仅仅**返回**一个的方法——`Ai1McpToolFactory.buildTools` 就是——**不是 bean**、永不被按名
//     解析，**不得**被报出来：本线所倚的那条缝正是走那个方法的。
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { dirname } from 'node:path'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')

/** Our own sources. Upstream's modules are deliberately absent — see the header. */
const ROOTS = ['yudao-server/src', 'yudao-module-ai1-springai/src']

/** What a bean handing tools to Spring AI looks like in a signature. */
const TOOL_TYPES = /\b(ToolCallback|FunctionCallback|ToolCallbacks)\b/

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
for (const r of ROOTS) walk(join(ROOT, r))

const offenders = []
for (const file of javaFiles) {
  const text = readFileSync(file, 'utf8')
  const lines = text.split(/\r?\n/)
  lines.forEach((line, i) => {
    if (!/@Bean\b/.test(line)) return
    // The declaration runs from the annotation to the method's body or its `;`. Everything between is
    // its signature — annotations, modifiers, return type, name, parameters.
    let decl = ''
    for (let j = i; j < lines.length && j < i + 15; j += 1) {
      decl += `${lines[j]}\n`
      if (/[{;]\s*$/.test(lines[j])) break
    }
    if (TOOL_TYPES.test(decl)) {
      offenders.push({ file: relative(ROOT, file).split('\\').join('/'), line: i + 1, decl })
    }
  })
}

if (offenders.length > 0) {
  for (const o of offenders) {
    console.error(`  UNGOVERNED  ${o.file}:${o.line}  <- a @Bean hands tools to Spring AI by name`)
  }
  console.error()
  console.error(`${offenders.length} bean(s) would make our tools reachable by a model without KeelBase`
    + ' seeing the call. Route them through the runtime instead (see YD-T14 / D42).')
  console.log(`0 ok / ${offenders.length} failed`)
  process.exit(1)
}

console.log(`no bean in our own sources hands tools to Spring AI`
  + ` (${javaFiles.length} file(s) over ${ROOTS.length} root(s))`);
console.log(`1 ok / 0 failed`);
