// SPDX-License-Identifier: Apache-2.0
//
// 首页右上方那两格「接口调用 / 接口异常」读的是基础设施的日志，而读者角色没有那两个权限。没有权限的账号
// 必须**不发**这两条请求、也**不画**这两格 —— 否则那两条读会以 `{code:403}` 回来，宿主界面把「没有该操作
// 权限」弹成一条通知，读者一进首页就被吓一跳（M7 第三位读者场 F-12 的原话）。
//
//     node --test scripts/home-page.test.mjs
//     HOME_PAGE_PATCH=<别的补丁> node --test scripts/home-page.test.mjs   # 反向证明用
//
// 断言停在补丁层：这个页面住在一次性 UI 检出（`target/`）里，本仓只保有一份补丁；把它补丁掉、把守卫删掉
// 或把两格重新变成无条件渲染，都会红。
//
// The two figures at the top right of the home page read the infrastructure logs, which the reader
// roles may not read. An account without the permission must send neither request and draw neither
// figure — otherwise those reads answer `{code:403}` and the host console turns "no permission" into a
// toast the reader meets on landing (finding F-12, from M7's third reader session).
//
//     node --test scripts/home-page.test.mjs
//     HOME_PAGE_PATCH=<another patch> node --test scripts/home-page.test.mjs   # reverse proof
//
// The assertions stop at the patch layer: the page lives in a disposable UI checkout (`target/`) and
// this repository keeps only the patch. Dropping the guard, or making the two figures unconditional
// again, turns this red.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
// 反向证明用环境变量换掉补丁：`--test` 会把 `--` 之后的参数当成**另一个测试文件**，故不走命令行参数。
// The reverse proof swaps the patch through an environment variable: `--test` treats anything after `--`
// as another test file, so a command-line option is not available here.
const PATCH = process.env.HOME_PAGE_PATCH || join(ROOT, 'keelbase-ui/host-patches/0001-our-ui.patch')

const raw = readFileSync(PATCH, 'utf8').replace(/\r\n/g, '\n')

/** 取 `src/views/Home/Index.vue` 那一段补丁（到下一个 `diff --git` 为止）。 / the patch section for Home/Index.vue. */
function sectionOf(text) {
  const start = text.indexOf('diff --git a/src/views/Home/Index.vue')
  assert.notEqual(start, -1, 'the patch carries no section for src/views/Home/Index.vue')
  const next = text.indexOf('\ndiff --git ', start + 1)
  return text.slice(start, next === -1 ? undefined : next)
}

/**
 * 把一个文件的补丁段重放成「打完补丁之后的样子」：只取上下文行（` `）与新增行（`+`）。
 * 它不校验行号 —— 这里要的是内容，行号由 `git apply --check` 在构建里管。
 *
 * Replay one file's patch section into "what the file looks like after the patch": context lines (` `)
 * and added lines (`+`). Line numbers are not checked here — this test is about content, and the build's
 * `git apply --check` owns the numbering.
 */
function applied(text) {
  const out = []
  for (const line of sectionOf(text).split('\n')) {
    if (line.startsWith('@@') || line.startsWith('---') || line.startsWith('+++') || line.startsWith('diff --git')) continue
    if (line.startsWith('+')) out.push(line.slice(1))
    else if (line.startsWith(' ')) out.push(line.slice(1))
    else if (line.startsWith('-')) continue
    else if (line.startsWith('\\')) continue
    else out.push(line)
  }
  return out.join('\n')
}

const file = applied(raw)

test('the reader-visible figures ask the two infra-log permissions first', () => {
  assert.match(file, /checkPermi\(\['infra:api-access-log:query'\]\)/,
    'the permission for the access log is never asked')
  assert.match(file, /checkPermi\(\['infra:api-error-log:query'\]\)/,
    'the permission for the error log is never asked')
  assert.match(file, /const canSeeApiLogs =/,
    'the two permission answers are not kept in one flag')
})

test('neither log is requested when the permission is absent', () => {
  // 两条读必须落在 `canSeeApiLogs` 的三元里；无条件的 `await Promise.all([` 就是没有守卫。
  // Both reads must sit inside the canSeeApiLogs ternary; a bare `await Promise.all([` is no guard.
  assert.match(file, /const \[access, errors\] = canSeeApiLogs\s*\n\s*\? await Promise\.all\(\[/,
    'the two log reads are not guarded by canSeeApiLogs')
  assert.doesNotMatch(file, /=\s*await Promise\.all\(\[\s*\n\s*getApiAccessLogPage/,
    'the access log is still requested unconditionally')
})

test('the two figures are not drawn for an account that may not read them', () => {
  const guard = file.indexOf('<template v-if="canSeeApiLogs">')
  const access = file.indexOf("t('workplace.apiAccess')")
  const error = file.indexOf("t('workplace.apiError')")
  const close = file.indexOf('</template>', guard)
  assert.notEqual(guard, -1, 'the template has no v-if for the two figures')
  assert.ok(access > guard && access < close, 'the access figure is drawn outside the guard')
  assert.ok(error > guard && error < close, 'the error figure is drawn outside the guard')
})
