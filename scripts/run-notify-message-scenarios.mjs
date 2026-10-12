#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// YD-15's gate: the **third** module, and the first whose owner is not the shape the other two had.
//
//   bash scripts/run-server.sh &            # a running stack, PostgreSQL + redis
//   node scripts/run-notify-message-scenarios.mjs
//
// The module is Yudao's own system module, and the slice is its in-app messages
// (`system_notify_message`) — a row belongs to `(user_id, user_type)`, a pair rather than a column.
//
// **What this gate can and cannot show, said before it is run.** Unlike CRM's and BPM's, this module's
// own "my" queries already filter on the caller's pair, so the row-scope question is answered *by the
// module*. What that means for the assertions here:
//
//   * a **read** is asserted as "the turn ran", read from the trail — the same limit the other gates
//     record, since core 0.1.5's answer carries no `data`;
//   * a **write on the caller's own message** is asserted end to end: held, approved, the row flips;
//   * a **write on someone else's message** ends as a **no-op, not a refusal** — the module's own
//     update filters on the caller's pair, so the row simply does not match. On CRM and BPM the advice
//     raises a denial and that denial lands in the audit; here there is none to land. So what is
//     asserted is that **the other subject's row is untouched**, and the outcome is *noted* rather
//     than assumed to be a refusal.
//
// That difference is the finding, not a gap in the gate: it says the per-module work here collapses
// onto the identity bridge, because upstream's code already had the shape.
//
// YD-15 的门禁：**第三个**模块，也是第一个属主形状与另外两个不同的模块。
//
//   bash scripts/run-server.sh &            # 一套跑着的栈：PostgreSQL + redis
//   node scripts/run-notify-message-scenarios.mjs
//
// 该模块是芋道自己的 system 模块，切片是它的站内信（`system_notify_message`）——一行属于 `(user_id, user_type)`，
// 是**一对**、不是一列。
//
// **本门禁能显示什么、不能显示什么，跑之前就说。**与 CRM、BPM 不同，本模块自己的「我的」查询**已经**按调用方那一对
// 过滤，故**行范围问题是模块自己回答的**。这对这里断言的影响是：
//
//   * **读**断言的是「这一回合跑过了」（从审计轨读）——与其他门禁记的同一个限度，因为 core 0.1.5 的答案不带 `data`；
//   * **对调用方自己的消息的写**端到端断言：扣下、批准、那一行翻转；
//   * **对别人消息的写**收在**静默 no-op、不是拒绝**——模块自己的更新按调用方那一对过滤，故那一行**根本匹配不上**。
//     在 CRM 与 BPM 上切面会抛拒绝、那次拒绝会落进审计；这里**没有可落的**。故断言的是**另一个主体的那一行没动**，而
//     结局是**记下来**的、不是假定为拒绝。
//
// 那个差别**就是发现本身**、不是门禁的缺口：它说这里的逐模块活**塌到了身份桥上**，因为上游的代码本来就有那个形状。
import { execFileSync } from 'node:child_process';

const HOST = process.env.HOST_URL || 'http://127.0.0.1:48080';
const PG = process.env.PG_CONTAINER || 'keelbase-yudao-pg';
const DB = process.env.PG_DB || 'ruoyi-vue-pro';
const TENANT = process.env.TENANT_ID || '1';
const SECOND_USER = process.env.SECOND_USER || 'gatesales';
const SECOND_PASS = process.env.SECOND_PASS || 'gate-pass-1234';
const MESSAGE = process.env.NOTIFY_TABLE || 'system_notify_message';
const ADMIN_USER_TYPE = 2; // UserTypeEnum.ADMIN

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

// The same two reads the other gates use, for the same reported reasons: the caller's confirmation
// centre answers 500 on this host, and the chat answer is the frozen `chat-response` with no `data`.
// 与其他门禁相同的两处读法、同样的理由：调用方的确认中心在本宿主答 500，而聊天答案是冻结的 `chat-response`、不带 `data`。
const pendingToken = (operatorId) =>
  psql(`select token from confirmation_requests where operator_id = '${operatorId}' and status = 'pending' order by id desc limit 1`) || undefined;

const pendingCount = (operatorId) =>
  Number(psql(`select count(*) from confirmation_requests where operator_id = '${operatorId}' and status = 'pending'`));

const ranAs = (tool) =>
  Number(psql(`select count(*) from ai_audit_logs where detail like '%${tool} -> ok%'`));

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
// The row's identity is the pair `(user_id, user_type)`, and every NOT NULL column is named here —
// upstream declares a dozen of them on this table.
// 行的身份是 `(user_id, user_type)` **这一对**，而每个 NOT NULL 列都在这里点名——上游在这张表上声明了十来个。
const seed = (userId, text) =>
  psql(`insert into ${MESSAGE}(id, user_id, user_type, template_id, template_code, template_type,`
    + ` template_nickname, template_content, template_params, read_status, create_time, update_time, deleted, tenant_id)`
    + ` values (nextval('${MESSAGE}_seq'), ${userId}, ${ADMIN_USER_TYPE}, 0, 'gate', 1,`
    + ` 'Gate', '${text}', '{}'::jsonb, false, now(), now(), 0, ${TENANT})`);
seed(1, adminMarker);
seed(Number(secondId), salesMarker);
const adminRowId = psql(`select id from ${MESSAGE} where template_content = '${adminMarker}'`);
const salesRowId = psql(`select id from ${MESSAGE} where template_content = '${salesMarker}'`);
check('admin 那一行已种', true, adminRowId !== '');
check('第二个调用方那一行已种', true, salesRowId !== '');
// `read_status` is a **boolean** column, and `psql -tA` prints it `t` / `f` — normalised here so the
// assertion reads as the true/false it means rather than as the driver's spelling of it.
// `read_status` 是 **boolean** 列，而 `psql -tA` 打的是 `t` / `f`——在这里归一化，好让断言读起来是它表示的
// true/false，而不是驱动对它的拼法。
const readStatusOf = (id) => {
  const value = psql(`select read_status from ${MESSAGE} where id = ${id}`);
  return value === 't' || value === 'true';
};

console.log('== 场景 2 · 身份传递：读被路由到本模块的工具 ==');
{
  const a = await chat(admin, '我的消息');
  check('admin 触发站内信工具', 'list_my_notify_messages', (a.toolCalls || [])[0]);
  check('admin 的执行状态（审计轨）', 1, ranAs('list_my_notify_messages'));

  const s = await chat(second, '我的消息');
  check('第二个调用方触发同一个工具', 'list_my_notify_messages', (s.toolCalls || [])[0]);
  check('它的执行状态（审计轨）', 2, ranAs('list_my_notify_messages'));
}

console.log('== 场景 3 · 高风险写入被扣下，批准后落库 ==');
{
  const held = pendingCount(secondId);
  // The row travels as `customerId` — the runtime's one structured slot, named for CRM (see the BPM
  // face for the same recorded workaround).
  // 那一行以 `customerId` 传递——运行时唯一的结构化槽位、以 CRM 命名（同样的绕法见 BPM 那个面）。
  await chat(second, '标记已读', { customerId: Number(salesRowId) });
  check('写被扣下', held + 1, pendingCount(secondId));
  check('未改（仍是未读）', false, readStatusOf(salesRowId));
  const proposedToken = pendingToken(secondId);
  checkNot('扣下时给出 token', 'undefined', proposedToken);

  const outcome = await confirm(second, proposedToken, 'approve');
  check('批准后执行', 'executed', outcome.status);
  check('已标为已读', true, readStatusOf(salesRowId));
}

console.log('== 场景 4 · 越权写：这里没有拒绝可挣 ==');
{
  // Same call, against another subject's message. **The module's own update filters on the caller's
  // pair**, so the row does not match and nothing is refused — the call runs and changes not one row.
  // The assertion is therefore that the other row is untouched, not that something was denied.
  // 同一次调用、打向**另一个主体**的消息。**模块自己的更新按调用方那一对过滤**，故那一行匹配不上、也就没有被拒——
  // 调用跑了、一行未改。故断言的是**那一行没动**，而不是「有东西被拒了」。
  const hijackHeld = pendingCount(secondId);
  await chat(second, '标记已读', { customerId: Number(adminRowId) });
  check('别人的消息也被扣下', hijackHeld + 1, pendingCount(secondId));
  const hijackDone = await confirm(second, pendingToken(secondId), 'approve');
  note('越权写批准后的结局（本模块预期是 no-op，不是拒绝）', hijackDone.status || JSON.stringify(hijackDone).slice(0, 120));
  check('别人的消息一字未动', false, readStatusOf(adminRowId));
}

console.log('== 场景 5 · 审计链 ==');
{
  const rows = Number(psql('select count(*) from ai_audit_logs'));
  check('审计行在（>0）', true, rows > 0);
  const verify = await (await fetch(`${HOST}/audit/verify`, { headers: { Authorization: `Bearer ${admin}` } })).json();
  check('链校验通过', true, verify.data && verify.data.valid);
  // No refusal is asserted, and that is the point: this module answers the row question itself, so a
  // cross-subject write is a no-op rather than a denial on the record.
  // **不**断言拒绝，而那正是要点：本模块自己回答了行范围那一问，故跨主体的写是**静默 no-op**、不是一条拒绝记录。
  note('本门禁不断言拒绝', `refusals on record = ${psql("select count(*) from ai_audit_logs where detail like '%mark_notify_message_read refused%'")}`);
}

console.log(`\n${pass} ok / ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
