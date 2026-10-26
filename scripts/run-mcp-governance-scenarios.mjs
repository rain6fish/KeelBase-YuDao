#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// The gate for `D32`'s layer: governance over the host's own tool path, run by a **real model**, over
// **more than one step**.
//
//   bash scripts/run-server.sh &            # a running stack, PostgreSQL + redis
//   node scripts/run-mcp-governance-scenarios.mjs
//
// Every assertion is scoped by a **name unique to this run** — the `gate-<timestamp>` marker every row
// it builds carries — so the gate holds both on a database it has already been run against and
// alongside another run of itself. Its two `count(*)`s are scoped by that same marker (`tool =
// '<this run's server>:…'`), not by the whole table.
//
// It brings up the probe MCP server itself, builds the chain it needs (API key, model, MCP, agent,
// session) through the host's own APIs, asks the model for a two-step job, and then checks the two
// things a governed multi-step run has to leave behind: the probe's own log of what it was called
// with, and the audit's account of who asked. Everything it creates, it removes again.
//
// **It builds the chain on `ai1`'s own rows, and it has now been there twice.** This line first pointed
// at an `ai1` provider and an `ai1` model; it was moved to the console's model page (`ai_api_key` +
// `ai_model`) while the built-in AI module answered the agent's model question, and `D52 ③` put that
// question back on `ai1`'s own catalogue — so it points at `ai1` again. Note the cost of getting this
// wrong, because it is silent in the other direction: an agent naming a row from the wrong catalogue is
// refused at `create`, and the gate fails at its *setup* with `供应商不存在` rather than in anything it
// meant to assert.
//
// The model key is read from `D:\Rain6fish\.secrets\spring-ai.env` and never printed.
//
// `D32` 那一层的门禁：治理宿主**自己**那条工具路径，由一个**真模型**跑，且不止一步。
//
//   bash scripts/run-server.sh &            # 一套跑着的栈：PostgreSQL + redis
//   node scripts/run-mcp-governance-scenarios.mjs
//
// 每条断言都用**本次唯一的名字**收束 —— 就是它建的每一行都带着的那个 `gate-<时间戳>` 标记 —— 故本门禁**在一个
// 已经跑过的库上**、**以及跟它自己的另一次运行并排**时都照样成立；它那两处 `count(*)` 同样由这个标记收束
// （`tool = '<本轮的 server>:…'`），**不**按整张表计。
//
// 它自己拉起探针 MCP 服务，经宿主**自己的 API** 建出它需要的链路（API 密钥 · 模型 · mcp · agent · session），
// 请模型做**两件**事，然后查一次受治理的多步运行**必须留下**的两样：探针自己记下的「被调用成什么样」，以及
// 审计记下的「谁在问」。**它建的每一样，跑完都拆掉。**
//
// **它建链建在 `ai1` 自己的行上，而这件事已经来回过两次。** 本线最早点的是一行 `ai1` 的 provider 与一行
// `ai1` 的 model；在「内置 AI 模块回答 agent 的模型问题」那段时间，它被移到控制台的模型页（`ai_api_key` +
// `ai_model`）上；而 `D52 ③` 把那个问题放回了 `ai1` 自己的目录 —— 于是它又指向 `ai1`。**点错的代价值得看一眼，
// 因为反方向是静默的**：一个点着**另一个目录**的行的 agent 在 `create` 就被拒，门禁于是**在它的前置上**
// 失败（`供应商不存在`），而不是在它要断言的任何一件事上。
//
// 模型 key 读自 `D:\Rain6fish\.secrets\spring-ai.env`，**从不打印**。
import { execFileSync, spawn } from 'node:child_process';
import { existsSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ADMIN_USER, resolveAdminPassword } from './admin-credential.mjs';

const HOST = process.env.HOST_URL || 'http://127.0.0.1:48080';
const API = `${HOST}/admin-api`;
const PG = process.env.PG_CONTAINER || 'keelbase-yudao-pg';
const DB = process.env.PG_DB || 'ruoyi-vue-pro';
const TENANT = process.env.TENANT_ID || '1';

// Two things this gate used to take from the machine rather than from itself, and both of them are
// shared state. A fixed probe port meant a second run could not start its own probe; a fixed note file
// meant a second run deleted the first run's side effect mid-scene. Neither shows up when the gate is
// run the way it is usually run — alone — which is why they are named here.
// 两样东西过去是从机器上取、而不是从自己身上取的，而两样都是**共享状态**。探针端口写死 ⇒ 第二次运行起不了自己的
// 探针；便签文件路径写死 ⇒ 第二次运行会把第一次的副作用在产品中途删掉。**单独跑**——也就是这道门禁通常的跑法——
// 两样都看不见，所以在这里点名。
const freePort = () => new Promise((resolve, reject) => {
  const probe = createServer();
  probe.on('error', reject);
  probe.listen(0, '127.0.0.1', () => {
    const { port } = probe.address();
    probe.close(() => resolve(port));
  });
});
const PORT = Number(process.env.MCP_PORT || (await freePort()));
const NOTE_FILE = process.env.MCP_NOTE_FILE || join(tmpdir(), `probe-notes-${process.pid}.txt`);
// The two request bodies also go through files (a Chinese argument passed on the command line is
// mangled by the shell), and those paths were shared too — a second run read the first run's body, and
// the symptom was a `400: 接口地址不能为空` against a request whose base URL was right there.
// 两个请求体同样走文件（中文经命令行传参会 shell 改坏），而那两条路径也是共享的——第二次运行读到的是第一次的
// 请求体，症状对着一个 base URL 明明在的请求报 `400: 接口地址不能为空`。
const BODY_FILE = join(tmpdir(), `mcp-gov-body-${process.pid}.json`);
const SEND_FILE = join(tmpdir(), `mcp-gov-send-${process.pid}.json`);
const SECRETS = process.env.KEY_FILE || 'D:/Rain6fish/.secrets/spring-ai.env';

let pass = 0;
let fail = 0;
const ok = (name) => { console.log(`  ok    ${name}`); pass += 1; };
const bad = (name, expected, got) => { console.log(`  FAIL  ${name} — expected [${expected}], got [${got}]`); fail += 1; };
const check = (name, expected, got) => (String(expected) === String(got) ? ok(name) : bad(name, expected, got));

const psql = (sql) =>
  execFileSync('docker', ['exec', '-i', '-e', 'PGPASSWORD=password', PG, 'psql', '-U', 'root', '-d', DB, '-tAc', sql], { encoding: 'utf8' }).trim();

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

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
  const file = BODY_FILE;
  writeFileSync(file, JSON.stringify(body));
  const raw = execFileSync('curl', ['-s', '-X', 'POST', `${API}${path}`,
    '-H', 'Content-Type: application/json', '-H', `tenant-id: ${TENANT}`,
    '-H', `Authorization: Bearer ${token}`, '--data-binary', `@${file}`], { encoding: 'utf8' });
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

// ── 1) the probe, brought up by this script so the run is self-contained ──────────────────────────
console.log('== 前置 · 探针 ==');
rmSync(NOTE_FILE, { force: true });
const probe = spawn('node', ['scripts/probe-mcp-server.mjs'], {
  env: { ...process.env, MCP_PORT: String(PORT), MCP_NOTE_FILE: NOTE_FILE },
  stdio: ['ignore', 'pipe', 'pipe']
});
let probeLog = '';
probe.stdout.on('data', (chunk) => { probeLog += chunk.toString(); });
probe.stderr.on('data', (chunk) => { probeLog += chunk.toString(); });
for (let i = 0; i < 40 && !probeLog.includes('listening'); i += 1) {
  await sleep(100);
}
check('探针起来了', true, probeLog.includes('listening'));

const admin = await login(ADMIN_USER, resolveAdminPassword(psql));
ok('admin 登录');

// ── 2) the chain, through the host's own APIs ─────────────────────────────────────────────────────
console.log('== 前置 · 链路（经宿主自己的 API 建）==');
const marker = `gate-${Date.now()}`;
let providerId; let modelId; let mcpId; let agentId; let sessionId;
try {
  // The chain is built on `ai1`'s **own** rows: `D52 ③` put the agent module's model back on its own
  // catalogue, so an agent naming a row from anywhere else is refused — measured, `供应商不存在`.
  // 链路建在 `ai1` **自己的**行上：`D52 ③` 把 agent 模块的模型放回了它自己的目录，故一个点着别处行的 agent
  // 会被拒 —— 实测 `供应商不存在`。
  providerId = postJson('/ai1/provider/create', admin,
    { name: `probe-${marker}`, baseUrl: 'https://api.deepseek.com', apiKey: KEY, status: 0 });
  modelId = postJson('/ai1/model/create', admin,
    { providerId, name: `probe-${marker}`, model: MODEL, type: 0, status: 0 });
  mcpId = postJson('/ai1/mcp/create', admin,
    { name: `probe-${marker}`, transport: 'http', url: `http://127.0.0.1:${PORT}/mcp`, status: 0 });
  agentId = postJson('/ai1/agent/create', admin, {
    name: `probe-${marker}`,
    introduction: 'D32 门禁探针',
    providerId,
    modelId,
    systemPrompt: '你是一个只会照做的助手。用户让你用工具，你就按他说的顺序把工具用掉，不要多问。',
    knowledgeBaseIds: [],
    mcpIds: [mcpId],
    skillIds: []
  });
  // A new agent is created disabled by upstream's own code; the status is set afterwards.
  // 新 agent 由上游自己的代码建成「关闭」；状态是之后设的。
  await api('/ai1/agent/update-status', { method: 'PUT', token: admin, body: { id: agentId, status: 0 } });
  sessionId = postJson('/ai1/session/create-my', admin, { agentId });
  ok(`链路建成（agent=${agentId} session=${sessionId}）`);
} catch (e) {
  bad('链路建成', 'ok', e.message);
}

// ── 3) a two-step job, asked for by a person, carried out by a model ──────────────────────────────
console.log('== 场景 · 真模型驱动的两步 ==');
if (fail === 0) {
  const echoMarker = `${marker}-echo`;
  const noteMarker = `${marker}-note`;
  const body = `请依次做两件事，两步都要完成，顺序不要颠倒：第一步，用那个**回显**的工具回显这段文本：${echoMarker}；第二步，用那个**记笔记**的工具记下这段文本：${noteMarker}。`;
  const file = SEND_FILE;
  writeFileSync(file, JSON.stringify({ sessionId, content: body }));
  execFileSync('curl', ['-s', '-N', '--max-time', '180', '-X', 'POST', `${API}/ai1/session/message/send-stream`,
    '-H', 'Content-Type: application/json', '-H', `tenant-id: ${TENANT}`,
    '-H', `Authorization: Bearer ${admin}`, '--data-binary', `@${file}`], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  // The probe is a child process and its stdout arrives asynchronously; reading it the instant curl
  // returns reads a log that is still in flight, and reports "no calls" for a run that made them.
  // 探针是子进程，它的 stdout 是**异步**到的；curl 一回来就读，读到的是**还在路上**的日志，会把一次真发生了
  // 调用的运行报成「没有调用」。
  await sleep(800);

  const calls = probeLog.split('\n').filter((line) => line.includes('tools/call'));
  console.log(`  note  探针共收到 ${calls.length} 次调用`);
  check('探针收到 ≥2 次', true, calls.length >= 2);
  check('第一步是回显', true, calls.some((line) => line.includes('probe_echo')));
  check('第二步是记笔记', true, calls.some((line) => line.includes('probe_note')));
  const first = calls.findIndex((line) => line.includes('probe_echo'));
  const second = calls.findIndex((line) => line.includes('probe_note'));
  check('顺序对（回显在前）', true, first >= 0 && second > first);
  check('回显的参数是给它的那段', true, calls.some((line) => line.includes(echoMarker)));
  check('记笔记的参数是给它的那段', true, calls.some((line) => line.includes(noteMarker)));

  // The side effect is the half a refusal cannot fake.
  // 副作用是「拒绝」伪装不了的那一半。
  const noted = existsSync(NOTE_FILE) ? readFileSync(NOTE_FILE, 'utf8') : '';
  check('写工具的副作用落地', true, noted.includes(noteMarker));

  console.log('== 审计 · 每一步都记在提出它的人名下 ==');
  // The row names the MCP server, and this run's server carries this run's marker — so the query
  // cannot be satisfied by a row some earlier run left behind. (The first version hard-coded one
  // server's name and read an old row as a pass: a false green, caught by reading it back.)
  // 那一行里写着 MCP 服务的名字，而本轮的服务带着**本轮的标记** ⇒ 该查询**不可能**被更早某次运行留下的行满足。
  // （第一版写死了一个服务名、把一行旧行读成了通过：一个假绿，回读时逮住。）
  const server = `probe-${marker}`;
  const auditOf = (tool) => psql(`select count(*) from ai_audit_logs where user_id = '1' and tool = '${server}:${tool}' and outcome = 'executed'`);
  check('回显那一步有审计行且属主是登录用户', true, Number(auditOf('probe_echo')) > 0);
  check('记笔记那一步同样', true, Number(auditOf('probe_note')) > 0);
  // YD-33：这几行**说得清自己属于哪一轮**。会话号由装配处发布、由切面写进那条列；此前它**恒为 null**，于是凡按
  // 会话读轨迹的人都看不见 MCP 这条路上的任何调用 —— 而「没显示」与「没发生」在界面上长得一样。
  //
  // YD-33: these rows can say which turn they belong to. The session is published at assembly and written onto
  // the row by the aspect; before this it was always null, so a reader of any per-turn trace could not see a
  // single call on the MCP face — and "not shown" looks the same as "did not happen".
  const withSession = psql(`select count(*) from ai_audit_logs where tool like '${server}:%' and conversation_id is not null`);
  check('MCP 审计行带上了会话号', true, Number(withSession) > 0);
}

// ── 4) put the scene back ─────────────────────────────────────────────────────────────────────────
console.log('== 收尾 ==');
probe.kill();
// Only the rows this run actually built. A chain that failed half-way leaves some of these ids
// unset, and the previous version pasted `undefined` into the SQL — where it made psql refuse the
// **whole** statement, so the rows that *had* been built survived a cleanup that reported a failure.
// A failed setup is exactly when the cleanup has to work.
// 只删本轮**确实建出来**的行。建到一半就失败的链路会让其中一些 id 没被赋值，而上一版把 `undefined` 拼进
// SQL——psql 因此拒掉**整条**语句，于是**已经建出来**的那些行，活过了一次报错的收尾。建链失败时，恰恰是收尾
// 最需要能用的时候。
const cleanup = [
  sessionId && `delete from ai1_session_message where session_id = ${sessionId}`,
  sessionId && `delete from ai1_session where id = ${sessionId}`,
  agentId && `delete from ai1_agent where id = ${agentId}`,
  mcpId && `delete from ai1_mcp where id = ${mcpId}`,
  modelId && `delete from ai1_model where id = ${modelId}`,
  providerId && `delete from ai1_provider where id = ${providerId}`
].filter(Boolean).join('; ');
try {
  psql(cleanup);
  rmSync(NOTE_FILE, { force: true });
  ok('链路与探针的痕迹已拆');
} catch (e) {
  bad('收尾', 'ok', e.message);
}
check('探针进程已停', true, probe.killed === true || probe.exitCode !== null);

console.log(`\n${pass} ok / ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
