#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// 抽屉那三条数据源的门禁：会话与流（`ai1`）、工具卡（运行时的副作用面）、确认卡（运行时的按调用方那条面）。
//
//   bash scripts/run-server.sh &            # 一套跑着的栈：PostgreSQL + redis
//   node scripts/run-assistant-drawer-scenarios.mjs
//
// 它查的是**页面要读的每一条面**，而不是页面本身——界面的像素由一次真浏览器点击来验，这里验的是那些像素
// 背后**有没有东西**。四件各自会单独坏掉的事：
//
//   * **会话与流**：`/ai1/session/my-list` 里有本轮会话；`send-stream` 真的吐出 `message` 并以 `done` 收尾
//     （抽屉的流式读取若接错，这里看不到 `done`）。
//   * **确认卡有源**：那条被扣下的写，`GET /ai/my/confirmations` 能列出来，且 `…/decide` 能**按调用方**批掉
//     —— 这一步同时证明「普通调用方不必跳治理页」（治理页是管理员专属）。
//   * **工具卡有源**：批准之后 `GET /ai/tool-effects` 里有那笔写。**只列写**这件事在这里是断言的一部分：
//     本运行时按调用方只供写那一面。
//   * **每一次受治理的调用说得出自己在哪一轮**：`ai_audit_logs.conversation_id` = 本会话号。没有它，工具卡
//     永远只能退化成「这个调用方最近的调用」——那一栏是**逐轮**视图的前提，故此门禁在 **DB 层**断言它。
//
// 它建的每一样，跑完都拆掉；审计行**刻意**留着（那是一条哈希链）。
//
// The gate for the drawer's three data sources: sessions and the stream (`ai1`), the tool card (the
// runtime's effect surface), and the confirmation card (its caller-scoped route).
//
// It checks **every surface the page reads** rather than the page itself — pixels are verified by a real
// click in a browser; what this checks is whether there is anything behind them. Four things that break
// separately: the session and its stream (a wrong stream reader never sees `done`); the confirmation card
// has a source and can be decided **by the caller** (which is what makes the jump to the administrator-only
// governance pages unnecessary); the tool card has a source after approval (**writes only** is part of the
// assertion, not an aside); and every governed call says which turn it belongs to —
// `ai_audit_logs.conversation_id` = this session, asserted at the **DB** layer, because without it a tool
// card can never be more than "this caller's recent calls".
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const HOST = process.env.HOST_URL || 'http://127.0.0.1:48080';
const API = `${HOST}/admin-api`;
const PG = process.env.PG_CONTAINER || 'keelbase-yudao-pg';
const DB = process.env.PG_DB || 'ruoyi-vue-pro';
const TENANT = process.env.TENANT_ID || '1';
const CRM_TABLE = process.env.CRM_TABLE || 'crm_customer';
const BODY_FILE = join(tmpdir(), `assistant-body-${process.pid}.json`);
const SEND_FILE = join(tmpdir(), `assistant-send-${process.pid}.json`);
const SECRETS = process.env.KEY_FILE || 'D:/Rain6fish/.secrets/spring-ai.env';

let pass = 0;
let fail = 0;
const ok = (name) => { console.log(`  ok    ${name}`); pass += 1; };
const bad = (name, expected, got) => { console.log(`  FAIL  ${name} — expected [${expected}], got [${got}]`); fail += 1; };
const check = (name, expected, got) => (String(expected) === String(got) ? ok(name) : bad(name, expected, got));

const psql = (sql) =>
  execFileSync('docker', ['exec', '-i', '-e', 'PGPASSWORD=password', PG, 'psql', '-U', 'root', '-d', DB, '-tAc', sql], { encoding: 'utf8' }).trim();

// One request, retried once on the host's transient 500 — see the sibling gate for the measurement.
// 一次请求；宿主那个瞬时 500 时重试一次 —— 实测见同族那条门禁。
async function api(path, { method = 'GET', token, body } = {}) {
  for (let attempt = 1; ; attempt += 1) {
    const res = await fetch(`${API}${path}`, {
      method,
      headers: { 'Content-Type': 'application/json', 'tenant-id': TENANT, ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {})
    });
    const json = await res.json();
    if (json.code !== 500 || attempt > 1) {
      return json;
    }
    console.log(`  note  ${path} 第一次答 500 —— 宿主的瞬时故障，重试一次`);
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
}

/** The body goes through a file: a Chinese argument passed on the command line is mangled by the shell. */
/** 请求体走文件：中文经命令行传参会被 shell 改坏。 */
function postJson(path, token, body) {
  for (let attempt = 1; ; attempt += 1) {
    writeFileSync(BODY_FILE, JSON.stringify(body));
    const raw = execFileSync('curl', ['-s', '-X', 'POST', `${API}${path}`,
      '-H', 'Content-Type: application/json', '-H', `tenant-id: ${TENANT}`,
      '-H', `Authorization: Bearer ${token}`, '--data-binary', `@${BODY_FILE}`], { encoding: 'utf8' });
    const json = JSON.parse(raw);
    if (json.code === 0) {
      return json.data;
    }
    if (json.code !== 500 || attempt > 1) {
      throw new Error(`${path} -> ${json.code}: ${json.msg}`);
    }
    console.log(`  note  ${path} 第一次答 500 —— 宿主的瞬时故障，重试一次`);
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 300);
  }
}

async function login(username, password) {
  const r = await api('/system/auth/login', { method: 'POST', body: { username, password } });
  if (r.code !== 0) throw new Error(`login(${username}) -> ${r.code}: ${r.msg}`);
  return r.data.accessToken;
}

/**
 * 一个**普通调用方**：本部署的 `common` 角色、不是管理员。同名用户复用，没有就建一个（与兄弟门禁同一手法）。
 * **注意**：给角色授权**之后紧接着**的那次登录会撞上宿主的瞬时 500 —— `api()` 已经为此重试一次。
 *
 * One **ordinary** caller: this deployment's `common` role, not an administrator. Reuses the same user,
 * creating it if absent (the sibling gates' own approach). Note the login **immediately after** a role
 * assignment hits the host's transient 500 — `api()` already retries that once.
 */
async function ensureOrdinaryCaller(adminToken) {
  const existing = psql("select id from system_users where username = 'gatesales' and deleted = 0 limit 1");
  let id = existing ? Number(existing) : null;
  if (!id) {
    const created = await api('/system/user/create', {
      method: 'POST',
      token: adminToken,
      body: { username: 'gatesales', nickname: 'gatesales', password: 'gate-pass-1234', deptId: 100, postIds: [] }
    });
    if (created.code !== 0) throw new Error(`create ordinary user -> ${created.code}: ${created.msg}`);
    id = created.data;
  }
  const roleId = psql("select id from system_role where code = 'common' and deleted = 0 limit 1");
  await api('/system/permission/assign-user-role', {
    method: 'POST', token: adminToken, body: { userId: Number(id), roleIds: [Number(roleId)] }
  });
  return { id, username: 'gatesales', password: 'gate-pass-1234' };
}

// ── 0) the stack and the key ──────────────────────────────────────────────────────────────────────
console.log('== 前置 ==');
check('宿主在跑（/app/capabilities 200）', 200, await fetch(`${HOST}/app/capabilities`).then((r) => r.status).catch(() => 0));
if (!existsSync(SECRETS)) {
  console.error(`\nno model key at ${SECRETS}`);
  process.exit(1);
}
const secret = Object.fromEntries(readFileSync(SECRETS, 'utf8').split('\n')
  .filter((line) => line.includes('=') && !line.trim().startsWith('#'))
  .map((line) => [line.slice(0, line.indexOf('=')).trim(), line.slice(line.indexOf('=') + 1).trim()]));
const KEY = secret.SPRING_AI_DEEPSEEK_API_KEY;
const MODEL = secret.SPRING_AI_DEEPSEEK_CHAT_MODEL || secret.SPRING_AI_DEEPSEEK_CHAT_OPTIONS_MODEL;
check('拿到模型 key（只报长度）', true, !!KEY && KEY.length > 10);

const admin = await login('admin', 'admin123');
ok('admin 登录');
const auditFloor = Number(psql('select coalesce(max(id), 0) from ai_audit_logs'));

// ── 1) the chain ──────────────────────────────────────────────────────────────────────────────────
console.log('== 前置 · 链路（经宿主自己的 API 建）==');
const marker = `gate-${Date.now()}`;
const customer = `${marker}-customer`;
let providerId; let modelId; let agentId; let sessionId;
try {
  providerId = postJson('/ai1/provider/create', admin,
    { name: `gate-${marker}`, baseUrl: 'https://api.deepseek.com', apiKey: KEY, status: 0 });
  modelId = postJson('/ai1/model/create', admin,
    { providerId, name: `gate-${marker}`, model: MODEL, type: 0, status: 0 });
  agentId = postJson('/ai1/agent/create', admin, {
    name: `gate-${marker}`,
    introduction: '抽屉三条数据源的门禁探针',
    providerId,
    modelId,
    systemPrompt: '你是一个只会照做的助手。用户让你用工具，你就把工具用掉，不要多问，也不要只回答文字。',
    knowledgeBaseIds: [],
    mcpIds: [],
    skillIds: []
  });
  await api('/ai1/agent/update-status', { method: 'PUT', token: admin, body: { id: agentId, status: 0 } });
  sessionId = postJson('/ai1/session/create-my', admin, { agentId });
  ok(`链路建成（agent=${agentId} session=${sessionId}）`);
} catch (e) {
  bad('链路建成', 'ok', e.message);
}

// ── 2) the session list and the stream ────────────────────────────────────────────────────────────
console.log('== 会话块 · 列表与流 ==');
// `chainReady` rather than `fail === 0`: the blocks below depend on the *chain*, not on each other, and an
// early assertion failing must not hide the three later ones — measured the first run, where it did.
// 判据是 `chainReady` 而不是 `fail === 0`：下面几块依赖的是**链路**、不是彼此，而一条早断言失败**不该**把后面
// 三条一起藏掉 —— 首跑实测正是如此。
const chainReady = fail === 0;
if (chainReady) {
  const sessions = await api(`/ai1/session/my-list?agentId=${agentId}`, { token: admin });
  check('会话列在「我的会话」里', true, (sessions.data ?? []).some((s) => s.id === sessionId));

  // The stream is read the way the page reads it: a POST, so not `EventSource`, and the events are what
  // the page keys on. `done` is the one a broken reader never sees.
  // 流按页面读它的方式读：POST，故不是 `EventSource`；而事件名是页面的判据。`done` 正是**读取接错**时看不到的那个。
  writeFileSync(SEND_FILE, JSON.stringify({
    sessionId,
    content: `请用新建客户的工具新建一个客户，客户名称就是这段：${customer}`
  }));
  const streamed = execFileSync('curl', ['-s', '-N', '--max-time', '180', '-X', 'POST',
    `${API}/ai1/session/message/send-stream`, '-H', 'Content-Type: application/json',
    '-H', `tenant-id: ${TENANT}`, '-H', `Authorization: Bearer ${admin}`, '--data-binary', `@${SEND_FILE}`],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  check('流里有 message 事件', true, /event:\s*message/.test(streamed));
  check('流以 done 收尾', true, /event:\s*done/.test(streamed));

  const messages = await api(`/ai1/session/message/my-list?sessionId=${sessionId}`, { token: admin });
  check('会话里留下了这一轮的消息', true, (messages.data ?? []).length >= 2);
}

// ── 3) the confirmation card, decided by the caller ───────────────────────────────────────────────
console.log('== 确认卡 · 按调用方列出、按调用方决定 ==');
let token;
if (chainReady) {
  const mine = await fetch(`${HOST}/ai/my/confirmations?status=pending`, {
    headers: { Authorization: `Bearer ${admin}`, 'tenant-id': TENANT }
  }).then((r) => r.json()).catch(() => null);
  const list = mine?.data ?? [];
  const held = list.find((row) => JSON.stringify(row.arguments ?? {}).includes(customer));
  check('待我确认里列出了本轮那条', true, !!held);
  token = held?.token;
  if (token) {
    const decided = await fetch(`${HOST}/ai/my/confirmations/${encodeURIComponent(token)}/decide`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${admin}`, 'tenant-id': TENANT },
      body: JSON.stringify({ decision: 'approve' })
    }).then((r) => r.json()).catch((e) => ({ error: String(e) }));
    check('本页内联批准成功（不必跳治理页）', true, !decided?.error && decided?.code !== 4);
  }
}

// ── 4) the tool card, and the turn every row belongs to ───────────────────────────────────────────
console.log('== 工具卡 · 副作用面 + 每一行说得出在哪一轮 ==');
if (chainReady) {
  const effects = await fetch(`${HOST}/ai/tool-effects?page=1&limit=20`, {
    headers: { Authorization: `Bearer ${admin}`, 'tenant-id': TENANT }
  }).then((r) => r.json()).catch(() => null);
  const items = effects?.data?.items ?? [];
  check('副作用面里有本轮那笔写', true, items.some((e) => e.toolName === 'create_crm_customer' && e.resultType === 'crm_customer'));

  // The half no screen could fake: the rows say which session they came from.
  // 任何界面都伪装不了的那一半：那些行说得出自己来自哪条会话。
  const linked = Number(psql(`select count(*) from ai_audit_logs where conversation_id = '${sessionId}'`
    + ` and tool = 'create_crm_customer' and id > ${auditFloor}`));
  check('审计行带着本会话号（逐轮视图的前提）', true, linked > 0);
  check('批准之后业务行落地了', true, Number(psql(`select count(*) from ${CRM_TABLE} where name = '${customer}'`)) > 0);
}

// ── 5) the deployment's own answer about which agent ──────────────────────────────────────────────
console.log('== 部署方那条面 ==');
if (chainReady) {
  const answer = await api('/keelbase/assistant/agent', { token: admin });
  check('助手 agent 那条面答得出形状（configured + agentId）', true,
    answer.code === 0 && answer.data?.configured !== undefined && 'agentId' in answer.data);

  // The deployment's standing assistant itself: the seed's agent exists, is enabled, and carries a prompt.
  // Without it the drawer opens configured-but-empty, which is the state `YD-30 ③` came from — the gate
  // asserts the seed is in place, not what the model says (that part is not a gate's business).
  // 部署方那条**种子**本身：常驻 agent 在、是启用的、且带着提示词。没有它，抽屉打开就是「配了但空着」
  // —— 那正是 `YD-30 ③` 来时的状态。这里断言的是**种子在不在**，不是模型说了什么（那件事不该由门禁判）。
  const seeded = Number(psql("select count(*) from ai1_agent where id = 9001 and status = 0"
    + " and coalesce(system_prompt, '') <> '' and deleted = 0"));
  check('部署方的常驻助手在、启用、且带提示词（`sql/keelbase-assistant-agent.sql`）', true, seeded > 0);
}

// ── 6) the ordinary caller: `YD-30 ①`'s judgement ─────────────────────────────────────────────────
// The drawer is for an **ordinary** caller, and every `ai1` route it calls is behind `ai1:session:my`.
// This block is that item's judgement, in its own words: an ordinary caller can open the drawer, start a
// session, and send a message. `sql/keelbase-assistant-role-grants.sql` is what makes the permission
// exist at all (the module ships no menus), so the first check here is that the grant is in place.
// 普通调用方那一块 —— `YD-30 ①` 的判据：「普通调用方能开抽屉、能建会话、能发消息」。
// 抽屉调的每条 `ai1` 路由都挡在 `ai1:session:my` 后面，而那条权限要 `sql/keelbase-assistant-role-grants.sql`
// 才存在（模块不带菜单），故这里第一条断言就是那份授予在不在。
console.log('== 普通调用方 · 抽屉的另一半用户 ==');
let ordinarySessionId;
if (chainReady) {
  const granted = Number(psql("select count(*) from system_role_menu rm join system_menu m on m.id = rm.menu_id"
    + " where rm.role_id = 2 and m.permission = 'ai1:session:my' and rm.deleted = 0"));
  check('普通角色（role 2）持有 ai1:session:my', true, granted > 0);

  const ordinary = await ensureOrdinaryCaller(admin);
  const token = await login(ordinary.username, ordinary.password);
  const theirs = await api(`/ai1/session/my-list?agentId=${agentId}`, { token });
  check('普通调用方读得到自己的会话', 0, theirs.code);

  const created = await api('/ai1/session/create-my', { method: 'POST', token, body: { agentId } });
  check('普通调用方能建会话', 0, created.code);
  ordinarySessionId = created.data;

  if (ordinarySessionId) {
    writeFileSync(SEND_FILE, JSON.stringify({ sessionId: ordinarySessionId, content: '请列出我的客户' }));
    const streamed = execFileSync('curl', ['-s', '-N', '--max-time', '180', '-X', 'POST',
      `${API}/ai1/session/message/send-stream`, '-H', 'Content-Type: application/json',
      '-H', `tenant-id: ${TENANT}`, '-H', `Authorization: Bearer ${token}`, '--data-binary', `@${SEND_FILE}`],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
    check('普通调用方发得出消息（流里有 message）', true, /event:\s*message/.test(streamed));
    check('那一条流以 done 收尾', true, /event:\s*done/.test(streamed));

    const mineRows = Number(psql(`select count(*) from ai_audit_logs where conversation_id = '${ordinarySessionId}'`
      + ` and user_id = '${ordinary.id}'`));
    check('那次调用记在普通调用方名下、且带着他的会话号', true, mineRows > 0);

    // 两张卡对普通调用方也要可用——它们读的是**运行时**按调用方限范围的那两条面。
    // Both cards must work for an ordinary caller too: they read the runtime's caller-scoped routes.
    const effects = await fetch(`${HOST}/ai/tool-effects?page=1&limit=5`, {
      headers: { Authorization: `Bearer ${token}`, 'tenant-id': TENANT }
    }).then((r) => r.json()).catch(() => null);
    check('工具卡对普通调用方可用', true, Array.isArray(effects?.data?.items));
    const waiting = await fetch(`${HOST}/ai/my/confirmations`, {
      headers: { Authorization: `Bearer ${token}`, 'tenant-id': TENANT }
    }).then((r) => r.json()).catch(() => null);
    check('确认卡对普通调用方可用', true, Array.isArray(waiting?.data));
  }
}

// ── 7) put the scene back ─────────────────────────────────────────────────────────────────────────
console.log('== 收尾 ==');
const cleanup = [
  `delete from confirmation_requests where tool_name = 'create_crm_customer' and args_json like '%${customer}%'`,
  `delete from ${CRM_TABLE} where name = '${customer}'`,
  ordinarySessionId && `delete from ai1_session_message where session_id = ${ordinarySessionId}`,
  ordinarySessionId && `delete from ai1_session where id = ${ordinarySessionId}`,
  sessionId && `delete from ai1_session_message where session_id = ${sessionId}`,
  sessionId && `delete from ai1_session where id = ${sessionId}`,
  agentId && `delete from ai1_agent where id = ${agentId}`,
  modelId && `delete from ai1_model where id = ${modelId}`,
  providerId && `delete from ai1_provider where id = ${providerId}`
].filter(Boolean).join('; ');
try {
  psql(cleanup);
  ok('链路、待确认与那笔业务写已拆（审计行**刻意**留着）');
} catch (e) {
  bad('收尾', 'ok', e.message);
}

console.log(`\n${pass} ok / ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
