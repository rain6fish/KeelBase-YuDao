// SPDX-License-Identifier: Apache-2.0
//
// 渲染级文案闸门要看得见**算出来的**记号：`{{ '**' + t('x') }}`、``:title="`${n}**`"`` 这些在渲染那一刻才
// 产生字面的写法，静态文本与静态属性两条路径都看不见。2026-10-28 把它们种进模板后，闸门仍 exit 0。
//
// 这条测试驱动的是**闸门本身**（不是它里面的某个函数）：临时目录里搭一份只够它跑的 UI 样子 —— 符号链接
// 过去的 `node_modules`、铺好的两份词条、这一层发布的那些 `.vue` —— 然后逐例改其中一个模板、跑闸门、看
// 退出码与点名。四种写法各一例：两种「算出来的记号」必须红，模板字面量的定界符不算文本（绿），字符串里的
// 反引号算（红）。红例之后恢复原样再跑一次，确认绿是恢复出来的而不是从没红过。
//
// 它要一个**铺好增量**的 UI 检出（`target/yudao-ui-admin-vue3`，或 `KEELBASE_YUDAO_UI`）：闸门要用那里的
// Vue 工具链，`node_modules` 只有装上才有。没有检出时这条测试**红**并直说要跑哪条命令 —— 不悄悄跳过，
// 否则「没跑」会读成「过了」。CI 上它挂在 `rendered-copy` job 里，那一步铺完增量，故不会缺。
//
//    node --test scripts/rendered-copy-expressions.test.mjs
//
// ── English ─────────────────────────────────────────────────────────────────────
//
// The rendered-copy gate has to see **computed** markers: `{{ '**' + t('x') }}`, ``:title="`${n}**`"`` and
// the like produce their literal only at render time, and neither the static text path nor the static
// attribute path can see them. Seeded into a template on 2026-10-28, the gate still exited 0.
//
// This test drives **the gate** (not some function inside it): it builds, in a temp directory, just enough
// of a UI for the gate to run — `node_modules` linked in, the two laid-down bundles, the `.vue` files this
// layer ships — then rewrites one template per case, runs the gate, and reads its exit code and the file
// it names. Four cases: two computed markers that must go red, a template literal whose backticks are only
// syntax (green), and a backtick inside a string (red). After the red cases the fixture is restored and the
// gate re-run, so the green is a restoration rather than a run that never went red.
//
// It needs a UI checkout **with the delta laid down** (`target/yudao-ui-admin-vue3`, or
// `KEELBASE_YUDAO_UI`): the gate needs that checkout's Vue toolchain, and `node_modules` only exists after
// an install. With no checkout this test goes **red** and names the command to run — never a quiet skip,
// which would read as "passed". In CI it runs in the `rendered-copy` job, which lays the delta down, so it
// is never missing there.
import test from 'node:test'
import assert from 'node:assert/strict'
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'
import { tmpdir } from 'node:os'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const GATE = join(ROOT, 'scripts', 'check-rendered-copy.mjs')

/**
 * 检出位置与闸门同一顺序（`KEELBASE_YUDAO_UI` → `<repo>/target/…`），多一步：在**工作树**里 `target/` 通常
 * 空着 —— 真正铺过增量的是主检出，`git worktree list` 的第一行就是它。这一步只挪默认值，测试的判据不变。
 *
 * The checkout, in the gate's order (`KEELBASE_YUDAO_UI` → `<repo>/target/…`), with one extra step: in a
 * **worktree** `target/` is usually empty — the laid-down checkout is the primary one, which the first line
 * of `git worktree list` names. This only moves the default; what the test asserts is unchanged.
 */
function defaultUi() {
  const local = join(ROOT, 'target', 'yudao-ui-admin-vue3')
  if (existsSync(join(local, 'node_modules'))) return local
  const listed = spawnSync('git', ['-C', ROOT, 'worktree', 'list', '--porcelain'], { encoding: 'utf8' })
  const primary = listed.status === 0 ? /^worktree (.+)$/m.exec(listed.stdout)?.[1] : null
  return primary ? join(primary, 'target', 'yudao-ui-admin-vue3') : local
}

// 必须是绝对路径：临时目录里的 `node_modules` 是符号链接，相对目标会相对**链接所在目录**去解析，指向别处。
// It has to be absolute: the fixture's `node_modules` is a symlink, and a relative target resolves against
// the **link's own** directory, pointing somewhere else.
const UI = resolve(process.env.KEELBASE_YUDAO_UI || defaultUi())

/** 这一层发布的每个 `.vue`，相对检出根 —— 与闸门自己的枚举同形。 / Every `.vue` this layer ships, relative to the checkout — the gate's own enumeration. */
function deltaVueFiles() {
  const files = new Set()
  const overlay = join(ROOT, 'keelbase-ui', 'host-overlay')
  const walk = (dir) => {
    for (const entry of readdirSync(dir)) {
      const path = join(dir, entry)
      if (statSync(path).isDirectory()) walk(path)
      else if (entry.endsWith('.vue')) files.add(relative(overlay, path))
    }
  }
  walk(overlay)
  const patches = join(ROOT, 'keelbase-ui', 'host-patches')
  for (const patch of readdirSync(patches).filter((f) => f.endsWith('.patch'))) {
    for (const line of readFileSync(join(patches, patch), 'utf8').split('\n')) {
      const match = /^\+\+\+ b\/(.+\.vue)\r?$/.exec(line)
      if (match) files.add(match[1])
    }
  }
  return [...files]
}

/**
 * 搭一份闸门跑得动的 UI 样子。`node_modules` 用 junction 指过去（Windows 下不需要管理员权限，也不需要
 * 拷 20MB），其余只拷它真的会读的那些文件。
 *
 * Build enough of a UI for the gate to run. `node_modules` is a junction to the checkout's own (no admin
 * rights needed on Windows, and no 20 MB copy); everything else is copied only where the gate reads it.
 */
function buildFixture() {
  const fixture = mkdtempSync(join(tmpdir(), 'rendered-copy-'))
  symlinkSync(join(UI, 'node_modules'), join(fixture, 'node_modules'), 'junction')

  const locales = join(UI, 'src', 'locales')
  cpSync(locales, join(fixture, 'src', 'locales'), { recursive: true, filter: (p) => p === locales || p.endsWith('.ts') })

  const files = deltaVueFiles()
  for (const rel of files) {
    mkdirSync(dirname(join(fixture, rel)), { recursive: true })
    cpSync(join(UI, rel), join(fixture, rel))
  }
  return { fixture, files }
}

function runGate(fixture) {
  const result = spawnSync(process.execPath, [GATE, fixture], { encoding: 'utf8' })
  return { code: result.status, output: `${result.stdout}${result.stderr}` }
}

let fixture = null
let files = null
let seeded = null

test('the checked-out tree is arranged the way this test needs it', () => {
  try {
    statSync(join(UI, 'node_modules', 'vue', 'package.json'))
  } catch {
    assert.fail(
      `no Vue toolchain under ${UI} — the gate resolves vue, vue-i18n and the Vue compilers from there. ` +
        'Run `bash scripts/build-host-ui.sh` (or pass KEELBASE_YUDAO_UI=<checkout>) and re-run this test.',
    )
  }
  const missing = deltaVueFiles().filter((rel) => {
    try {
      statSync(join(UI, rel))
      return false
    } catch {
      return true
    }
  })
  assert.deepEqual(
    missing,
    [],
    'the delta is not laid down in the checkout — run `bash scripts/build-host-ui.sh` and re-run this test',
  )
})

test('the gate is green on an unmodified fixture, then red on computed markers, then green again', (t) => {
  const built = buildFixture()
  fixture = built.fixture
  files = built.files
  t.after(() => {
    if (fixture) rmSync(fixture, { recursive: true, force: true })
  })

  const clean = runGate(fixture)
  assert.equal(clean.code, 0, `the unmodified fixture must be green:\n${clean.output}`)

  // 种在哪儿不重要 —— 取排序后第一个带 `<template>` 的文件，随覆盖目录怎么改名都不会失准。
  // Where it is seeded does not matter: the first file (in sort order) with a `<template>`, so renaming
  // an overlay page cannot silently point this at nothing.
  seeded = [...files].sort().find((rel) => readFileSync(join(fixture, rel), 'utf8').includes('<template'))
  assert.ok(seeded, 'some .vue this layer ships has a <template>')
  const original = readFileSync(join(fixture, seeded), 'utf8')

  /** 在 `<template>` 开标签之后插一行，返回它在文件里的行号。 / insert one line after the opening tag, return its line number. */
  const seed = (markup) => {
    const open = original.indexOf('<template')
    const line = original.slice(0, open).split('\n').length
    const next = open === -1 ? null : original.slice(open)
    const end = next === null ? -1 : next.indexOf('>')
    assert.ok(end !== -1, `${seeded} has an opening <template> tag`)
    const at = open + end + 1
    const seededSource = `${original.slice(0, at)}\n  ${markup}${original.slice(at)}`
    writeFileSync(join(fixture, seeded), seededSource)
    return line + 1
  }
  const restore = () => writeFileSync(join(fixture, seeded), original)

  const cases = [
    {
      name: 'an interpolation that adds a marker',
      markup: `<div>{{ '**' + t('keelbase.common.refresh') }}</div>`,
      red: true,
      why: 'an interpolation',
    },
    {
      name: 'a bound attribute built from a template literal',
      markup: '<div :title="`${n}**`"></div>',
      red: true,
      why: 'a v-bind value',
    },
    {
      name: 'a backtick inside a string literal',
      markup: '<div :title="\'a`b\'"></div>',
      red: true,
      why: 'a v-bind value',
    },
    {
      name: 'a template literal whose backticks are only syntax',
      markup: '<div :title="`id_${n}`"></div>',
      red: false,
      why: 'the delimiters are syntax, not copy',
    },
  ]

  for (const { name, markup, red, why } of cases) {
    const line = seed(markup)
    const result = runGate(fixture)
    if (red) {
      assert.equal(result.code, 1, `${name} must turn the gate red:\n${result.output}`)
      const where = `${seeded.replace(/\\/g, '/')}:${line}`
      assert.ok(result.output.includes(where), `${name} must be named as ${where}:\n${result.output}`)
      assert.ok(result.output.includes(why), `${name} must say what carried it (${why}):\n${result.output}`)
    } else {
      assert.equal(result.code, 0, `${name} must stay green — flagging it would be a false positive:\n${result.output}`)
    }
    restore()
    const after = runGate(fixture)
    assert.equal(after.code, 0, `restoring ${name} must return the gate to green:\n${after.output}`)
  }
})
