#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// The gate for the console's navigation turn: the assistant names a page, and both channels say so.
//
//   bash scripts/run-server.sh &            # a running stack, PostgreSQL + redis
//   node scripts/run-console-navigation-scenarios.mjs
//
// Everything it asserts is asserted through the same two surfaces the console uses, because the point
// is not that a tool returns a map — it is that a route reaches a caller. So the route is read from
// the non-streaming body and from the stream event, and the run is read back from the audit trail,
// where a `navigate_console_page` that never ran would leave nothing.
//
// The model is off by default on this host (`keelbase.ai.enabled`), which is what this gate wants:
// the phrases are matched by `ConsolePages`'s own route, so a green run says the deterministic path
// reaches the page without a model having been asked. A model would only make the same assertion
// depend on which page the model felt like choosing.
//
// 控制台导航回合的门禁：助手点名一页，而两个通道都说出来。
//
//   bash scripts/run-server.sh &            # 一套跑着的栈：PostgreSQL + redis
//   node scripts/run-console-navigation-scenarios.mjs
//
// 它断言的一切都走**控制台用的那两条面**，因为要点不是「某工具交回了一个 map」，而是**一条路径到达了调用方**。
// 故路径分别从非流式 body 与流事件里读，而一次运行从**审计轨**读回来 —— 那里，一次从没跑过的
// `navigate_console_page` 什么都留不下。
//
// 本宿主默认**没有模型**（`keelbase.ai.enabled`），而这正是本门禁想要的：短语由 `ConsolePages` 自己的路由匹配，
// 故一次绿跑说的是**在没问过模型的情况下**、确定性那条路到达了那一页。有模型只会让同一句断言取决于模型**恰好想选**
// 哪一页。
import { execFileSync } from 'node:child_process';

const HOST = process.env.HOST_URL || 'http://127.0.0.1:48080';
const PG = process.env.PG_CONTAINER || 'keelbase-yudao-pg';
const DB = process.env.PG_DB || 'ruoyi-vue-pro';

let pass = 0;
let fail = 0;
const ok = (name) => { console.log(`  ok    ${name}`); pass += 1; };
const bad = (name, expected, got) => { console.log(`  FAIL  ${name} — expected [${expected}], got [${got}]`); fail += 1; };
const check = (name, expected, got) => (String(expected) === String(got) ? ok(name) : bad(name, expected, got));

const psql = (sql) =>
  execFileSync('docker', ['exec', '-i', '-e', 'PGPASSWORD=password', PG, 'psql', '-U', 'root', '-d', DB, '-tAc', sql], { encoding: 'utf8' }).trim();

async function login(username, password) {
  const res = await fetch(`${HOST}/admin-api/system/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'tenant-id': '1' },
    body: JSON.stringify({ username, password })
  });
  const r = await res.json();
  if (r.code !== 0) throw new Error(`login(${username}) -> ${r.code}: ${r.msg}`);
  return r.data.accessToken;
}

async function chat(token, message) {
  const res = await fetch(`${HOST}/ai/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ message, conversationId: `nav-${Date.now()}` })
  });
  const json = await res.json();
  return json.data || json;
}

/** The console's own channel, read the way the console reads it: `data:` lines, one event each. */
async function stream(token, endpoint, message) {
  const res = await fetch(`${HOST}${endpoint}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ message, conversationId: `nav-${Date.now()}` })
  });
  if (res.status !== 200) return { status: res.status, events: [] };
  const body = await res.text();
  const events = body
    .split('\n')
    .filter((line) => line.startsWith('data:'))
    .map((line) => JSON.parse(line.slice(5).trim()));
  return { status: res.status, events };
}

const types = (events) => events.map((e) => e.type);
const eventOf = (events, type) => events.find((e) => e.type === type);

// ── 0) the stack answers at all ────────────────────────────────────────────────────────────────────
console.log('== 前置 ==');
check('/app/capabilities 200', '200', (await fetch(`${HOST}/app/capabilities`)).status);
const admin = await login('admin', 'admin123');
ok('admin 登录');

// Only rows about this gate's own tool, counted **before and after** rather than over the whole table:
// the table outlives a run, so a whole-table count would make this gate depend on how many times it
// has been run, and a timestamp window would drag in the timezone of whoever runs it.
// 只算本门禁自己那个工具的行，且按**运行前后**两次计数、不按整表：表活得比一次运行长，故整表计数会让门禁取决于
// 它已被跑过多少次，而时间窗会把**跑它那个人的时区**一并算进来。
const navigations = () =>
  Number(psql(`select count(*) from ai_audit_logs where tool = 'navigate_console_page' and outcome = 'executed'`));

const ranBefore = navigations();

// ── 1) a phrase names a page, and the non-streaming body reports it ───────────────────────────────
console.log('== 非流式：打开用户管理 ==');
const answer = await chat(admin, '打开用户管理');
check('路径透出', '/system/user', answer.navigateTo);
check('工具报出', 'navigate_console_page', (answer.toolCalls || [])[0]);

// ── 2) the same phrase on the streaming channel ───────────────────────────────────────────────────
//
// On `/ai/chat/stream` rather than its administrator sibling, and the reason is a finding about this
// host rather than a preference: `/admin/ai/chat/stream` answers 500 here, before the turn is built.
// Upstream's access-log interceptor reads the request body in its non-production branch, and the
// administrator path reaches the endpoint without a wrapper that would let it be read twice, so
// Spring's @RequestBody resolver is left with a stream somebody else has closed
// (`getReader() has already been called`). That is a host-running-in-dev defect that predates this
// change and belongs on this line's list; `/ai/**` is wrapped and reads fine. Both paths run the same
// turn (the runtime's `ChatStreamController.open`), so the event sequence asserted here is the one the
// console would see once that is fixed.
//
// 走 `/ai/chat/stream`、不走它那个管理员兄弟，理由**是关于本宿主的一条发现**、不是偏好：`/admin/ai/chat/stream`
// 在这里答 500，且**在回合被构造之前**。上游的访问日志拦截器在**非生产**分支里读走请求体，而管理员那条路到达端点时
// **没有一层能让它被读两次**的包装，于是 Spring 的 `@RequestBody` 解析器拿到的是一个**已被别人关掉**的流
// （`getReader() has already been called`）。那是**宿主跑在开发模式**下的缺陷，早于本次改动，该记在本线的清单上；
// `/ai/**` 有包装、读得动。两条路跑的是**同一个回合**（运行时的 `ChatStreamController.open`），故这里断言的
// 事件序，就是那条修好之后控制台会看到的那个。
console.log('== 流式：打开用户管理 ==');
const streamed = await stream(admin, '/ai/chat/stream', '打开用户管理');
check('流打开', 200, streamed.status);
check('事件序', 'tool_start,text,navigate,tool_end,done', types(streamed.events).join(','));
check('navigate 事件带的是那条路径', '/system/user', (eventOf(streamed.events, 'navigate') || {}).route);

// ── 3) the same page by a second phrase, so the route is not one hardcoded phrase ─────────────────
console.log('== 非流式：打开客户管理 ==');
const secondPage = await chat(admin, '打开客户管理');
check('第二页也到得了', '/crm/customer', secondPage.navigateTo);

// ── 4) a page nobody offers navigates nowhere rather than somewhere invented ──────────────────────
console.log('== 负数：不存在的页面 ==');
const unknown = await chat(admin, '打开财务共享中心');
check('没有路径透出', undefined, unknown.navigateTo);

// ── 5) a routing turn is still a governed turn: each one is on the audit trail ────────────────────
console.log('== 审计 ==');
// Three turns above named a page; the fourth named one nobody offers and must have run nothing.
// 上面三个回合点了名；第四个点的是没人提供的页面，**必须什么都没跑**。
check('三次导航各留一行审计', 3, navigations() - ranBefore);

console.log();
console.log(`${pass} ok / ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
