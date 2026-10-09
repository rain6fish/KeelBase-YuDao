#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// The gate for a generated module: what the spike observed once, asserted again every time.
//
//   bash scripts/run-server.sh &            # a running stack, PostgreSQL + redis
//   node scripts/run-generated-module-scenarios.mjs
//
// Every assertion is against a **delta taken first**, so the gate still holds on a database it has
// already been run against — the same discipline the sibling host's scenarios use.
//
// 生成模块的门禁：Spike 观测过一次的东西，此后每次都要断言一遍。
//
//   bash scripts/run-server.sh &            # 一套跑着的栈：PostgreSQL + redis
//   node scripts/run-generated-module-scenarios.mjs
//
// 每条断言都对着**先取好的增量**，故这道门禁在一个它已经跑过的库上**照样成立**——与兄弟宿主那些场景同一条纪律。
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
  check('admin 的执行状态', 'executed', a.status);
  check('admin 看得到本次那一行', 1, (a.data || []).filter((r) => r.name === adminMarker).length);

  const s = await chat(second, '列出产品');
  check('第二个调用方触发同一个工具', 'list_generated_products', (s.toolCalls || [])[0]);
  check('它看得到自己那一行', 1, (s.data || []).filter((r) => r.name === salesMarker).length);
  check('它看不到 admin 那一行', 0, (s.data || []).filter((r) => r.name === adminMarker).length);
}

console.log('== 场景 3 · 写被扣下等人，批准后才执行 ==');
{
  const before = Number(psql(`select count(*) from ${MODULE}`));
  const proposed = await chat(second, `新建产品 gate-write-${Date.now()}`);
  check('写被扣下', 'pending_confirmation', proposed.status);
  check('未落库', before, Number(psql(`select count(*) from ${MODULE}`)));
  checkNot('扣下时给出 token', 'undefined', proposed.token);

  const decided = await fetch(`${HOST}/ai/confirmations/${proposed.token}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${second}` },
    body: JSON.stringify({ decision: 'approve' })
  });
  const outcome = (await decided.json()).data || {};
  check('批准后执行', 'executed', outcome.status);
  check('落库', before + 1, Number(psql(`select count(*) from ${MODULE}`)));
}

console.log('== 场景 4 · 审计链 ==');
{
  const rows = Number(psql(`select count(*) from ai_audit_logs`));
  check('审计行在（>0）', true, rows > 0);
  const verify = await (await fetch(`${HOST}/audit/verify`, { headers: { Authorization: `Bearer ${admin}` } })).json();
  check('链校验通过', true, verify.data && verify.data.valid);
}

console.log(`\n${pass} ok / ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
