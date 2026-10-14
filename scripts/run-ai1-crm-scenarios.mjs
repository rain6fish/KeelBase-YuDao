#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// The gate for the assistant's own tool path: an agent driven by a real model, whose model comes from
// the host's model page and whose tools come from the runtime's governed face.
//
//   bash scripts/run-server.sh &            # a running stack, PostgreSQL + redis
//   node scripts/run-ai1-crm-scenarios.mjs
//
// Two things are being checked, and neither can be checked by reading the code.
//
//   * **The model comes from the console's model page.** The chain built here is an `ai_api_key` row
//     and an `ai_model` row — the two rows the console's own AI pages write — and the agent points at
//     them. Nothing is passed on the command line and `KEELBASE_MODEL_API_KEY` is not needed: this
//     gate deliberately does **not** start the host's own model loop, because the point is that this
//     path no longer needs it.
//   * **CRM is reachable, and governed.** A read goes through and leaves an audit row; a write is held
//     for a person by the engine, leaves a confirmation, and the customer row does not land. The last
//     one is the half a refusal cannot fake.
//
// Everything it creates, it removes again. It does **not** remove audit rows: those are a hash chain,
// and cutting a link to tidy up after a gate would be a worse act than the untidiness.
//
// Run-scoping, and why not a timestamp. Rows already in the table from other runs would satisfy a
// query that only named the tool, so each assertion is scoped to `ai_audit_logs.id` **above** the
// highest id seen before the run started. A timestamp would also work and is beaten by a clock
// difference between this shell and the database; an id is the database's own ordering.
//
// 助手自己那条工具路径的门禁：一个由**真模型**驱动的 agent，模型来自宿主的模型页、工具来自运行时的受治理工具面。
//
//   bash scripts/run-server.sh &            # 一套跑着的栈：PostgreSQL + redis
//   node scripts/run-ai1-crm-scenarios.mjs
//
// 查的是两件事，而两件都**读代码读不出来**。
//
//   * **模型来自控制台的模型页。** 这里建的链路是一行 `ai_api_key` 加一行 `ai_model`——正是控制台自己的
//     AI 页面会写的那两行——而 agent 指着它们。命令行什么都不传，也不需要 `KEELBASE_MODEL_API_KEY`：本门禁
//     **刻意不**打开宿主自己那条模型回路，因为要证的正是**这条路不再需要它**。
//   * **CRM 够得到，而且受治理。** 读走通了、留下审计行；写被引擎**扣下等人**、留下一条待确认，而客户行**没有
//     落地**。最后这一条是**拒绝伪装不了的**那一半。
//
// 它建的每一样，跑完都拆掉。它**不**删审计行：那是一条哈希链，为了给门禁收尾而剪掉一环，比不整洁更糟。
//
// 按轮次收束，以及为什么不用时间戳。表里**别的轮次**留下的行会满足一条只点了工具名的查询，故每条断言都收束在
// 「本轮开始前见过的最大 `ai_audit_logs.id` **之上**」。时间戳也能用，但会被本 shell 与数据库之间的时钟差打败；
// id 是数据库自己的次序。
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
// The two request bodies go through files: a Chinese argument passed on the command line is mangled
// by the shell, and the model has to be addressed in Chinese.
// 两个请求体走文件：中文经命令行传参会被 shell 改坏，而对着模型说话必须用中文。
const BODY_FILE = join(tmpdir(), `ai1-crm-body-${process.pid}.json`);
const SEND_FILE = join(tmpdir(), `ai1-crm-send-${process.pid}.json`);
const SECRETS = process.env.KEY_FILE || 'D:/Rain6fish/.secrets/spring-ai.env';

let pass = 0;
let fail = 0;
const ok = (name) => { console.log(`  ok    ${name}`); pass += 1; };
const bad = (name, expected, got) => { console.log(`  FAIL  ${name} — expected [${expected}], got [${got}]`); fail += 1; };
const check = (name, expected, got) => (String(expected) === String(got) ? ok(name) : bad(name, expected, got));

const psql = (sql) =>
  execFileSync('docker', ['exec', '-i', '-e', 'PGPASSWORD=password', PG, 'psql', '-U', 'root', '-d', DB, '-tAc', sql], { encoding: 'utf8' }).trim();

async function api(path, { method = 'GET', token, body } = {}) {
  const res = await fetch(`${API}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', 'tenant-id': TENANT, ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {})
  });
  return res.json();
}

/** The body goes through a file: a Chinese argument passed on the command line is mangled by the shell. */
/** 请求体走文件：中文经命令行传参会被 shell 改坏。 */
function postJson(path, token, body) {
  writeFileSync(BODY_FILE, JSON.stringify(body));
  const raw = execFileSync('curl', ['-s', '-X', 'POST', `${API}${path}`,
    '-H', 'Content-Type: application/json', '-H', `tenant-id: ${TENANT}`,
    '-H', `Authorization: Bearer ${token}`, '--data-binary', `@${BODY_FILE}`], { encoding: 'utf8' });
  const json = JSON.parse(raw);
  if (json.code !== 0) throw new Error(`${path} -> ${json.code}: ${json.msg}`);
  return json.data;
}

async function login(username, password) {
  const r = await api('/system/auth/login', { method: 'POST', body: { username, password } });
  if (r.code !== 0) throw new Error(`login(${username}) -> ${r.code}: ${r.msg}`);
  return r.data.accessToken;
}

// ── 0) the stack and the key ──────────────────────────────────────────────────────────────────────
console.log('== 前置 ==');
const up = await fetch(`${HOST}/app/capabilities`).then((r) => r.status).catch(() => 0);
check('宿主在跑（/app/capabilities 200）', 200, up);
if (up !== 200) {
  console.error('\nstart the host first:  bash scripts/run-server.sh &');
  process.exit(1);
}
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
if (!KEY || !MODEL) {
  console.error('\nthe secrets file has no DeepSeek key / model');
  process.exit(1);
}

const admin = await login('admin', 'admin123');
ok('admin 登录');

// The database's own ordering, taken before anything is asked of it — see the header.
// 数据库自己的次序，在向它问任何东西**之前**取下来——见文件头。
const auditFloor = Number(psql('select coalesce(max(id), 0) from ai_audit_logs'));

// ── 1) the chain, through the host's own APIs ─────────────────────────────────────────────────────
console.log('== 前置 · 链路（经宿主自己的 API 建）==');
const marker = `gate-${Date.now()}`;
let keyId; let modelId; let agentId; let sessionId;
try {
  // The console's model page writes exactly these two rows, and this is that page's own API — so the
  // gate proves the page is where this deployment's models live, not a file.
  // 控制台的模型页写下的正是这两行，而这里用的就是那个页面自己的 API——故本门禁证的是：本部署的模型住在
  // **那个页面**里，不是一个文件里。
  keyId = postJson('/ai/api-key/create', admin,
    { name: `gate-${marker}`, platform: 'DeepSeek', apiKey: KEY, url: 'https://api.deepseek.com', status: 0 });
  modelId = postJson('/ai/model/create', admin,
    { keyId, name: `gate-${marker}`, model: MODEL, platform: 'DeepSeek', type: 1, sort: 0, status: 0 });
  // No MCP server and no SKILL: what the agent can call is what the runtime holds.
  // 没有 MCP 服务、也没有 SKILL：这个 agent 能调的就是运行时持有的那些。
  agentId = postJson('/ai1/agent/create', admin, {
    name: `gate-${marker}`,
    introduction: '把受治理工具面接进会话的门禁探针',
    providerId: keyId,
    modelId,
    systemPrompt: '你是一个只会照做的助手。用户让你用工具，你就按他说的顺序把工具用掉，不要多问，也不要只回答文字。',
    knowledgeBaseIds: [],
    mcpIds: [],
    skillIds: []
  });
  // A new agent is created disabled by upstream's own code; the status is set afterwards.
  // 新 agent 由上游自己的代码建成「关闭」；状态是之后设的。
  await api('/ai1/agent/update-status', { method: 'PUT', token: admin, body: { id: agentId, status: 0 } });
  sessionId = postJson('/ai1/session/create-my', admin, { agentId });
  ok(`链路建成（agent=${agentId} session=${sessionId} model=${modelId}）`);
} catch (e) {
  bad('链路建成', 'ok', e.message);
}

// ── 2) a read that goes through, and a write that does not ────────────────────────────────────────
console.log('== 场景 · 真模型驱动的 CRM 一读一写 ==');
if (fail === 0) {
  const customer = `${marker}-customer`;
  const body = `请依次做两件事，两步都要真的调用工具，顺序不要颠倒，不要只回答文字：`
    + `第一步，用列客户的工具把客户列出来；`
    + `第二步，用新建客户的工具新建一个客户，客户名称就是这段：${customer}。`;
  writeFileSync(SEND_FILE, JSON.stringify({ sessionId, content: body }));
  execFileSync('curl', ['-s', '-N', '--max-time', '240', '-X', 'POST', `${API}/ai1/session/message/send-stream`,
    '-H', 'Content-Type: application/json', '-H', `tenant-id: ${TENANT}`,
    '-H', `Authorization: Bearer ${admin}`, '--data-binary', `@${SEND_FILE}`], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });

  console.log('== 审计 · 走通的那一半 ==');
  const after = (tool, outcome) => Number(psql(`select count(*) from ai_audit_logs where id > ${auditFloor}`
    + ` and user_id = '1' and tool = '${tool}' and outcome = '${outcome}'`));
  check('读那一半执行了，且属主是登录用户', true, after('list_crm_customers', 'executed') > 0);

  console.log('== 扣下 · 写没有落地 ==');
  check('写那一半留下「等人确认」的审计行', true, after('create_crm_customer', 'pending') > 0);
  // The confirmation is scoped by this run's own marker inside its arguments, so no earlier run's row
  // can satisfy this query.
  // 那条待确认由**本轮自己的标记**（在它的入参里）圈定，故更早某轮留下的行满足不了这条查询。
  const held = Number(psql(`select count(*) from confirmation_requests where tool_name = 'create_crm_customer'`
    + ` and status = 'pending' and args_json like '%${customer}%'`));
  check('确认行挂在本轮点名的那个客户上', true, held > 0);
  // The half a refusal cannot fake: if the write had run, this row would be here.
  // 拒绝伪装不了的那一半：写若真的跑了，这一行就会在。
  const landed = Number(psql(`select count(*) from ${CRM_TABLE} where name = '${customer}'`));
  check('客户行没有落地', 0, landed);
}

// ── 3) put the scene back ─────────────────────────────────────────────────────────────────────────
console.log('== 收尾 ==');
const customer = `${marker}-customer`;
const cleanup = [
  `delete from confirmation_requests where tool_name = 'create_crm_customer' and args_json like '%${customer}%'`,
  `delete from ${CRM_TABLE} where name = '${customer}'`,
  `delete from ai1_session_message where session_id = ${sessionId}`,
  `delete from ai1_session where id = ${sessionId}`,
  `delete from ai1_agent where id = ${agentId}`,
  `delete from ai_model where id = ${modelId}`,
  `delete from ai_api_key where id = ${keyId}`
].join('; ');
try {
  psql(cleanup);
  ok('链路与那条待确认已拆（审计行**刻意**留着）');
} catch (e) {
  bad('收尾', 'ok', e.message);
}

console.log(`\n${pass} ok / ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
