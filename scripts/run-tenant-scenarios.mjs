// SPDX-License-Identifier: Apache-2.0
//
// YD-10's gate: the tenant a governed write lands in is **the caller's**, not a constant.
//
//   bash scripts/run-server.sh &            # a running stack, PostgreSQL + redis
//   node scripts/run-tenant-scenarios.mjs
//
// Why it exists. Until this line's D39/D40 landed, the adapter ran every host-service call inside
// `TenantUtils.execute(SPIKE_TENANT_ID, …)` — one hard-coded tenant, declared in three places. The
// judgement it has to meet is narrow and checkable: **a caller on tenant ≠ 1 is no longer resolved as
// tenant 1.** The counter-proof the read-only inventory asked for is the one that matters here, and it
// is stated as a comparison rather than a before/after rerun: two callers on **two different tenants**
// each make the same write, and the two rows must land in **two different tenants**. If the value were
// still a constant, both rows would read the same tenant — so "the two differ" is what says the value is
// actually read, and "row B is not tenant 1" is what says it follows the caller.
//
// ⚠ The gate is written so it cannot pass by accident on a busy database: every reading is keyed off
// **this run's own marker**, never off a count of the table.
//
// YD-10 的门禁：一次受治理的写落在**哪个租户**，取决于**调用方**，不是一个常量。
//
// 为什么要有它：在 `D39`/`D40` 落地之前，适配器把每一次宿主服务调用都包在
// `TenantUtils.execute(SPIKE_TENANT_ID, …)` 里——一个写死的租户，声明在三处。它要满足的判据很窄、可核：
// **一个 tenant≠1 的调用方，不再被按 tenant 1 解析。** 只读盘点要求的那条**反证**在这里是主线，且写成
// **比较**而不是「改前改后各跑一次」：**两个不同租户**的调用方各做同一次写，两行必须落在**两个不同的租户**。
// 如果那个值还是常量，两行会读作同一个租户——所以「两者不同」说明它**真被读到**，「B 那行不是租户 1」说明它
// **跟着调用方走**。
//
// ⚠ 这道闸刻意写成**不可能在一张忙的库上侥幸通过**：每个读数都挂在**本轮自己的标记**上，绝不数整张表。
import { execFileSync } from 'node:child_process';

const HOST = process.env.HOST_URL || 'http://127.0.0.1:48080';
const PG = process.env.PG_CONTAINER || 'keelbase-yudao-pg';
const DB = process.env.PG_DB || 'ruoyi-vue-pro';
const ROOT_TENANT = 1;
const GATE_TENANT_NAME = process.env.GATE_TENANT_NAME || 'GateTenant';
const GATE_USER = process.env.GATE_USER || 'gatetenant';
const GATE_PASS = process.env.GATE_PASS || 'gate-tenant-1234';

let pass = 0;
let fail = 0;
const ok = (name) => { console.log(`  ok    ${name}`); pass += 1; };
const bad = (name, expected, got) => { console.log(`  FAIL  ${name} — expected [${expected}], got [${got}]`); fail += 1; };
const check = (name, expected, got) => (String(expected) === String(got) ? ok(name) : bad(name, expected, got));
const note = (name, got) => console.log(`  note  ${name} → ${got}`);

const psql = (sql) =>
  execFileSync('docker', ['exec', '-i', '-e', 'PGPASSWORD=password', PG, 'psql', '-U', 'root', '-d', DB, '-tAc', sql], { encoding: 'utf8' }).trim();

// The admin API asks for a tenant; the AI and confirmation paths are the runtime's own and are called
// with the same header, because **carrying the tenant is the whole point** (D39: the request carries it).
// A call without that header is deliberately left as a reading below, not as a working path.
// 管理端 API 要租户；AI 与确认那两条路是运行时自己的，也用同一个头调，因为**「请求携带」正是要点**（`D39`）。
// 不带这个头的调用**刻意**只作下面的一条读数，不作一条可用的路。
const headers = (token, tenant) => ({
  'Content-Type': 'application/json',
  ...(token ? { Authorization: `Bearer ${token}` } : {}),
  ...(tenant === null || tenant === undefined ? {} : { 'tenant-id': String(tenant) })
});

async function api(path, { method = 'GET', token, tenant = ROOT_TENANT, body } = {}) {
  const res = await fetch(`${HOST}/admin-api${path}`, { method, headers: headers(token, tenant), ...(body ? { body: JSON.stringify(body) } : {}) });
  return res.json();
}

async function login(username, password, tenant) {
  const r = await api('/system/auth/login', { method: 'POST', tenant, body: { username, password } });
  if (r.code !== 0) throw new Error(`login(${username}@${tenant}) -> ${r.code}: ${r.msg}`);
  return r.data.accessToken;
}

const userIdOf = (username, tenant) =>
  psql(`select id from system_users where username = '${username}' and tenant_id = ${tenant} and deleted = 0 limit 1`);

// One governed write, taken all the way to a real row: ask, approve, and report what the console said.
// 一次受治理的写，走到真的落库：提问、批准，并报出控制台说了什么。
async function makeCustomer({ token, tenant, label, operatorId }) {
  const marker = `tenantgate-${label}-${Date.now()}`;
  const chatRes = await fetch(`${HOST}/ai/chat`, {
    method: 'POST', headers: headers(token, tenant),
    body: JSON.stringify({ message: `新建客户 ${marker}`, conversationId: `tenant-gate-${label}-${Date.now()}` })
  });
  const chatJson = await chatRes.json();
  const held = chatJson.data || chatJson;

  const confirmationToken =
    held.token || held.confirmationToken ||
    psql(`select token from confirmation_requests where operator_id = '${operatorId}' and status = 'pending' order by id desc limit 1`);

  const confRes = await fetch(`${HOST}/ai/confirmations/${confirmationToken}`, {
    method: 'POST', headers: headers(token, tenant), body: JSON.stringify({ decision: 'approve' })
  });
  const confJson = await confRes.json();
  return { marker, http: chatRes.status, outcome: confJson.data || confJson };
}

// ── 0) the stack answers, and the second tenant exists ───────────────────────────────────────────
console.log('== 前置 ==');
check('/app/capabilities 200', '200', (await fetch(`${HOST}/app/capabilities`)).status);
const rootToken = await login('admin', 'admin123', ROOT_TENANT);
ok('admin@1 登录');

let tenantId = psql(`select id from system_tenant where name = '${GATE_TENANT_NAME}' limit 1`);
if (!tenantId) {
  const created = await api('/system/tenant/create', {
    method: 'POST', token: rootToken,
    body: {
      name: GATE_TENANT_NAME, contactName: GATE_TENANT_NAME, status: 0, packageId: 111,
      // yudao's request body takes the expiry as epoch **milliseconds**, not a formatted string: a
      // string is accepted without error and silently stored as the epoch, which then reads as
      // "expired".  yudao 的请求体把过期时间收作**纪元毫秒**，不是格式化字符串：传字符串**不报错**、
      // 静默存成纪元，于是读作「已过期」。
      expireTime: Date.UTC(2030, 11, 31, 0, 0, 0), accountCount: 10,
      username: GATE_USER, password: GATE_PASS
    }
  });
  if (created.code !== 0) throw new Error(`create tenant -> ${created.code}: ${created.msg}`);
  tenantId = created.data;
  ok(`建闸租户 id=${tenantId}`);
} else {
  ok(`复用闸租户 id=${tenantId}`);
}
check('闸租户不是 1 号', true, String(tenantId) !== String(ROOT_TENANT));

// The host caches "is this tenant valid" for a minute, so a tenant created an instant ago still reads as
// expired for up to that long. Waiting it out is the honest fix; retrying is not the same thing.
// 宿主把「这个租户有效吗」缓存一分钟，故一个刚建的租户在那一分钟里仍读作已过期。等它过去才是诚实的修法；
// 靠重试不是同一件事。
let tenantToken = '';
for (let attempt = 1; attempt <= 10; attempt += 1) {
  try {
    tenantToken = await login(GATE_USER, GATE_PASS, tenantId);
    ok(`第二个调用方登录（@${tenantId}）`);
    break;
  } catch (err) {
    if (attempt === 10) throw err;
    await new Promise((r) => setTimeout(r, 15000));
  }
}

// ── 1) the same write, made by a caller on each tenant ───────────────────────────────────────────
console.log('== 场景 1 · 1 号租户的调用方写一笔 ==');
const rootOp = userIdOf('admin', ROOT_TENANT);
const a = await makeCustomer({ token: rootToken, tenant: ROOT_TENANT, label: 'root', operatorId: rootOp });
note('[root] /ai/chat', `HTTP ${a.http} · 结局 ${JSON.stringify(a.outcome).slice(0, 120)}`);
check('[root] 批准后执行', 'executed', a.outcome.status);
const rowA = psql(`select id, tenant_id, owner_user_id from crm_customer where name like '%${a.marker}%'`);
note('[root] 落库行', rowA || '(none)');
check('[root] 本轮那一行已落', true, rowA !== '');

console.log('== 场景 2 · 闸租户的调用方写同一笔 ==');
const gateOp = userIdOf(GATE_USER, tenantId);
const b = await makeCustomer({ token: tenantToken, tenant: tenantId, label: 'gate', operatorId: gateOp });
note('[gate] /ai/chat', `HTTP ${b.http} · 结局 ${JSON.stringify(b.outcome).slice(0, 120)}`);
check('[gate] 批准后执行', 'executed', b.outcome.status);
const rowB = psql(`select id, tenant_id, owner_user_id from crm_customer where name like '%${b.marker}%'`);
note('[gate] 落库行', rowB || '(none)');
check('[gate] 本轮那一行已落', true, rowB !== '');

// ── 2) the counter-proof: two callers, two tenants ───────────────────────────────────────────────
console.log('== 场景 3 · 反证：同一笔写，两个调用方落在两个租户 ==');
const tenantOfA = rowA ? rowA.split('|')[1] : '';
const tenantOfB = rowB ? rowB.split('|')[1] : '';
check('[root] 那一行落在 1 号租户', String(ROOT_TENANT), tenantOfA);
check('[gate] 那一行落在闸租户（不是 1）', String(tenantId), tenantOfB);
check('两者不同 ⇒ 这个值是被读到的，不是常量', true, tenantOfA !== tenantOfB);

// ── 3) and it is not merely written, it is not visible across tenants ────────────────────────────
console.log('== 场景 4 · 跨租户不可见 ==');
check('[gate] 看不到 root 那笔', 0, Number(psql(`select count(*) from crm_customer where tenant_id = ${tenantId} and name like '%${a.marker}%'`)));
check('[root] 看不到 gate 那笔', 0, Number(psql(`select count(*) from crm_customer where tenant_id = ${ROOT_TENANT} and name like '%${b.marker}%'`)));

// ── 4) the declared fallback, read rather than asserted ──────────────────────────────────────────
console.log('== 场景 5 · 读数（非断言）：不给租户头时会怎样 ==');
{
  const marker = `tenantgate-noheader-${Date.now()}`;
  const res = await fetch(`${HOST}/ai/chat`, {
    method: 'POST', headers: headers(tenantToken, null),
    body: JSON.stringify({ message: `新建客户 ${marker}`, conversationId: `tenant-gate-noheader-${Date.now()}` })
  });
  const body = await res.json();
  note('不带租户头的 /ai/chat', `HTTP ${res.status} ${JSON.stringify(body).slice(0, 160)}`);
  note('它有没有落库', psql(`select id, tenant_id from crm_customer where name like '%${marker}%'`) || '(no row)');
}

console.log(`\n${pass} ok / ${fail} failed`);
console.log(`note: 闸租户 id=${tenantId}（${GATE_TENANT_NAME}）—— 它留在本机库里供复用，不删。`);
process.exitCode = fail === 0 ? 0 : 1;
