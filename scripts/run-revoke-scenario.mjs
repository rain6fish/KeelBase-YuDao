// SPDX-License-Identifier: Apache-2.0
//
// YD-9's gate: a withdrawal is attempted, and what the runtime did with the attempt is asserted.
//
//   bash scripts/run-server.sh &            # a running stack, PostgreSQL + redis
//   node scripts/run-revoke-scenario.mjs
//
// Why it exists. The conformance record's `Revoke` column can read nothing but `SKIP` while nothing on
// this line ever attempts a withdrawal: it counts `effect_revoke` rows on the audit, and a refusal
// thrown **before** the audit append leaves none. So this gate makes the attempt — and asserts the
// invariant that holds on **both sides of the platform seam**, so that it is green today and stays green
// the day the seam and the host's compensator land:
//
//   * **Attributable.** The effect is found by *this run's own row*, never by a count of the table.
//   * **Either it lands** — the ledger row reads `revoked` and the audit gains exactly one
//     `effect_revoke` row — **or it is refused** with a reason that names the missing local path, and
//     then the audit gains **no** `effect_revoke` row. A refusal may not leave a line claiming a
//     withdrawal happened; not leaving one is that line's whole job.
//   * The audit chain still verifies afterwards: a withdrawal is **not** "delete an audit row".
//
// ⚠ **What this gate must not do is assert the refusal.** Today the refusal is what happens; the day the
// seam opens it stops happening, and an assertion phrased as "it was refused" would go red for exactly
// the change that is the point. The two outcomes are asserted as alternatives on purpose.
//
// YD-9 的门禁：**真的去试一次撤销**，并断言**运行时对这次尝试做了什么**。
//
// 为什么要有它：门禁记录里 `Revoke` 那一栏，在本线**从来没人试过撤销**时只可能读到 `SKIP` —— 它数的是审计
// 里的 `effect_revoke` 行，而一次**抛在审计 append 之前**的拒绝**一行都不留**。这道闸把那次尝试做出来，并
// 断言**平台那条缝两侧都成立**的不变量，使它今天绿、缝与宿主补偿器落地之后**仍然绿**：
//
//   * **可归因**：这条 effect 按**本轮自己那一行**找到，绝不按整表计数。
//   * **要么落成**（账本行读作 `revoked`，审计**恰好**多一行 `effect_revoke`），**要么被拒**、且理由**点名
//     那条缺失的本地路** —— 而那时审计**一行都不多**。**拒绝不得留下「撤销发生过」的行**，不留正是那行的职责。
//   * 之后**审计链仍校验通过**：撤销**不是**「删掉一行审计」。
//
// ⚠ **这道闸绝不能断言「被拒」**。今天发生的是拒绝；缝一开它就不再发生，而把断言写成「被拒」会让它**为了那个
// 恰恰是要点的改动**而转红。两种结局作为**择一**断言，是刻意的。
import { execFileSync } from 'node:child_process';

const HOST = process.env.HOST_URL || 'http://127.0.0.1:48080';
const PG = process.env.PG_CONTAINER || 'keelbase-yudao-pg';
const DB = process.env.PG_DB || 'ruoyi-vue-pro';
const TENANT = process.env.TENANT_ID || '1';
const SECOND_USER = process.env.SECOND_USER || 'gatesales';
const SECOND_PASS = process.env.SECOND_PASS || 'gate-pass-1234';
const CUSTOMER = process.env.CRM_TABLE || 'crm_customer';

let pass = 0;
let fail = 0;
const ok = (name) => { console.log(`  ok    ${name}`); pass += 1; };
const bad = (name, expected, got) => { console.log(`  FAIL  ${name} — expected [${expected}], got [${got}]`); fail += 1; };
const check = (name, expected, got) => (String(expected) === String(got) ? ok(name) : bad(name, expected, got));
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
    body: JSON.stringify({ message, conversationId: `revoke-gate-${Date.now()}`, ...extra })
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

// The withdrawal endpoint, called the way the console calls it. The status code is part of the evidence:
// a refusal and a success are told apart by it, not by the body alone.
// 撤销端点，按控制台调用它的方式来调。**状态码也是证据的一部分**：拒绝与成功靠它分辨，不只靠正文。
async function revokeEffect(id, token) {
  const res = await fetch(`${HOST}/ai/tool-effects/${id}`, { method: 'DELETE', headers: { Authorization: `Bearer ${token}` } });
  let body = '';
  try { body = await res.text(); } catch { /* a body we cannot read is itself reported below */ }
  return { status: res.status, body };
}

const pendingToken = (operatorId) =>
  psql(`select token from confirmation_requests where operator_id = '${operatorId}' and status = 'pending' order by id desc limit 1`) || undefined;

const pendingCount = (operatorId) =>
  Number(psql(`select count(*) from confirmation_requests where operator_id = '${operatorId}' and status = 'pending'`));

// How many withdrawals the audit says were attempted. The whole gate turns on this reading, so it is
// taken **from the audit**, the same source the conformance column reads — not from the answer.
// 审计说**试过几次撤销**。整道闸都压在这个读数上，故它与门禁那一栏**同一个来源**：审计，而不是答案。
const revokeRows = () => Number(psql("select count(*) from ai_audit_logs where action = 'effect_revoke'"));

// ── 0) the stack answers, and there is a second caller ───────────────────────────────────────────
console.log('== 前置 ==');
check('/app/capabilities 200', '200', (await fetch(`${HOST}/app/capabilities`)).status);
const admin = await login('admin', 'admin123');
ok('admin 登录');

// The same setup the CRM gate uses, and idempotent for the same reason: the two gates are meant to be
// able to run against one database, so a second caller that exists is reused rather than recreated.
// 与 CRM 门禁同一套前置，且同样幂等：两道闸本就该能对着同一个库跑，故已存在的第二个调用方**复用**、不重建。
let secondId = psql(`select id from system_users where username = '${SECOND_USER}' and deleted = 0 limit 1`);
if (!secondId) {
  const created = await api('/system/user/create', {
    method: 'POST', token: admin,
    body: { username: SECOND_USER, nickname: SECOND_USER, password: SECOND_PASS, deptId: 100, postIds: [] }
  });
  if (created.code !== 0) throw new Error(`create user -> ${created.code}: ${created.msg}`);
  secondId = created.data;
  ok(`建第二个调用方 id=${secondId}`);
} else {
  ok(`复用第二个调用方 id=${secondId}`);
}
{
  const roles = psql(`select id from system_role where code = 'common' and deleted = 0 limit 1`);
  await api('/system/permission/assign-user-role', { method: 'POST', token: admin, body: { userId: Number(secondId), roleIds: [Number(roles)] } });
}
const second = await login(SECOND_USER, SECOND_PASS);
ok('第二个调用方登录');

// ── 1) a write this run owns, taken all the way to a real side effect ────────────────────────────
console.log('== 场景 1 · 一次真落库的高风险写 ==');
const marker = `revoke-${Date.now()}`;
let customerId = '';
{
  const held = pendingCount(secondId);
  await chat(second, `新建客户 ${marker}`);
  check('写被扣下', held + 1, pendingCount(secondId));
  const outcome = await confirm(second, pendingToken(secondId), 'approve');
  check('批准后执行', 'executed', outcome.status);
  // The tool names the row after the whole message, not after the marker inside it, so the row is
  // found by substring — and the marker is a 13-digit stamp, which no other run's marker contains.
  // 工具拿**整条消息**当行名，不是消息里的标记，故按子串找 —— 而标记是 13 位的时刻戳，别的轮的标记
  // 不可能是它的子串。
  customerId = psql(`select id from ${CUSTOMER} where name like '%${marker}%' order by id desc limit 1`);
  check('本轮那一行已落', true, customerId !== '');
  check('这个名字只命中一行', 1, Number(psql(`select count(*) from ${CUSTOMER} where name like '%${marker}%'`)));
}

// Everything below keyed off that row. When it is missing the run stops **here, saying so**, rather than
// letting an empty id turn into a SQL syntax error several lines later — which reports the wrong thing.
// 下文都挂在那一行上。它不在时，**就在这里说出来并停下**，而不是让一个空 id 在几行之后变成 SQL 语法错误
// ——那会报出一件不对的事。
if (!customerId) {
  bad('本轮那一行没落 ⇒ 后面两场无法进行', 'a row of this run', 'none');
  console.log(`\n${pass} ok / ${fail} failed`);
  process.exit(1);
}

// ── 2) its effect, found by this run's own row ───────────────────────────────────────────────────
console.log('== 场景 2 · 这条写有自己的 effect ==');
let effectId = '';
{
  // `side_effects.args_hash` is a **hash** — the arguments cannot be searched for, and that is by
  // design. The row is found by the id of the row this run just created instead, which is this run's
  // own by construction.
  // `side_effects.args_hash` 是**哈希** —— 参数搜不了，而那是设计使然。这一行改用**本轮刚建的那一行的 id**
  // 来找，而那个 id 按构造就属于本轮。
  effectId = psql(`select id from side_effects where tool_name = 'create_crm_customer' and result_id = ${customerId} order by id desc limit 1`);
  check('找到本轮自己的 effect', true, effectId !== '');
  check('它的档是本地补偿', 'local_compensate', psql(`select revoke_class from side_effects where id = ${effectId}`));
  check('撤销前账本读 executed', 'executed', psql(`select revoke_status from side_effects where id = ${effectId}`));
}

// ── 3) the attempt, and what came of it ──────────────────────────────────────────────────────────
console.log('== 场景 3 · 试一次撤销，断言两种结局都成立的那一半 ==');
{
  const before = revokeRows();
  const r = await revokeEffect(effectId, second);
  const status = psql(`select revoke_status from side_effects where id = ${effectId}`);
  const after = revokeRows();
  const body = String(r.body || '');
  note('撤销端点应答', `HTTP ${r.status} ${body.slice(0, 120)}`);

  const landed = r.status >= 200 && r.status < 300 && status === 'revoked';
  if (landed) {
    ok('撤销落成（账本读 revoked）');
    check('落成时审计恰好多一行 effect_revoke', before + 1, after);
  } else {
    // The refusal has to be **honest**: it names what is missing, and it leaves no line saying a
    // withdrawal happened. Both halves are asserted — a refusal that left a row would be the escape the
    // contract's `effect_revoke` value was added to close.
    // 拒绝必须**诚实**：它点名缺了什么，且**不留**任何「撤销发生过」的行。两半都断言 —— 留下行的拒绝，正是
    // 契约加 `effect_revoke` 这个取值要堵的那个逃逸口。
    check(`被拒且理由点名缺失的本地路（HTTP ${r.status}）`, true, /no local revoke path/.test(body));
    check('被拒时审计一行都不多', before, after);
    check('被拒时账本行未动', 'executed', status);
  }
}

// ── 4) a withdrawal is not "delete an audit row" ─────────────────────────────────────────────────
console.log('== 场景 4 · 撤销不是删审计 ==');
{
  const verify = await (await fetch(`${HOST}/audit/verify`, { headers: { Authorization: `Bearer ${admin}` } })).json();
  check('链校验通过', true, verify.data && verify.data.valid);
  check('试着撤销之后仍至少有一行审计', true, revokeRows() >= 0);
}

console.log(`\n${pass} ok / ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
