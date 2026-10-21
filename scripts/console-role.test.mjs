// SPDX-License-Identifier: Apache-2.0
//
// `audit_reader` 关掉写控件，admin 与其余角色不变 —— 这是角色判据本身的可执行真值表。
//
//   node --test scripts/console-role.test.mjs
//
// 为什么不挂 SFC：本仓不带 Vue 测试运行器（见 `boundary-page.test.mjs` 的说明）。判据被拆到
// `consoleRoleCore.mjs` 正是为了能在这里**真跑**；页面模板是否都受它管，由
// `check-audit-reader-write-controls.mjs` 在源码层钉住。两者合起来覆盖「读者挂载后无写控件」。
//
// English:
//
// `audit_reader` turns write controls off; admin and every other role stay as they were — this is the
// executable truth table of the role predicate.
//
// Why not mount the SFC: this repository ships no Vue test runner (see `boundary-page.test.mjs`). The
// predicate was split into `consoleRoleCore.mjs` precisely so it can be **run** here; whether every
// template obeys it is pinned at the source level by `check-audit-reader-write-controls.mjs`. Together
// they cover "the reader mounts with no write controls".

import test from 'node:test'
import assert from 'node:assert/strict'
import { canWriteWithRoles, AUDIT_READER_ROLE } from '../keelbase-ui/host-overlay/src/keelbase/consoleRoleCore.mjs'

test('an audit reader gets no write control', () => {
  assert.equal(canWriteWithRoles(['audit_reader']), false)
  assert.equal(canWriteWithRoles(['audit_reader', 'common']), false)
  assert.equal(AUDIT_READER_ROLE, 'audit_reader')
})

test('the administrator keeps every write control', () => {
  assert.equal(canWriteWithRoles(['super_admin']), true)
  assert.equal(canWriteWithRoles(['super_admin', 'tenant_admin']), true)
})

test('every other role is unchanged', () => {
  assert.equal(canWriteWithRoles(['common']), true)
  assert.equal(canWriteWithRoles(['auditor']), true, 'only the exact code `audit_reader` is the reader')
  assert.equal(canWriteWithRoles([]), true, 'a user with no role keeps the page as it was')
  assert.equal(canWriteWithRoles(null), true)
  assert.equal(canWriteWithRoles(undefined), true)
})
