#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// YD-14's gate: the **second** module upstream wrote, brought under the same four advices CRM is held
// by. Its question is not "does it work" but "what does a second module cost" — so what it asserts is
// deliberately the same shape `run-crm-customer-scenarios.mjs` asserts, run against a module whose
// ownership is spelled differently.
//
//   bash scripts/run-server.sh &            # a running stack, PostgreSQL + redis
//   node scripts/run-bpm-oa-leave-scenarios.mjs
//
// The module is Yudao's own BPM, and the slice is its leave request (`bpm_oa_leave`) — the one surface
// in that module that carries a per-row owner. The task and process-instance surfaces take their
// ownership from the engine (`assignee` / `startUserId`), not from a column, and would need a **second**
// scope machine; they are out of this slice on purpose, and that finding is the point rather than a
// gap. See `keelbase-docs/KEELBASE-MODULE-ONBOARDING.md` §4.1.
//
// What is different from CRM, and therefore the per-module reading:
//
//   * the owner is an **argument** (`getLeavePage(userId, …)`, `createLeave(userId, …)`) rather than a
//     field on the VO, so the advice rewrites the argument;
//   * the owner **column** is `user_id`, not `owner_user_id`;
//   * the **create** path calls the process engine and this host has no process definition deployed, so
//     no tool is declared for it and no scenario here can reach it — recorded, not looked past.
//
// YD-14 的门禁：**第二个**上游写的模块，被与 CRM 同样的四条通知管住。它问的不是「能不能跑」，而是「第二个模块
// 要多少钱」——故它断言的东西**刻意与 `run-crm-customer-scenarios.mjs` 同形**，只是跑在一个归属拼法不同的模块上。
//
//   bash scripts/run-server.sh &            # 一套跑着的栈：PostgreSQL + redis
//   node scripts/run-bpm-oa-leave-scenarios.mjs
//
// 该模块是芋道自己的 BPM，切片是它的请假单（`bpm_oa_leave`）——那个模块里**唯一**逐行带属主的业务面。任务面与
// 流程实例面的归属取自**引擎**（`assignee` / `startUserId`），不来自某一列，需要**第二台**范围机；它们**刻意**
// 不在本切片，而那个发现**正是要点**、不是缺口。见 `keelbase-docs/KEELBASE-MODULE-ONBOARDING.md` §4.1。
//
// 与 CRM 不同、因而属**逐模块的读**的地方：
//
//   * 属主是**入参**（`getLeavePage(userId, …)`、`createLeave(userId, …)`），不是 VO 上的字段，故通知改的是实参；
//   * 属主**列**是 `user_id`，不是 `owner_user_id`；
//   * **创建**那条路调**流程引擎**，而本宿主没有部署任何流程定义，故**没有**为它声明工具、本文件任何情境也够不到它
//     ——记下，而不是绕过去不看。
import { execFileSync } from 'node:child_process';

const HOST = process.env.HOST_URL || 'http://127.0.0.1:48080';
const PG = process.env.PG_CONTAINER || 'keelbase-yudao-pg';
const DB = process.env.PG_DB || 'ruoyi-vue-pro';
const TENANT = process.env.TENANT_ID || '1';
const SECOND_USER = process.env.SECOND_USER || 'gatesales';
const SECOND_PASS = process.env.SECOND_PASS || 'gate-pass-1234';
const LEAVE = process.env.BPM_LEAVE_TABLE || 'bpm_oa_leave';
const RUNNING = 1; // BpmTaskStatusEnum.RUNNING / 审批中
const APPROVE = 2; // BpmTaskStatusEnum.APPROVE / 审批通过

let pass = 0;
let fail = 0;
const ok = (name) => { console.log(`  ok    ${name}`); pass += 1; };
const bad = (name, expected, got) => { console.log(`  FAIL  ${name} — expected [${expected}], got [${got}]`); fail += 1; };
const check = (name, expected, got) => (String(expected) === String(got) ? ok(name) : bad(name, expected, got));
const checkNot = (name, forbidden, got) =>
  String(got).includes(forbidden) ? bad(name, `must not contain ${forbidden}`, got) : ok(name);
const note = (name, got) => console.log(`  note  ${name} → ${got}`);

const psql = (sql) =>
  execFileSync('docker', ['exec', '-i', '-e', 'PGPASSWORD=password', PG, 'psql', '-U', 'root', '-d', DB, '-tAc', sql], { encoding: 'utf8' }).trim();

async function api(path, { method = 'GET', token, body } = {}) {
  const res = await fetch(`${HOST}/admin-api${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', 'tenant-id': TENANT, ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {})
  });
  return res.json();
}

async function login(username, password) {
  const r = await api('/system/auth/login', { method: 'POST', body: { username, password } });
  if (r.code !== 0) throw new Error(`login(${username}) -> ${r.code}: ${r.msg}`);
  return r.data.accessToken;
}

async function chat(token, message, extra = {}) {
  const res = await fetch(`${HOST}/ai/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ message, conversationId: `gate-${Date.now()}`, ...extra })
  });
  const json = await res.json();
  return json.data || json;
}

async function confirm(token, confirmationToken, decision) {
  const res = await fetch(`${HOST}/ai/confirmations/${confirmationToken}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ decision })
  });
  const json = await res.json();
  return json.data || json;
}

// Same two reads the CRM gate uses, and for the same reported reason: the caller's confirmation centre
// answers 500 on this host (its query names a `mode` column this schema lacks), and the chat answer is
// the frozen `chat-response` with no `status`, `data` or `token`. So the waiting row is read from the
// runtime's own table and "did it run" from the audit trail.
// 与 CRM 门禁相同的两处读法，理由也一样：调用方的确认中心在本宿主答 500（它查一个本 schema 没有的 `mode` 列），
// 而聊天答案是冻结的 `chat-response`、不带 `status`/`data`/`token`。故「等待中的那一行」读运行时自己的表、
// 「跑没跑」读审计轨。
const pendingToken = (operatorId) =>
  psql(`select token from confirmation_requests where operator_id = '${operatorId}' and status = 'pending' order by id desc limit 1`) || undefined;

const pendingCount = (operatorId) =>
  Number(psql(`select count(*) from confirmation_requests where operator_id = '${operatorId}' and status = 'pending'`));

const ranAs = (tool) =>
  Number(psql(`select count(*) from ai_audit_logs where tool = '${tool}' and outcome = 'executed'`));

// ── 0) the stack answers at all ────────────────────────────────────────────────────────────────────
console.log('== 前置 ==');
psql('truncate table ai_audit_logs');
check('/app/capabilities 200', '200', (await fetch(`${HOST}/app/capabilities`)).status);

const admin = await login('admin', 'admin123');
ok('admin 登录');

console.log('== 前置 · 第二个调用方 ==');
let secondId = psql(`select id from system_users where username = '${SECOND_USER}' and deleted = 0 limit 1`);
if (!secondId) {
  const created = await api('/system/user/create', {
    method: 'POST',
    token: admin,
    body: { username: SECOND_USER, nickname: SECOND_USER, password: SECOND_PASS, deptId: 100, postIds: [] }
  });
  if (created.code !== 0) throw new Error(`create user -> ${created.code}: ${created.msg}`);
  secondId = created.data;
  ok(`建第二个用户 id=${secondId}`);
} else {
  ok(`复用第二个用户 id=${secondId}`);
}
{
  const roles = psql(`select id from system_role where code = 'common' and deleted = 0 limit 1`);
  await api('/system/permission/assign-user-role', { method: 'POST', token: admin, body: { userId: Number(secondId), roleIds: [Number(roles)] } });
}
const second = await login(SECOND_USER, SECOND_PASS);
ok('第二个调用方登录');

// ── the rows, written straight into the module's own table ─────────────────────────────────────────
console.log('== 场景 1 · 上游模块自己的表 ==');
const marker = `gate-${Date.now()}`;
const adminMarker = `${marker}-admin`;
const salesMarker = `${marker}-sales`;
// `tenant_id` is not optional here either: `BpmOALeaveDO` extends `BaseDO` and carries no `@TenantIgnore`,
// so the host's tenant interceptor appends `tenant_id = ?` to every path through this table.
// `tenant_id` 在这里也不是可选的：`BpmOALeaveDO` 继承 `BaseDO` 且没带 `@TenantIgnore`，故宿主的租户拦截器给这张表
// 的每条路都加 `tenant_id = ?`。
psql(`insert into ${LEAVE}(id, user_id, type, reason, day, status, deleted, tenant_id, create_time, update_time)`
  + ` values (nextval('${LEAVE}_seq'), 1, 1, '${adminMarker}', 1, ${RUNNING}, 0, ${TENANT}, now(), now())`);
psql(`insert into ${LEAVE}(id, user_id, type, reason, day, status, deleted, tenant_id, create_time, update_time)`
  + ` values (nextval('${LEAVE}_seq'), ${secondId}, 1, '${salesMarker}', 1, ${RUNNING}, 0, ${TENANT}, now(), now())`);
const adminRowId = psql(`select id from ${LEAVE} where reason = '${adminMarker}'`);
const salesRowId = psql(`select id from ${LEAVE} where reason = '${salesMarker}'`);
check('admin 那一行已种', true, adminRowId !== '');
check('第二个调用方那一行已种', true, salesRowId !== '');
const statusOf = (id) => psql(`select status from ${LEAVE} where id = ${id}`);

console.log('== 场景 2 · 身份传递：读被路由到本模块的工具 ==');
{
  // What is asserted is that the turn **ran**, read from the trail: the page narrowing itself lives on
  // the advice, and its effect is asserted where it shows — scenario 4's refusal. The returned rows
  // cannot be asserted here for the reason the CRM gate records: core 0.1.5's answer carries no `data`.
  // 断言的是这一回合**跑过了**（从审计轨读）：分页收束本身在通知上，它的效果在**它显形的地方**断言——情境 4 的
  // 拒绝。返回的行在这里断言不了，理由与 CRM 门禁记的一样：core 0.1.5 的答案不带 `data`。
  const a = await chat(admin, '看我的请假');
  check('admin 触发 BPM 工具', 'list_my_oa_leaves', (a.toolCalls || [])[0]);
  check('admin 的执行状态（审计轨）', 1, ranAs('list_my_oa_leaves'));

  const s = await chat(second, '看我的请假');
  check('第二个调用方触发同一个工具', 'list_my_oa_leaves', (s.toolCalls || [])[0]);
  check('它的执行状态（审计轨）', 2, ranAs('list_my_oa_leaves'));
}

console.log('== 场景 3 · 高风险写入被扣下，批准后落库 ==');
{
  const held = pendingCount(secondId);
  // The row travels as `customerId` — the one structured slot the runtime's chat request declares, and
  // it is named for CRM. A second module has no slot of its own; that leak is this module's cost and is
  // recorded in the tool face's class comment rather than worked around quietly.
  // 那一行以 `customerId` 传递——运行时的聊天请求**唯一**声明的结构化槽位，而它**以 CRM 命名**。第二个模块没有
  // 自己的槽位；这处泄漏是本模块的代价，记在工具面的类注释里，而不是默默绕过。
  await chat(second, '审批请假', { customerId: Number(salesRowId) });
  check('写被扣下', held + 1, pendingCount(secondId));
  check('未改（仍是审批中）', RUNNING, statusOf(salesRowId));
  const proposedToken = pendingToken(secondId);
  checkNot('扣下时给出 token', 'undefined', proposedToken);

  const outcome = await confirm(second, proposedToken, 'approve');
  check('批准后执行', 'executed', outcome.status);
  check('状态已改', APPROVE, statusOf(salesRowId));
}

console.log('== 场景 4 · 越权写：批准也买不到 ==');
{
  // Same call, against another caller's row. The approval settles the risk question and nothing else:
  // the row-scope question is answered lower down, at the module's own seam — this advice asks only
  // the applicant question.
  // 同一次调用，打向**别人的**行。批准了结的是**风险**问题、仅此而已：行范围的问题在更下面、在模块自己的接缝上
  // 回答——本通知只问**申请人**那一问。
  const hijackHeld = pendingCount(secondId);
  await chat(second, '审批请假', { customerId: Number(adminRowId) });
  check('改别人的行也被扣下', hijackHeld + 1, pendingCount(secondId));
  const hijackDone = await confirm(second, pendingToken(secondId), 'approve');
  note('越权写批准后的结局', hijackDone.status || JSON.stringify(hijackDone).slice(0, 120));
  check('批准了也没执行', true, hijackDone.status !== 'executed');
  check('别人的行一字未改', RUNNING, statusOf(adminRowId));
}

console.log('== 场景 5 · 审计链与被记下的拒绝 ==');
{
  const rows = Number(psql('select count(*) from ai_audit_logs'));
  check('审计行在（>0）', true, rows > 0);
  const verify = await (await fetch(`${HOST}/audit/verify`, { headers: { Authorization: `Bearer ${admin}` } })).json();
  check('链校验通过', true, verify.data && verify.data.valid);
  const refusals = Number(psql(`select count(*) from ai_audit_logs where tool = 'approve_oa_leave' and outcome = 'refused'`));
  check('被拒的那次写在案', true, refusals > 0);
}

console.log(`\n${pass} ok / ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
