#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// Report how far this repository has moved from the upstream tree it was derived from.
//
// Why it exists: `D45` (2026-10-11) settled two things. `D15` — the RuoYi line's rule that upstream's
// generated artefacts and `vm/` are never touched — is **not** transplanted here, because this line
// already modifies seven upstream files and three of them change what upstream does on its own
// threads, all of them for `D32`. What replaces it is a declared boundary plus this patrol: a
// re-runnable reading of the footprint, so that "we changed upstream" is a number somebody can look
// at, rather than something found out during a rebase.
//
// Three things it will not do. It is **not a gate** — upstream moving, and this line moving with it,
// are both normal, so a red light here would mean nothing and be ignored within a week. It does not
// **fetch**: the baseline is the tag this repository already carries, and a comparison against the
// moving remote would silently add upstream's own progress to our count. And it does not measure the
// UI: `keelbase-ui/host-patches/` addresses a *different* upstream (`yudao-ui-admin-vue3`), so it is
// reported separately, as a count of the files our patch reaches.
//
//   node scripts/check-upstream-footprint.mjs
//
// 报告本仓相对「它派生自的那棵上游树」走了多远。
//
// 为什么有它：`D45`（2026-10-11）定下两件事。`D15` —— RuoYi 线那条「绝不碰上游生成物与 `vm/`」——
// **不平移**到本线：本线**已经改了 7 个上游文件，其中 3 个改的是上游在自己线程上做什么**，且**都为 `D32`**。
// 取代它的是**一条声明的边界 + 这道巡检**：让「我们改了上游」成为一个**能去看的数字**，而不是等到某次
// rebase 才发现的事。
//
// 三件它不做的事。它**不是门禁** —— 上游在动、本线跟着动，都是常态，亮红灯在这里没有意义、一周内就会被无视。
// 它**不 fetch** —— 基线是本仓**已经带着的那个 tag**；对着会漂的远端比，会把**上游自己的进度**悄悄算进我们的
// 数字。它也**不量界面**：`keelbase-ui/host-patches/` 面对的是**另一个上游**（`yudao-ui-admin-vue3`），故
// 另行报告——报的是我们的补丁够到多少个文件。
//
//   node scripts/check-upstream-footprint.mjs
import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const git = (args) => execFileSync('git', ['-C', REPO, ...args], { encoding: 'utf8' }).trim()
// Same call, but a non-zero exit is an answer rather than an error — "upstream does not have this
// path" is exactly what the caller is asking — so its stderr does not belong on the report.
// 同一个调用，但「非零退出」在这里**是个答案**而不是错误——调用方问的正是「上游有没有这个路径」——故它的
// stderr 不该出现在报告里。
const gitOk = (args) => {
  try {
    execFileSync('git', ['-C', REPO, ...args], { stdio: ['ignore', 'ignore', 'ignore'] })
    return true
  } catch {
    return false
  }
}

// The last reading anyone took by hand, and the day they took it. Growth past it is the signal; the
// number itself is not a target. A patrol that cannot be compared against anything is just a print
// statement, and one that fails is one nobody reads — this sits in between on purpose.
// 前一次有人手工量下的读数，以及量它的日子。**超过它**才是信号；数字本身不是目标。一道没有可比对象的巡检
// 就只是一句 print，而一失败就红的那道没人看——这里刻意取中间。
const BASELINE = { deleted: 0, added: 108, modified: 8, takenOn: '2026-10-11' }

let pin
try {
  pin = git(['rev-parse', 'upstream-pin-994605b^{commit}'])
} catch {
  console.error('no `upstream-pin-*` tag in this checkout — the baseline is missing, not the difference')
  console.error('本检出没有 `upstream-pin-*` tag —— 缺的是基线，不是差异')
  process.exit(2)
}

// Content, not mode. `git diff` calls a file modified when only its executable bit moved, and this
// tree lost roughly 130 of those coming across Windows; the blob comparison is what `KEELBASE-DEV.md`
// documents for exactly this reason.
// 看**内容**，不看 mode。只有可执行位动过时 `git diff` 也会报 modified，而这棵树在经 Windows 搬运时丢了
// 大约 130 个可执行位；`KEELBASE-DEV.md` 记的正是为此要比 blob。
const status = git(['diff', '--name-status', pin, 'HEAD']).split('\n').filter(Boolean)
const deleted = []
const added = []
const modified = []
for (const line of status) {
  const [code, ...rest] = line.split('\t')
  const p = rest.join('\t')
  if (code === 'D') deleted.push(p)
  else if (code === 'A') added.push(p)
  else if (code.startsWith('M')) {
    let same
    try {
      same = git(['rev-parse', `HEAD:${p}`]) === git(['rev-parse', `${pin}:${p}`])
    } catch {
      same = false
    }
    if (!same) modified.push(p)
  }
}

const changed = (p) => {
  const n = git(['diff', '--numstat', pin, 'HEAD', '--', p]).split('\n').filter(Boolean)[0] || ''
  const [a, b] = n.split('\t')
  return { plus: a, minus: b }
}

console.log(`baseline: ${pin.slice(0, 12)} (the tag this repository carries, not the remote)`)
console.log(`reading:  deleted ${deleted.length} · added ${added.length} · modified ${modified.length}`)
console.log()
console.log('modified upstream files (content, mode-only excluded):')
for (const p of modified) {
  const { plus, minus } = changed(p)
  console.log(`  +${plus} / -${minus}\t${p}`)
}

// Files we put inside a directory upstream already owns. `.github/` was the first, and nothing tells
// us when upstream adds something there — the same shape as the UI-drift gap.
// 我们放进**上游已拥有**的目录里的文件。`.github/` 是第一个，而上游往那儿加东西时**没有东西告诉我们**
// —— 与界面漂移那道缺口同形。
const topDirs = new Set()
for (const p of added) {
  const top = p.split('/')[0]
  if (gitOk(['cat-file', '-e', `${pin}:${top}`])) topDirs.add(top)
}
if (topDirs.size) {
  console.log()
  console.log('added files that land in a directory upstream already owns:')
  for (const top of [...topDirs].sort()) {
    const n = added.filter((p) => p.startsWith(top + '/')).length
    console.log(`  ${String(n).padStart(4)}  ${top}/`)
  }
}

// The UI is a different upstream and is measured by what our patch reaches, not by a tree diff.
// 界面是**另一个上游**，量的是我们的补丁够到哪些文件，而不是比树。
const PATCH = join(REPO, 'keelbase-ui/host-patches/0001-our-ui.patch')
if (existsSync(PATCH)) {
  const reached = new Set()
  for (const line of readFileSync(PATCH, 'utf8').split('\n')) {
    const m = line.match(/^\+\+\+ b\/(.+)$/)
    if (m && m[1] !== '/dev/null') reached.add(m[1])
  }
  console.log()
  console.log(`UI patch (a different upstream): reaches ${reached.size} files under yudao-ui-admin-vue3`)
}

// Growth is the signal, not the absolute number.
// 信号是**变大**，不是绝对值。
const grew = []
if (deleted.length > BASELINE.deleted) grew.push(`deleted ${BASELINE.deleted} → ${deleted.length}`)
if (added.length > BASELINE.added) grew.push(`added ${BASELINE.added} → ${added.length}`)
if (modified.length > BASELINE.modified) grew.push(`modified ${BASELINE.modified} → ${modified.length}`)
console.log()
if (grew.length) {
  console.log(`footprint GREW since ${BASELINE.takenOn}: ${grew.join(' · ')}`)
  console.log('that is a reminder to look, not a failure — this patrol has no red light on purpose')
} else {
  console.log(`footprint is within the ${BASELINE.takenOn} reading — nothing to look at`)
}
