#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// 本宿主上每一条 AI 执行路径都登记，且它报的种类就是它自己那种。
//
//   node scripts/check-governance-registry.mjs
//
// 登记处（`GovernedModuleRegistry`）收的是 Spring 看得见的那些声明，故一台跑着的宿主可以被问到「这里被数到的是什么」。
// 它**做不到**的是：分出「不受治理」与「被忘了」——载体没自报的路径，**就是不在**集合里。本检查堵的就是那个洞，而它堵在
// **源码**上、不是运行时。
//
// **`YD-24`：射程从「模块一种」扩到全量 AI 执行路径。**在此之前，这里只读模块的 `service` 层切点一种形状，于是一个
// **工具面**——AI 经它到达执行的地方——从没被枚举过，丢了也没人看得见。现在四种形状都读（下面「怎么数」列全），每种
// 都要求**登记**：形状读出来的每一个载体，都得在登记处自报（或在手写目录里被列出）；缺失即红。
//
// ## 怎么数：射程与四条形状
//
// 射程是**写明的**：只扫 `yudao-server` 自己的 `cn.iocoder.yudao.server.keelbase` 树（含 `generated/`）。上游的模块不扫
// ——我们不治理它们，是它们被我们治理。
//
// **射程为何到此（依据，不是省略）。** 两条。① **方向**：治理接口（`GovernedPath`）住在 `yudao-server`，而
// `yudao-module-ai1-springai` **不能反向依赖它** ⇒ 那个模块里的类**实现不了**本接口，自报这条路对它们根本不通。
// ② **那一层自称什么**：`ai1` 的 `harness/`，按它自己的 `package-info.java`，是**平台去调用**大模型、MCP 服务、知识库
// 与 SKILL 的**客户端与工厂**——原文是「**不是**注册给模型调用的 function tool」。故本集合装的是**本线自己声明的路径**，
// 落在 keelbase 树内；`harness/` 那一层整层属**出站**，不入本射程。
//
// **一处张力，如实记、不掩。** 那份手写目录里的 `Ai1McpGovernance` 切的是 `Ai1McpClientTool.callTool`——那是**出站**
// （宿主作为 MCP 客户端去调外部服务端），与 `harness/` 里那些**同方向**的装配者分处两侧。故这条边界判的是**「归谁声明」**，
// 不是「入站还是出站」；把它读成后者，这一条会看起来自相矛盾。
//
// 一条**AI 执行路径** = 本宿主上一处「AI 能到达执行」的载体，按下面四条形状判定（读的是**形状**、不是名字）：
//
//   * `module`  —— 自己的 `@Around` pointcut 点了 `cn.iocoder.yudao.module.<x>.service.` 的切面。它是一处**业务模块**，
//      且拿模块自己的 service 层当接缝；它必须 `implements GovernedModule`，且它报的名字**必须**是它 pointcut 里的 `<x>`。
//   * `tool`    —— 把可调用物交给**引擎或模型**的地方，读三种子形状：① 声明 `@Bean` 且**声明返回类型**是 `AiTool` 的方法
//      （工具面）；② `implements AiTool` 的类（单工具）；③ `implements Ai1LocalToolProvider` 的类（本部署自己的引擎工具面）；
//      ④ 一处**不是模块 service** 的切面（`@Aspect` + `@Around`，但 pointcut 不走 `module.<x>.service.`）—— 那是打**工具路**
//      的治理（本宿主上就是 MCP 那条）。它必须**登记**：或者自己 `implements GovernedPath`（`kind()` 为 `TOOL`），或者在
//      `GovernedToolFaces.java` 这份手写目录里被按类名列出（生成面走这条，见那份文件的类注释）。
//   * `workflow` / `scheduled-task` —— 今天本宿主上**一条都没有**；形状写在下面，空缺**明说**而不是省略。一条流程若出现，
//      它应 `implements GovernedPath` 并报 `WORKFLOW`；一个定时任务同理报 `SCHEDULED_TASK`。
//
// **为什么工具也守「反向」**：目录里列了一个**没有**对应形状的类名 ⇒ 那也是红（一条过期的登记，读起来像治理、其实是幽灵）。
// 故本检查两个方向都读：形状 ⇒ 有登记，登记 ⇒ 有形状。
//
// Every AI execution path on this host is declared, and the kind it declares is the one it is.
//
//   node scripts/check-governance-registry.mjs
//
// The registry (`GovernedModuleRegistry`) collects the declarations Spring can see, so a running host can
// be asked what is counted here. What it cannot do is tell "not governed" from "forgotten" — a path whose
// carrier does not declare itself is simply absent. This check closes that gap, on the **sources** rather
// than at runtime.
//
// **`YD-24`: the scope widened from one kind to every AI execution path.** Before, this read a module's
// `service.` pointcut and nothing else, so a **tool face** — a place the AI reaches execution through —
// was never enumerated and could be dropped unseen. It now reads four shapes (all listed above), and each
// requires a **declaration**: every carrier a shape picks out must declare itself in the registry (or be
// listed in the hand-written catalogue); a miss is red.
//
// Scope is stated, not implied: only `yudao-server`'s own `cn.iocoder.yudao.server.keelbase` tree
// (including `generated/`). Upstream's modules are not scanned; we do not govern them, they are governed
// by us.
//
// **Why the scope ends here (a reason, not an omission).** Two of them. (1) **Direction**: the
// governance interface (`GovernedPath`) lives in `yudao-server`, and `yudao-module-ai1-springai`
// **cannot depend back on it** — so a class in that module **cannot** implement the interface and
// declaring itself is not open to it at all. (2) **What that layer says it is**: `ai1`'s `harness/`, by
// its own `package-info.java`, is the **client and factory side** that calls out to models, MCP,
// knowledge bases and SKILL — in its words, "**not** a function tool registered for the model to call".
// So this set holds **the paths this line declares itself**, inside the keelbase tree; `harness/` is
// outbound as a whole and stays outside the scope.
//
// **One tension, recorded rather than hidden.** The hand-written catalogue's `Ai1McpGovernance` advises
// `Ai1McpClientTool.callTool` — an **outbound** call (this host as an MCP client reaching an external
// server), on the far side from the same-direction assemblers in `harness/`. So this boundary judges
// **"who declares it"**, not "inbound or outbound"; read as the latter, this one line looks
// self-contradictory.
//
// An **AI execution path** is a carrier of one of four shapes — read as shapes, not names:
//
//   * `module`  — an aspect whose `@Around` pointcut names `cn.iocoder.yudao.module.<x>.service.`. It must
//       `implements GovernedModule`, and the name it declares has to be the `<x>` own pointcuts name.
//   * `tool`    — a place that hands callables to the engine or a model, read as three sub-shapes: a
//       `@Bean` method whose declared return type is `AiTool`; a class that `implements AiTool`; a class
//       that `implements Ai1LocalToolProvider`; and an aspect that governs a **tool path** rather than a
//       module's `service.` layer. It must declare itself (`implements GovernedPath`, `kind()` = `TOOL`)
//       or be listed by class name in the hand-written `GovernedToolFaces.java` catalogue.
//   * `workflow` / `scheduled-task` — there are none on this host today; the shapes are stated and the
//       emptiness is said rather than omitted.
//
// **Why tools are read both ways**: a name listed in the catalogue with no matching shape is also red — a
// stale declaration that reads like governance but is a ghost. So both directions are read.
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const OWN = 'yudao-server/src/main/java/cn/iocoder/yudao/server/keelbase'
// The one hand-written catalogue a tool face may be listed in instead of declaring itself. Read as data,
// so a face added to the sources without an entry here turns this check red.
// 工具面可以**不**自报、改为在这份手写目录里被列出（生成面走这条）。当数据读：源码里加了面而这里没加，即红。
const CATALOGUE = 'yudao-server/src/main/java/cn/iocoder/yudao/server/keelbase/GovernedToolFaces.java'

/** A pointcut that goes through a module's own service layer — the shape that makes it a governed module. */
const MODULE_SERVICE = /execution\(\*\s*cn\.iocoder\.yudao\.module\.([a-z0-9]+)\.service\./g
const HAS_MODULE_SERVICE = /execution\(\*\s*cn\.iocoder\.yudao\.module\.([a-z0-9]+)\.service\./
/** The declaration a governed module requires, and the name it declares. */
// Anchored to a *class declaration* (`class X implements …`) rather than the bare phrase, so prose in a
// comment — a class comment may well write "implements GovernedModule" while explaining the pattern —
// is not read as a declaration. Shape, not text. / 锚在**类声明**上（`class X implements …`），而不是光秃秃那句话，
// 免得注释里的散文（类注释完全可能写着 `implements GovernedModule` 在解释这个形状）被读成声明。读形状、不读文字。
const DECLARES_MODULE = /\bclass\s+\w+[^{;]*\bimplements\b[^{;]*\bGovernedModule\b/
const MODULE_METHOD = /String\s+module\(\)\s*\{\s*return\s*"([a-z0-9]+)"/
/** The declaration a tool face may carry itself (a record may carry it too — the catalogue's entry does). */
const DECLARES_PATH = /\b(?:class|record)\s+\w+[^{;]*\bimplements\b[^{;]*\bGovernedPath\b/
const KIND_METHOD = /Kind\s*\.\s*(MODULE|TOOL|WORKFLOW|SCHEDULED_TASK)/
/** A class that hands tools to the engine or a model, as a shape in the source. */
const IMPLEMENTS_AI_TOOL = /\bclass\s+\w+[^{;]*\bimplements\b[^{;]*\bAiTool\b/
const IMPLEMENTS_LOCAL_PROVIDER = /\bclass\s+\w+[^{;]*\bimplements\b[^{;]*\bAi1LocalToolProvider\b/
/** Is this source line a comment? A shape lives in code, not in prose about it. / 这一行是注释吗？形状长在代码里、不长在讲它的散文里。 */
const IS_COMMENT_LINE = /^\s*(\*|\/\/|\/\*)/

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

/** The class a file primarily declares, as the check names it: the file's base name. */
const classNameOf = (file) => file.split(/[\\/]/).pop().replace(/\.java$/, '')
const pathOf = (file) => relative(ROOT, file).split('\\').join('/')

/** Does a `@Bean` method declare `AiTool` as its return type? Read as a declaration, like the sibling check. */
const declaresAiToolBean = (text) => {
  const lines = text.split(/\r?\n/)
  for (let i = 0; i < lines.length; i += 1) {
    if (IS_COMMENT_LINE.test(lines[i]) || !/@Bean\b/.test(lines[i])) continue
    let decl = ''
    for (let j = i; j < lines.length && j < i + 15; j += 1) {
      decl += `${lines[j]}\n`
      if (/[{;]\s*$/.test(lines[j])) break
    }
    if (/\bAiTool\s+\w+\s*\(/.test(decl)) return true
  }
  return false
}

// The tool faces a person has enumerated by hand, as the catalogue declares them: `new ToolFace("X")`.
// 人手枚举出来的工具面，按目录声明它们的样子读：`new ToolFace("X")`。
let catalogueIds = []
try {
  catalogueIds = [...new Set([...readFileSync(join(ROOT, CATALOGUE), 'utf8')
    .matchAll(/new\s+ToolFace\(\s*"([^"]+)"\s*\)/g)].map((m) => m[1]))]
} catch {
  // Absent catalogue: every tool face becomes undeclared rather than silently passing. / 目录缺失：每个工具面都判未登记，
  // 而不是悄悄放行。
}

const declared = { module: [], tool: [], workflow: [], scheduledTask: [] }
const offenders = []
const seenCatalogue = new Set()

for (const file of javaFiles) {
  const text = readFileSync(file, 'utf8')
  const path = pathOf(file)
  const cls = classNameOf(file)
  const moduleNames = [...new Set([...text.matchAll(MODULE_SERVICE)].map((m) => m[1]))]

  // ---- module shape -------------------------------------------------------------------------------
  if (moduleNames.length > 1) {
    offenders.push(`${path} names more than one module (${moduleNames.join(', ')}) — a declaration ` +
      'carries one name; split the aspect or say which module it governs')
    continue
  }
  if (moduleNames.length === 1) {
    const module = moduleNames[0]
    if (!DECLARES_MODULE.test(text)) {
      offenders.push(`${path} governs \`${module}\` but does not implement GovernedModule — ` +
        'the registry cannot count what was never declared')
      continue
    }
    const name = MODULE_METHOD.exec(text)
    if (name === null) {
      offenders.push(`${path} implements GovernedModule but declares no name — ` +
        '`module()` has to return the module it governs')
      continue
    }
    if (name[1] !== module) {
      offenders.push(`${path} declares \`${name[1]}\` and its pointcuts name \`${module}\` — ` +
        'the set would be countable and wrong')
      continue
    }
    declared.module.push({ id: module, file: path })
    continue
  }
  // Declares itself a module but names no module's service — a declaration with nothing under it.
  if (DECLARES_MODULE.test(text)) {
    offenders.push(`${path} declares itself a GovernedModule but names no module's \`.service.\` ` +
      'pointcut — either point it at the module it governs, or drop the declaration')
    continue
  }

  // ---- tool shape ---------------------------------------------------------------------------------
  const toolShape = declaresAiToolBean(text) || IMPLEMENTS_AI_TOOL.test(text)
    || IMPLEMENTS_LOCAL_PROVIDER.test(text)
    || (/@Aspect\b/.test(text) && /@Around\b/.test(text) && !HAS_MODULE_SERVICE.test(text))
  if (!toolShape) continue

  // A tool face declares itself, or is listed in the hand-written catalogue. / 工具面自报，或在手写目录里被列出。
  const selfDeclared = DECLARES_PATH.test(text)
  if (selfDeclared && !/Kind\s*\.\s*TOOL/.test(text) && KIND_METHOD.test(text)) {
    offenders.push(`${path} declares itself a GovernedPath of kind ${KIND_METHOD.exec(text)[1]} ` +
      'but carries a tool shape — a tool face declares `Kind.TOOL`')
    continue
  }
  if (selfDeclared || catalogueIds.includes(cls)) {
    if (catalogueIds.includes(cls)) seenCatalogue.add(cls)
    declared.tool.push({ id: cls, file: path })
    continue
  }
  offenders.push(`${path} is a tool face but is not declared — a shape the AI reaches execution ` +
    'through, with nothing on the list that says so. Declare it (`implements GovernedPath`, `kind()` ' +
    '= `TOOL`) or list it in GovernedToolFaces.java')
}

// The catalogue read backwards: a listed name with no matching shape is a stale declaration. / 目录反读：列了名却
// 没有对应形状，是一条过期登记。
for (const id of catalogueIds) {
  if (!seenCatalogue.has(id)) {
    offenders.push(`GovernedToolFaces.java lists \`${id}\` but no file in the scope carries a tool ` +
      'shape under that name — a declaration with nothing under it reads like governance and is a ghost')
  }
}

// ---- report, grouped by kind ---------------------------------------------------------------------
const line = (kind, id, file) => console.log(`  ${kind.padEnd(14)} ${id.padEnd(24)} ${file}`)
for (const m of declared.module.sort((a, b) => a.id.localeCompare(b.id))) line('module', m.id, m.file)
for (const t of declared.tool.sort((a, b) => a.id.localeCompare(b.id))) line('tool', t.id, t.file)
for (const w of declared.workflow) line('workflow', w.id, w.file)
for (const s of declared.scheduledTask) line('scheduled-task', s.id, s.file)
if (declared.workflow.length === 0) line('workflow', '(none)', '— no such path on this host today')
if (declared.scheduledTask.length === 0) line('scheduled-task', '(none)', '— none on this host today')

const total = declared.module.length + declared.tool.length + declared.workflow.length
  + declared.scheduledTask.length

if (offenders.length > 0) {
  console.error()
  for (const o of offenders) console.error(`  UNDECLARED  ${o}`)
  console.error()
  console.error(`${offenders.length} path(s) would make the governed set incomplete, wrong or stale.`)
  console.log(`${total} ok / ${offenders.length} failed`)
  process.exit(1)
}

console.log(`\n${total} AI execution path(s) declared — ` +
  `${declared.module.length} module, ${declared.tool.length} tool, ` +
  `${declared.workflow.length} workflow, ${declared.scheduledTask.length} scheduled-task ` +
  `(${javaFiles.length} file(s) under ${OWN})`)
console.log(`${total} ok / 0 failed`)
