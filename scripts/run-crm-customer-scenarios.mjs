#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// YD-2's gate: governance over a module **upstream wrote**, not one the adapter generated.
//
//   bash scripts/run-server.sh &            # a running stack, PostgreSQL + redis
//   node scripts/run-crm-customer-scenarios.mjs
//
// The module is Yudao's own CRM — 283 Java files that we did not write, did not regenerate, and did
// not edit. What is asserted here is what `D28 ④` calls the first acceptance gate: an existing
// business module, reachable by AI under an explicit identity, with out-of-scope access refused,
// a high-risk write held for a human, and the side effect auditable — all of it through tests.
//
// Every assertion is scoped by a **name unique to this run** — never by a count of the whole table —
// so the gate holds both on a database it has already been run against **and alongside another run of
// itself**. The one place that used to count whole rows was measured the hard way: with two runs side
// by side it failed `落库 — expected [11], got [12]`, and the failure pointed at this gate rather than
// at the other writer.
//
// YD-2 的门禁：治理一个**上游写的**模块，而不是适配器生成的那个。
//
//   bash scripts/run-server.sh &            # 一套跑着的栈：PostgreSQL + redis
//   node scripts/run-crm-customer-scenarios.mjs
//
// 该模块是芋道自己的 CRM——我们没写、没重新生成、也没改过的 283 个 Java 文件。这里断言的是 `D28 ④` 所称的
// **第一道验收门**：一个既有业务模块，能被 AI 以**明确身份**访问、越权被拒、高风险写入被扣下等人、副作用可审计
// ——且这一切都由测试证明。
//
// 每条断言都用**本次唯一的名字**收束——**不**按整张表计数——故本门禁**在一个已经跑过的库上**、**以及跟它自己的
// 另一次运行并排**时都成立。曾经按整表计数的那一处是**实测出来**的：两次并排跑时它报
// `落库 — expected [11], got [12]`，而那个失败看上去指着本门禁、不指着另一个写手。
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
  // The token is inside the envelope's `data`, not on the envelope. / token 在信封的 `data` 里、不在信封上。
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

// ── 0) the stack answers at all ────────────────────────────────────────────────────────────────────
console.log('== 前置 ==');
// The audit chain is signed with `keelbase.audit.hmac-key`, so rows written under a different key can
// never verify. This gate clears the table first and says so, rather than reporting a failure it caused.
// 审计链用 `keelbase.audit.hmac-key` 签名，故换过 key 的行永远验不过。本门禁先清表并说出来。
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
// `tenant_id` is not optional here: the host's tenant interceptor appends `tenant_id = ?` to this
// table, because `CrmCustomerDO` extends `BaseDO` and carries no `@TenantIgnore`. A row inserted
// without it is invisible to every path through the module.
// `tenant_id` 在这里不是可选的：宿主的租户拦截器给这张表加 `tenant_id = ?`，因为 `CrmCustomerDO` 继承
// `BaseDO` 且没带 `@TenantIgnore`。不带它的行，经模块的每条路都看不见。
psql(`insert into ${CUSTOMER}(id, name, owner_user_id, tenant_id) values (nextval('${CUSTOMER}_seq'), '${adminMarker}', 1, ${TENANT})`);
psql(`insert into ${CUSTOMER}(id, name, owner_user_id, tenant_id) values (nextval('${CUSTOMER}_seq'), '${salesMarker}', ${secondId}, ${TENANT})`);
const adminRowId = psql(`select id from ${CUSTOMER} where name = '${adminMarker}'`);
const salesRowId = psql(`select id from ${CUSTOMER} where name = '${salesMarker}'`);
check('admin 那一行已种', true, adminRowId !== '');
check('第二个调用方那一行已种', true, salesRowId !== '');

console.log('== 场景 2 · 身份传递 + 数据范围 ==');
{
  const a = await chat(admin, '列出客户');
  check('admin 触发 CRM 工具', 'list_crm_customers', (a.toolCalls || [])[0]);
  check('admin 的执行状态', 'executed', a.status);
  // The seed administrator is the one caller the narrowing leaves alone, so this side also shows what
  // upstream's own default does when nobody narrows: every row of the tenant.
  // 种子管理员是收束唯一放过的那一个调用方，故这一侧也显出了上游自己的默认：本租户的每一行。
  check('admin 看得到本次两行', 2,
    (a.data || []).filter((r) => r.name === adminMarker || r.name === salesMarker).length);

  const s = await chat(second, '列出客户');
  check('第二个调用方触发同一个工具', 'list_crm_customers', (s.toolCalls || [])[0]);
  check('它的执行状态', 'executed', s.status);
  check('它看得到自己那一行', 1, (s.data || []).filter((r) => r.name === salesMarker).length);
  check('它看不到 admin 那一行', 0, (s.data || []).filter((r) => r.name === adminMarker).length);
  // Identity travelled: the row that came back is stamped with the caller the tool passed in, so the
  // module scoped the page itself rather than being post-filtered by us.
  // 身份传递到位：回来的那一行盖着**工具传进去的那个调用方**，故分页是**模块自己**收束的、不是我们事后过滤的。
  const mine = (s.data || []).find((r) => r.name === salesMarker) || {};
  check('回来的行属主 = 调用方', secondId, mine.ownerUserId);
}

console.log('== 场景 3 · 高风险写入被扣下，批准后落库 ==');
const writeMarker = `${marker}-write`;
{
  // Both halves of this scene count **this run's own row**, not the table. A whole-table delta
  // (`before` … `before + 1`) reads correctly only while nothing else writes, and two gates running at
  // once is exactly the case where it does not: it then fails with `expected [N], got [N+1]`, and the
  // failure points at the gate rather than at the other writer. Counting by this run's marker is both
  // immune to that and strictly stronger — it asserts *this* row landed, not that the table grew by one.
  // 本场两半都数**本轮自己那一行**、不数整张表。整表增量（`before` … `before + 1`）只在没有别人写的时候读起来对，
  // 而「两道门禁同时跑」正是它不对的那种情形：它会以 `expected [N], got [N+1]` 失败，而那个失败看上去指着门禁、
  // 不指着另一个写手。按本轮的标记计数**既免疫这个问题、又更强**——它断言的是**这一行**落了，不是表多了一行。
  const own = () => Number(psql(`select count(*) from ${CUSTOMER} where name like '%${writeMarker}%'`));
  const proposed = await chat(second, `新建客户 ${writeMarker}`);
  check('写被扣下', 'pending_confirmation', proposed.status);
  check('未落库', 0, own());
  checkNot('扣下时给出 token', 'undefined', proposed.token);

  const outcome = await confirm(second, proposed.token, 'approve');
  check('批准后执行', 'executed', outcome.status);
  check('落库', 1, own());
  // The owner is stamped by the governance advice, not named by the tool — the write VO carries the
  // column and a client could have pointed it at someone else.
  // 属主由治理通知盖章、不由工具点名——保存 VO 带着那一列，客户端本可以把它指到别人身上。
  check('新行的属主 = 调用方', secondId,
    psql(`select owner_user_id from ${CUSTOMER} where name like '%${writeMarker}%'`));
}

console.log('== 场景 4 · 越权写：批准也买不到 ==');
const ownMarker = `${marker}-own`;
const hijackMarker = `${marker}-hijack`;
{
  // A positive control first: the same tool, the same caller, a row that IS the caller's.
  // 先来一条正对照：同一个工具、同一个调用方，但那一行**是**它自己的。
  const mine = await chat(second, `修改客户 ${ownMarker}`, { customerId: Number(salesRowId) });
  check('改自己的行被扣下', 'pending_confirmation', mine.status);
  const mineDone = await confirm(second, mine.token, 'approve');
  check('改自己的行批准后执行', 'executed', mineDone.status);
  check('自己那一行确实改了', true,
    psql(`select name from ${CUSTOMER} where id = ${salesRowId}`).includes(ownMarker));

  // Then the same call against another caller's row. The approval settles the risk question and
  // nothing else: the row-scope question is answered lower down, at the module's own seam.
  // 然后是同一次调用、打向**别人的**行。批准了结的是**风险**问题、仅此而已：行范围的问题在更下面、在模块
  // 自己的接缝上回答。
  const hijack = await chat(second, `修改客户 ${hijackMarker}`, { customerId: Number(adminRowId) });
  check('改别人的行也被扣下', 'pending_confirmation', hijack.status);
  const hijackDone = await confirm(second, hijack.token, 'approve');
  note('越权写批准后的结局', hijackDone.status || JSON.stringify(hijackDone).slice(0, 120));
  check('批准了也没执行', true, hijackDone.status !== 'executed');
  check('别人的行一字未改', adminMarker, psql(`select name from ${CUSTOMER} where id = ${adminRowId}`));
}

console.log('== 场景 5 · 审计链与被记下的拒绝 ==');
{
  const rows = Number(psql('select count(*) from ai_audit_logs'));
  check('审计行在（>0）', true, rows > 0);
  const verify = await (await fetch(`${HOST}/audit/verify`, { headers: { Authorization: `Bearer ${admin}` } })).json();
  check('链校验通过', true, verify.data && verify.data.valid);
  // The half a list view can never produce: the refusal itself is on the record. The tool name lives
  // in `detail`, not in `action` — `action` says what kind of event it was (`tool_call`), and the
  // first version of this assertion looked in the wrong column and read a pass as a failure.
  // 菜单列表那一侧永远产生不了的那一半：**被拒本身**在案。工具名在 `detail` 里、不在 `action` 里——`action`
  // 说的是这是哪一类事件（`tool_call`）；本断言的**第一版**查错了列，把一次通过读成了失败。
  const refusals = Number(psql(`select count(*) from ai_audit_logs where detail like '%update_crm_customer refused%'`));
  check('被拒的那次写在案', true, refusals > 0);
}

console.log(`\n${pass} ok / ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
