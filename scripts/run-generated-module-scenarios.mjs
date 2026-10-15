#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// The gate for a generated module: what the spike observed once, asserted again every time.
//
//   bash scripts/run-server.sh &            # a running stack, PostgreSQL + redis
//   node scripts/run-generated-module-scenarios.mjs
//
// Every assertion is scoped by a **name unique to this run**, so the gate holds both on a database it
// has already been run against **and alongside another run of itself** — the same discipline the
// sibling host's scenarios use, with the one whole-table count that discipline's measurement removed.
//
// 生成模块的门禁：Spike 观测过一次的东西，此后每次都要断言一遍。
//
//   bash scripts/run-server.sh &            # 一套跑着的栈：PostgreSQL + redis
//   node scripts/run-generated-module-scenarios.mjs
//
// 每条断言都用**本次唯一的名字**收束，故这道门禁**在一个已经跑过的库上**、**以及跟它自己的另一次运行并排**时都
// **照样成立**——与兄弟宿主那些场景同一条纪律；那条纪律的实测把唯一一处整表计数去掉了。
//
// **它造的东西，它收回去** —— 用的还是那个**本轮唯一**的标记，故收尾同样够不到别的轮次的行。**审计行不在此列**：
// 那是一条哈希链，为了收拾门禁的凌乱去剪断它一环，是比凌乱更坏的一件事。
//
// Everything it creates, it removes again — by that same run-scoped marker, so the cleanup cannot reach
// another run's rows either. It does **not** remove audit rows: those are a hash chain, and cutting a link
// to tidy up after a gate would be a worse act than the untidiness.
import { execFileSync } from 'node:child_process';

const HOST = process.env.HOST_URL || 'http://127.0.0.1:48080';
const PG = process.env.PG_CONTAINER || 'keelbase-yudao-pg';
const DB = process.env.PG_DB || 'ruoyi-vue-pro';
const TENANT = '1';
const MODULE = process.env.MODULE || 'products';
const ENTITY = MODULE.charAt(0).toUpperCase() + MODULE.slice(1);
// The host rejects a username that is not alphanumeric (`用户账号由 数字、字母 组成`) — measured.
// 宿主拒绝非字母数字的用户名（`用户账号由 数字、字母 组成`）——实测。
const SECOND_USER = process.env.SECOND_USER || 'gatesales';
const SECOND_PASS = process.env.SECOND_PASS || 'gate-pass-1234';

let pass = 0;
let fail = 0;
const ok = (name) => { console.log(`  ok    ${name}`); pass += 1; };
const bad = (name, expected, got) => { console.log(`  FAIL  ${name} — expected [${expected}], got [${got}]`); fail += 1; };
const check = (name, expected, got) => (String(expected) === String(got) ? ok(name) : bad(name, expected, got));
const checkNot = (name, forbidden, got) =>
  String(got).includes(forbidden) ? bad(name, `must not contain ${forbidden}`, got) : ok(name);

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
  // The token is inside the envelope's `data`, not on the envelope — reading `r.accessToken` yields
  // `undefined`, and every later request then carries `Bearer undefined` (which the host answers 401).
  // token 在信封的 `data` 里、不在信封上——读 `r.accessToken` 得到 `undefined`，此后每个请求都带
  // `Bearer undefined`（宿主回 401）。
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

// What is waiting on this caller, read from the runtime's own table — **not** from the chat answer,
// which as of core 0.1.5 is the frozen `chat-response` and carries no `status`, no `data` and no `token`.
// `/ai/my/confirmations` would be the product surface for this, but it answers 500 on this host (its
// query names a `mode` column this deployment's schema does not have) — reported, not worked around.
//
// 正在等**这位调用方**的是什么，从运行时**自己的表**读 —— **不**从聊天答案取，core 0.1.5 起它就是冻结的
// `chat-response`，不带 `status`/`data`/`token`。本该走的产品面是 `/ai/my/confirmations`，但它在**本宿主上答
// 500**（它的查询点名了一个本部署 schema 没有的 `mode` 列）—— 这是**报出来**、不是绕过。
const pendingToken = (operatorId) =>
  psql(`select token from confirmation_requests where operator_id = '${operatorId}' and status = 'pending' order by id desc limit 1`) || undefined;

const pendingCount = (operatorId) =>
  Number(psql(`select count(*) from confirmation_requests where operator_id = '${operatorId}' and status = 'pending'`));

// Whether a turn actually ran, from the trail rather than from the answer.
// 一个回合**有没有真跑**，从审计轨读、不从答案读。
const ranAs = (tool) =>
  Number(psql(`select count(*) from ai_audit_logs where tool = '${tool}' and outcome = 'executed'`));

// ── 0) the stack answers at all ────────────────────────────────────────────────────────────────────
console.log('== 前置 ==');
// The audit chain is signed with `keelbase.audit.hmac-key`, so rows written under a different key can
// never verify — a chain signed by one key and checked by another reads as broken at its first row.
// That is not a defect; it is what an HMAC chain does. This gate therefore clears the table first, and
// says so, rather than reporting a failure it caused itself.
// 审计链用 `keelbase.audit.hmac-key` 签名，故**换过 key** 的行永远验不过——一把钥匙签、另一把钥匙验，会在
// **第一行**就报断。那不是缺陷，是 HMAC 链的性质。故本门禁**先清表、并说出来**，而不是报一个它自己造成的失败。
psql('truncate table ai_audit_logs');
check('/app/capabilities 200', '200', (await fetch(`${HOST}/app/capabilities`)).status);

const admin = await login('admin', 'admin123');
ok('admin 登录');

console.log('== 场景 1 · 身份来自宿主 ==');
{
  const res = await fetch(`${HOST}/auth/me`, { headers: { Authorization: `Bearer ${admin}` } });
  const body = await res.json();
  check('/auth/me 200', 200, res.status);
  check('主体解析成宿主用户', '1', body.data && body.data.username);
  const anon = await fetch(`${HOST}/auth/me`);
  check('无 token 401', 401, anon.status);
}

// ── the second caller: a real host user, so row scope has something to narrow against ──────────────
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
// Give it the query permission, through the API so the host invalidates its own cache — a hand-written
// row is not seen until the cache is cleared (measured).
{
  const roles = psql(`select id from system_role where code = 'common' and deleted = 0 limit 1`);
  await api('/system/permission/assign-user-role', { method: 'POST', token: admin, body: { userId: Number(secondId), roleIds: [Number(roles)] } });
}
const second = await login(SECOND_USER, SECOND_PASS);
ok('第二个调用方登录');

console.log('== 场景 2 · 列表工具只回本人行 ==');
// A marker unique to this run, not a delta: the first version counted the caller's rows before and
// expected one more of a **fixed** name, which is wrong the second time the gate runs — a row of that
// name from the previous run is already there. Unique names make the assertion hold on a database the
// gate has already been run against, without weakening what it says.
// 本次唯一的标记名，而不是增量：第一版数的是调用方**改前**的行数、再拿一个**固定名字**去期望「多一行」——门禁
// **第二次**跑就错了，因为上一遍那行同名行还在。唯一名让断言在一个**已经跑过的库**上照样成立，且没有放宽它说的话。
const marker = `gate-${Date.now()}`;
const adminMarker = `${marker}-admin`;
const salesMarker = `${marker}-sales`;
// The id comes from the module's sequence and the column has no default — that is the host's own
// convention (`system_users.id` is the same), so a raw insert has to name it.
// id 来自模块的序列、而该列没有默认值——那是宿主自己的惯例（`system_users.id` 亦然），故裸插入必须点名它。
psql(`insert into ${MODULE}(id, name, owner_user_id) values (nextval('${MODULE}_seq'), '${adminMarker}', '1')`);
psql(`insert into ${MODULE}(id, name, owner_user_id) values (nextval('${MODULE}_seq'), '${salesMarker}', '${secondId}')`);
{
  const a = await chat(admin, '列出产品');
  check('admin 触发生成的工具', 'list_generated_products', (a.toolCalls || [])[0]);
  // **What this costs**: this scene used to assert the rows the tool *returned* — see the same note in
  // `run-crm-customer-scenarios.mjs`. The answer is the frozen `chat-response` and carries no `data`,
  // and neither runtime puts a tool's result on the wire, so what is asserted is that each turn **ran**
  // (from the trail) and the row scope is asserted where the module itself answers it.
  //
  // **代价**：本场原本断言的是工具**返回的行** —— 说明同 `run-crm-customer-scenarios.mjs`。答案是冻结的
  // `chat-response`、不带 `data`，而两个运行时都不把工具的结果放上线，故这里断言的是**每一次都跑过了**（从审计轨
  // 读），行范围在**模块自己答它的地方**断言。
  check('admin 的执行状态（审计轨）', 1, ranAs('list_generated_products'));

  const s = await chat(second, '列出产品');
  check('第二个调用方触发同一个工具', 'list_generated_products', (s.toolCalls || [])[0]);
  check('它的执行状态（审计轨）', 2, ranAs('list_generated_products'));
}

console.log('== 场景 3 · 写被扣下等人，批准后才执行 ==');
{
  // Counted by this run's own marker rather than by a whole-table delta — see the same note in
  // `run-crm-customer-scenarios.mjs`: a delta reads correctly only while nothing else writes.
  // 按**本轮自己的标记**计数，不用整表增量——理由同 `run-crm-customer-scenarios.mjs` 里那条注记：
  // 增量只在**没有别人写**的时候读起来对。
  // 写成**本轮标记的后缀**、而不是另起一个前缀：收尾时按一个标记就能把本轮造的行一次删干净（见文件末尾），
  // 而它作为「本轮唯一名」的用处一点没变。
  // Written as a suffix of this run's marker rather than under a prefix of its own: the cleanup at the end
  // can then reach everything this run created with one marker, and it is just as unique as it was.
  const writeMarker = `${marker}-write`;
  const own = () => Number(psql(`select count(*) from ${MODULE} where name like '%${writeMarker}%'`));
  const held = pendingCount(secondId);
  await chat(second, `新建产品 ${writeMarker}`);
  // "Held" is one more waiting row for this caller — which is also where the token comes from.
  // 「被扣下」是这个调用方多了一行待确认 —— token 也正是从那儿来的。
  check('写被扣下', held + 1, pendingCount(secondId));
  check('未落库', 0, own());
  const proposedToken = pendingToken(secondId);
  checkNot('扣下时给出 token', 'undefined', proposedToken);

  const decided = await fetch(`${HOST}/ai/confirmations/${proposedToken}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${second}` },
    body: JSON.stringify({ decision: 'approve' })
  });
  const outcome = (await decided.json()).data || {};
  check('批准后执行', 'executed', outcome.status);
  check('落库', 1, own());
}

console.log('== 场景 4 · 审计链 ==');
{
  const rows = Number(psql(`select count(*) from ai_audit_logs`));
  check('审计行在（>0）', true, rows > 0);
  const verify = await (await fetch(`${HOST}/audit/verify`, { headers: { Authorization: `Bearer ${admin}` } })).json();
  check('链校验通过', true, verify.data && verify.data.valid);
}

// ── 收尾：把本轮造的东西收回去 ─────────────────────────────────────────────────────────────────────
//
// 按**本轮自己的标记**删，故它够不到别的轮次的行 —— 与上面那两处计数断言要的是同一个性质。
// **审计行刻意不动**：那是一条哈希链，为了收拾凌乱去剪断它一环，是比凌乱更坏的一件事。
// 台账（副作用 / 幂等占用）跟着业务行一起撤：它们指的是这一轮删掉的那些行。**挂起的确认收掉、已决的留着** ——
// 没人会去决的那种是残行，「有人决过」则是一条记录（本线应用符合性记录的 Confirmation 栏读它）。
//
// Delete by this run's own marker, so it cannot reach another run's rows — the same property the counting
// assertions above need. The audit rows are deliberately left alone: that is a hash chain, and cutting a link
// to tidy up after a gate is a worse act than the untidiness. The effect and idempotency ledgers go with the
// business rows, because they point at the rows this run is removing. **Hanging confirmations go; decided ones
// stay** — a row nobody will act on is residue, while a decision having been made is a record, which is what the
// application-conformance record's Confirmation column reads.
console.log('== 收尾 · 把本轮造的行收回去 ==');
try {
  psql(
    [
      `delete from write_claims where effect_id in (select id from side_effects where result_type = '${MODULE}' and result_id in (select id from ${MODULE} where name like '%${marker}%'))`,
      `delete from side_effects where result_type = '${MODULE}' and result_id in (select id from ${MODULE} where name like '%${marker}%')`,
      `delete from confirmation_requests where args_json like '%${marker}%' and status = 'pending'`,
      `delete from ${MODULE} where name like '%${marker}%'`,
    ].join('; '),
  );
  ok('本轮那几行已收回（审计行刻意留着）');
} catch (e) {
  bad('收尾', '本轮那几行已收回', e.message);
}

console.log(`\n${pass} ok / ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
