#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// M7 开跑前的六项预检，一次跑完 —— 主持人到场前十分钟用，不替读者，也不替主持人判断页面含义。
//
//   node scripts/m7-preflight.mjs                        # 默认 18092 / 48092
//   node scripts/m7-preflight.mjs --ui http://127.0.0.1:18092 --api http://127.0.0.1:48092 \
//     --password admin123 --out ./m7-preflight-out
//
// 它**不起栈**：数据库、宿主、界面服务各自怎么起，见演练记录。它只做一件事 —— 用真浏览器把两个账号
// 各登一遍、把预检要看的那几页打开、把读数抓下来，然后给出一个机读的结论。
//
// 六项与主持卡 B1 的对应：
//
//   ① 界面根与 `/login` 打得开；
//   ② `m7reader` 登录成功，侧栏有「治理」与「CRM 系统」；
//   ③ `m7audit` 登录成功，侧栏有「审计链」，且**没有** CRM、**没有**助手；
//   ④ 治理边界页打得开，路径表不少于 5 行；
//   ⑤ 审计链覆盖卡出现 3 条缺口，并给出缺口登记表版本；
//   ⑥ 助手点「新会话」后发一句话，能出待确认卡或正常回话。
//
// 三条纪律写在这里，因为它们决定这个脚本报什么：
//
//   * **登录走真表单**。登录框在构建产物里预填了 `admin` / `admin123`，读者照着点就会变成管理员；
//     故这里先在输入框里**清空**、再填纸上那个账号，并把「预填的是什么」记进读数 —— 覆盖失败就当场红，
//     不静默退回管理员会话。
//   * **缺模型密钥单列**。无密钥的部署上助手会回一条 401 / `Unauthorized` 的原文；那不是读者失败、
//     也不是环境不可用，记 `no_model_key`（既不算通过也不算失败），由主持人照 B4 记「未测（环境）」。
//   * **退出码只有三种**：`0` 没有失败项、`1` 有失败项、`2` 环境不可用（界面/接口打不开、浏览器起不来）。
//
// 输出：机读 JSON 到 stdout（每项 `pass` / `fail` / `na` / `no_model_key` + 读数 + 截图路径），
// 人读的几行到 stderr，截图与同份 JSON 落到 `--out`。
//
// The six pre-flight checks for M7, in one run. It does **not** start the stack: how the database,
// the host and the UI server come up is the rehearsal record's business. It logs in twice through the
// real form, opens the pages the checklist names, takes readings, and answers machine-readably.
//
// Two things shape what it reports. The login box ships **prefilled** with `admin` / `admin123` in the
// built page, so a reader who just clicks sign-in becomes an administrator — this script clears each
// field before typing and records what was prefilled, and a failed overwrite is red rather than a
// silent administrator session. And a deployment with no model key answers the assistant with a raw
// 401 / `Unauthorized`; that is neither a reader failure nor an unusable environment, so it is reported
// as `no_model_key` — counted as neither pass nor fail.
//
// Exit codes: `0` no failing item, `1` at least one failing item, `2` environment unusable (UI or API
// unreachable, or no browser to drive).
//
// usage: node scripts/m7-preflight.mjs [--ui <base>] [--api <base>] [--password <pwd>] [--out <dir>]
//   --ui        page origin          (default http://127.0.0.1:18092)
//   --api       host origin          (default http://127.0.0.1:48092)
//   --password  password for both accounts (default admin123)
//   --out       where the JSON and the screenshots go (default ./m7-preflight-out)

import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const argv = process.argv.slice(2)
const arg = (name, fallback) => {
  const i = argv.indexOf(`--${name}`)
  return i !== -1 && argv[i + 1] ? argv[i + 1] : fallback
}

const UI = arg('ui', 'http://127.0.0.1:18092').replace(/\/+$/, '')
const API = arg('api', 'http://127.0.0.1:48092').replace(/\/+$/, '')
const PASSWORD = arg('password', 'admin123')
const OUT = resolve(arg('out', 'm7-preflight-out'))
// 两个账号与租户名是主持卡上的纸面事实，不做成参数：写错一个，整场预检就验的是别的账号。
// The two accounts and the tenant are paper facts on the moderator's card, not options: a typo would
// make the whole pre-flight measure a different account.
const TENANT = 'KeelBase'
const READER = 'm7reader'
const AUDITOR = 'm7audit'
const EXPECTED_GAPS = 3

const CHROME_CANDIDATES = [
  process.env.CHROME_PATH,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  process.env.LOCALAPPDATA ? join(process.env.LOCALAPPDATA, 'Google/Chrome/Application/chrome.exe') : null,
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser'
].filter(Boolean)

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const norm = `(s) => (s || '').replace(/\\s+/g, ' ').trim()`

// ---------- 读数容器 / the reading container ----------

// 六项的题头先写死：后面每一项都可能因为前一项红了而根本没跑到，题头不该跟着跑没。
// The six titles are fixed up front: a later item may never run because an earlier one went red, and
// its title should not go missing with it.
const TITLES = [
  '① UI 根与 /login 渲染 — UI root and /login render',
  '② m7reader 登录且侧栏有「治理」与「CRM 系统」 — the reader signs in; sidebar has 治理 and CRM 系统',
  '③ m7audit 登录且侧栏有「审计链」、无 CRM 与助手 — the auditor signs in; sidebar has 审计链, no CRM, no assistant',
  '④ 治理边界页与路径表 — the boundary page and its path table',
  '⑤ 审计链覆盖卡 3 条缺口与登记表版本 — audit chain coverage card: 3 gaps and the registry revision',
  '⑥ 助手：新会话 + 一句话 + 待确认卡或回话 — assistant: a new session, one sentence, a card or a reply'
]
const KEYS = ['ui-login', 'reader-login', 'audit-login', 'boundary', 'audit-chain', 'assistant']
const items = []
const put = (id, key, title, status, readings = {}) => {
  items[id - 1] = { id, key, title, status, readings }
}
const isEnvBad = () => items.some((i) => i && i.readings && i.readings.environment === true)

// ---------- CDP / the browser ----------

class Cdp {
  constructor(ws) {
    this.ws = ws
    this.id = 0
    this.pending = new Map()
    this.events = []
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data)
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve: done, reject } = this.pending.get(msg.id)
        this.pending.delete(msg.id)
        msg.error ? reject(new Error(JSON.stringify(msg.error))) : done(msg.result)
      } else if (msg.method) {
        this.events.push(msg)
      }
    })
  }

  send(method, params = {}) {
    const id = ++this.id
    return new Promise((done, reject) => {
      this.pending.set(id, { resolve: done, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id)
          reject(new Error(`CDP timeout: ${method}`))
        }
      }, 30000)
    })
  }

  async evaluate(expression) {
    const r = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true, userGesture: true })
    if (r.exceptionDetails) {
      throw new Error(`page threw: ${r.exceptionDetails.exception?.description || r.exceptionDetails.text}`.slice(0, 500))
    }
    return r.result?.value
  }

  drain() {
    const out = this.events
    this.events = []
    return out
  }
}

// 只留能让预检失败的那种事件：4xx/5xx、请求失败、页面异常、console.error。
// Keep only the events that can explain a red item: 4xx/5xx, failed requests, page exceptions.
function summarize(events) {
  const out = []
  for (const e of events) {
    if (e.method === 'Network.responseReceived' && e.params.response.status >= 400 && !/favicon/.test(e.params.response.url)) {
      out.push(`HTTP ${e.params.response.status} ${e.params.response.url.slice(0, 160)}`)
    } else if (e.method === 'Network.loadingFailed' && !e.params.canceled && !/favicon/.test(e.params.documentURL || '')) {
      out.push(`failed ${e.params.errorText || ''} ${(e.params.documentURL || '').slice(0, 140)}`)
    } else if (e.method === 'Runtime.exceptionThrown') {
      const d = e.params.exceptionDetails
      out.push(`JS ${(d?.exception?.description || d?.text || '').slice(0, 240)}`)
    }
  }
  return out.slice(-12)
}

function findChrome() {
  for (const path of CHROME_CANDIDATES) {
    try {
      if (existsSync(path)) return path
    } catch {
      // A malformed candidate is simply not a candidate.
    }
  }
  return null
}

async function launchBrowser() {
  if (typeof WebSocket === 'undefined') throw new Error('this Node has no global WebSocket (Node 22 or newer is needed)')
  const chrome = findChrome()
  if (!chrome) throw new Error(`no Chrome/Chromium found — set CHROME_PATH`)
  const profile = mkdtempSync(join(tmpdir(), 'm7-preflight-chrome-'))
  const child = spawn(chrome, [
    '--headless=new',
    '--disable-gpu',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-extensions',
    '--disable-background-networking',
    '--window-size=1440,1100',
    `--user-data-dir=${profile}`,
    // 端口 0：由浏览器挑一个空闲端口并写进 `DevToolsActivePort`，预检因此不必占用任何固定端口，也就不会
    // 撞上别的栈。
    // Port 0: the browser picks a free port and writes it into `DevToolsActivePort`, so the pre-flight
    // holds no fixed port of its own and cannot collide with another stack.
    '--remote-debugging-port=0',
    'about:blank'
  ], { stdio: 'ignore' })

  const portFile = join(profile, 'DevToolsActivePort')
  let port = null
  for (let i = 0; i < 150; i++) {
    if (existsSync(portFile)) {
      const first = readFileSync(portFile, 'utf8').split('\n')[0]
      if (first && Number(first)) {
        port = Number(first)
        break
      }
    }
    await sleep(100)
  }
  if (!port) {
    child.kill()
    throw new Error('Chrome started but never published a DevTools port')
  }

  let target = null
  for (let i = 0; i < 100; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()
      target = list.find((t) => t.type === 'page')
      if (target) break
    } catch {
      // not listening yet
    }
    await sleep(200)
  }
  if (!target) {
    child.kill()
    throw new Error('Chrome published a port but no page target')
  }

  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((done, fail) => {
    ws.addEventListener('open', done, { once: true })
    ws.addEventListener('error', fail, { once: true })
  })
  const cdp = new Cdp(ws)
  await cdp.send('Page.enable')
  await cdp.send('Runtime.enable')
  await cdp.send('Network.enable')
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false })
  return { cdp, child, profile }
}

async function closeBrowser(browser) {
  if (!browser) return
  try {
    await browser.cdp.send('Browser.close')
  } catch {
    // The page may already be gone; the kill below is the belt to this braces.
  }
  try {
    browser.child.kill()
  } catch {
    // already gone
  }
  // Windows 上浏览器会另起子进程，父进程退出不等于它们退出；连子树一起收掉，临时 profile 才删得掉。
  // On Windows the browser forks, so the parent exiting is not the children exiting; take the tree too,
  // which is also what lets the temp profile be removed.
  if (process.platform === 'win32' && browser.child.pid) {
    try {
      spawn('taskkill', ['/PID', String(browser.child.pid), '/T', '/F'], { stdio: 'ignore' })
    } catch {
      // best effort
    }
  }
  await sleep(300)
  try {
    rmSync(browser.profile, { recursive: true, force: true })
  } catch {
    // Windows sometimes holds a file for a moment; a leftover temp profile is not a failure.
  }
}

// ---------- 页面动作 / page actions ----------

async function navigate(cdp, url, settleMs = 4000) {
  await cdp.send('Page.navigate', { url })
  await sleep(settleMs)
}

async function waitFor(cdp, expression, { timeout = 20000, every = 500 } = {}) {
  const deadline = Date.now() + timeout
  let last = null
  while (Date.now() < deadline) {
    try {
      last = await cdp.evaluate(expression)
    } catch {
      last = null
    }
    if (last) return last
    await sleep(every)
  }
  return last
}

async function shot(cdp, name) {
  const png = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
  const path = join(OUT, `${name}.png`)
  writeFileSync(path, Buffer.from(png.data, 'base64'))
  return path
}

// 三个输入框各按「可见 + 题注」找：登录页同时挂着注册/手机号/改密几套隐藏表单，按顺序取第几个会取错。
// Each of the three fields is found by "visible + its own caption": the login page also carries hidden
// register/mobile/reset forms, so picking them by index picks the wrong one.
const FIND_INPUT = `(kind) => {
  const visible = (el) => !!(el && el.getClientRects().length)
  const inputs = [...document.querySelectorAll('input')].filter(visible)
  if (kind === 'pass') {
    return inputs.find((i) => i.type === 'password' && /密码|password/i.test(i.placeholder || ''))
      || inputs.find((i) => i.type === 'password' && !/确认|confirm/i.test(i.placeholder || ''))
      || null
  }
  const re = kind === 'tenant' ? /租户|tenant/i : /用户|账号|username|account/i
  return inputs.find((i) => i.type !== 'password' && re.test(i.placeholder || '')) || null
}`

// 「全选 → 输入」—— 这一步就是「覆盖预填的 admin」本人，而且走的是读者真会做的两个动作：选中框里原来的
// 内容、打上纸上那个账号。**不用**「先赋值空串再赋值」：两次原生 setter 落在同一个 tick 里，Element Plus
// 会把第二次改回空串，表单于是报「该项为必填项」，而输入框看上去明明是填好的 —— 那种失败最难查。
// Select-all, then type — this *is* the "overwrite the prefilled admin" step, and it is the two actions a
// reader really performs. It deliberately does **not** assign an empty string first and then the value:
// two native setter writes in one tick make Element Plus restore the empty one, and the form then says
// "this field is required" while the box visibly holds the right text — the worst kind of failure to
// read.
async function fillField(cdp, kind, value) {
  const focused = await cdp.evaluate(`(() => {
    const el = (${FIND_INPUT})(${JSON.stringify(kind)})
    if (!el) return { ok: false }
    const before = el.value
    el.focus()
    el.select()
    return { ok: true, before }
  })()`)
  if (!focused?.ok) return { ok: false }
  await cdp.send('Input.insertText', { text: value })
  await sleep(150)
  const after = await cdp.evaluate(`(() => {
    const el = (${FIND_INPUT})(${JSON.stringify(kind)})
    return el ? { ok: true, value: el.value } : { ok: false }
  })()`)
  return { ...after, before: focused.before }
}

async function login(cdp, username, shotName = null) {
  await navigate(cdp, `${UI}/login`, 3000)
  // 清存储只是为了「上一位账号的会话不残留」；有的文档（`about:blank`、还没提交的导航）根本没有
  // localStorage，那里读到异常不该把整项判红。
  // Clearing storage is only about not inheriting the previous account's session. Some documents
  // (`about:blank`, a navigation not yet committed) have no localStorage at all; that must not turn red.
  try {
    await cdp.evaluate(`(() => { try { localStorage.clear(); sessionStorage.clear() } catch { } return true })()`)
  } catch {
    // the document is not one we can clear from — the reload below is the real reset
  }
  await cdp.send('Network.clearBrowserCookies')
  await navigate(cdp, `${UI}/login`, 3000)

  // 等表单真的在，而不是等一个固定的毫秒数 —— 冷启动的界面有时要更久。
  // Wait for the form to actually be there rather than for a fixed number of milliseconds; a cold UI
  // sometimes takes longer.
  await waitFor(cdp, `(() => ((${FIND_INPUT})('user') && (${FIND_INPUT})('pass')) ? true : false)()`, { timeout: 25000 })
  const loginShot = shotName ? await shot(cdp, shotName) : null

  const prefilled = await cdp.evaluate(`(() => {
    const u = (${FIND_INPUT})('user')
    const p = (${FIND_INPUT})('pass')
    return { username: u ? u.value : null, passwordPresent: p ? p.value.length > 0 : null }
  })()`)

  const tenant = await fillField(cdp, 'tenant', TENANT)
  // 租户那一栏是自动补全：填完等它给出建议，再点中与纸上一致的那一条。取不到建议也不阻断 —— 提交时
  // 页面自己还会按名字查一次租户 id。
  // The tenant box is an autocomplete: type, wait for suggestions, click the one that matches the card.
  // No suggestion is not fatal — the form resolves the tenant id by name again on submit.
  let tenantPicked = null
  if (tenant.ok) {
    await sleep(1200)
    tenantPicked = await cdp.evaluate(`(() => {
      const items = [...document.querySelectorAll('.el-autocomplete-suggestion li')].filter((li) => li.getClientRects().length)
      const hit = items.find((li) => (${norm})(li.textContent).includes(${JSON.stringify(TENANT)}))
      if (hit) { hit.click(); return (${norm})(hit.textContent) }
      return null
    })()`)
    await sleep(400)
  }

  const user = await fillField(cdp, 'user', username)
  const pass = await fillField(cdp, 'pass', PASSWORD)

  const readings = {
    loginShot,
    prefilledUsername: prefilled?.username ?? null,
    prefilledPasswordPresent: prefilled?.passwordPresent ?? null,
    tenantBefore: tenant.before ?? null,
    tenantFilled: tenant.ok ? tenant.value : null,
    tenantPicked,
    usernameBefore: user.before ?? null,
    usernameFilled: user.ok ? user.value : null
  }

  if (!tenant.ok || !user.ok || !pass.ok) {
    // 表单没出现时说清楚站在哪、屏上是什么 —— 否则这条红只会剩下「找不到」三个字。
    // When the form is absent, say where the page stands and what it shows; otherwise this red item is
    // just the words "not found".
    readings.page = await cdp.evaluate(`({ href: location.href, ready: document.readyState,
      head: (${norm})(document.body?.textContent || '').slice(0, 200) })`).catch(() => null)
    return { ok: false, reason: 'login form fields not found', readings }
  }
  // 覆盖失败（框里仍是 admin）就不许往下走：那会把整场预检验成管理员。
  // A failed overwrite must not go on: it would pre-flight the administrator instead of the reader.
  if (user.value !== username) {
    return { ok: false, reason: `prefilled username not overwritten (still ${user.value})`, readings }
  }

  const clicked = await cdp.evaluate(`(() => {
    const btn = [...document.querySelectorAll('button')].find((b) =>
      b.getClientRects().length && /登\\s*录|Sign\\s*in|Login/i.test((${norm})(b.textContent)))
    if (btn) btn.click()
    return !!btn
  })()`)
  if (!clicked) return { ok: false, reason: 'sign-in button not found', readings }

  // 失败时页面上那条提示只活几秒，故每一轮都读一次、记住最后一次读到的 —— 等到超时再读会什么也读不到。
  // On failure the page's message lives only a few seconds, so it is read on every round and the last
  // glimpse is kept; reading it once after the timeout reads nothing.
  let path = '/login'
  let errorText = []
  for (let i = 0; i < 25; i++) {
    await sleep(1000)
    path = await cdp.evaluate('location.pathname')
    if (path !== '/login') break
    const seen = await cdp.evaluate(`(() => {
      const errs = [...document.querySelectorAll('.el-form-item__error, .el-message--error, .el-message__content, .el-notification')]
        .map((e) => (${norm})(e.textContent)).filter(Boolean)
      return errs.slice(0, 3)
    })()`)
    if (seen?.length) errorText = seen
  }
  return { ok: path !== '/login', path, errorText, readings }
}

const sidebarRows = (cdp) => cdp.evaluate(`([...document.querySelectorAll('.el-menu-item, .el-sub-menu__title')]
  .map((e) => (${norm})(e.textContent)).filter(Boolean))`)

async function sidebar(cdp) {
  const rows = await waitFor(cdp, `(() => {
    const rows = [...document.querySelectorAll('.el-menu-item, .el-sub-menu__title')]
      .map((e) => (${norm})(e.textContent)).filter(Boolean)
    return rows.length ? rows : null
  })()`, { timeout: 15000 })
  return rows || []
}

// ---------- 六项 / the six checks ----------

async function main() {
  mkdirSync(OUT, { recursive: true })

  // ① 界面根与 /login：先走 HTTP，两条都不通就是「环境不可用」而不是「有一项红」。
  // ① UI root and /login over HTTP first; if neither answers, that is an unusable environment rather
  // than a red item.
  let uiRoot = null
  let loginPage = null
  try {
    const root = await fetch(`${UI}/`, { redirect: 'manual' })
    uiRoot = { status: root.status, type: root.headers.get('content-type') || null }
    const body = await root.text()
    uiRoot.hasApp = /id="app"/.test(body)
  } catch (e) {
    uiRoot = { error: e.code || e.message }
  }
  try {
    const res = await fetch(`${UI}/login`, { redirect: 'manual' })
    loginPage = { status: res.status, type: res.headers.get('content-type') || null }
    const body = await res.text()
    loginPage.hasApp = /id="app"/.test(body)
  } catch (e) {
    loginPage = { error: e.code || e.message }
  }
  const uiOk = !!(uiRoot && uiRoot.status === 200 && loginPage && loginPage.status === 200)

  let apiRoot = null
  try {
    const res = await fetch(`${API}/admin-api/system/tenant/get-id-by-name?name=${encodeURIComponent(TENANT)}`, { headers: { 'tenant-id': '1' } })
    apiRoot = { status: res.status }
    try {
      apiRoot.body = await res.json()
    } catch {
      apiRoot.body = null
    }
  } catch (e) {
    apiRoot = { error: e.code || e.message }
  }
  const apiOk = !!(apiRoot && apiRoot.status)

  put(1, KEYS[0], TITLES[0], uiOk ? 'pass' : 'fail', {
    ui: UI,
    root: uiRoot,
    login: loginPage,
    environment: !uiOk
  })

  if (!uiOk || !apiOk) {
    const reason = !uiOk
      ? `UI unreachable at ${UI} (root=${JSON.stringify(uiRoot)}, login=${JSON.stringify(loginPage)})`
      : `API unreachable at ${API} (${JSON.stringify(apiRoot)})`
    for (let id = 2; id <= 6; id++) {
      put(id, KEYS[id - 1], TITLES[id - 1], 'na', { environment: true, reason })
    }
    return
  }
  put(1, KEYS[0], TITLES[0], 'pass', { ...items[0].readings, api: API, apiProbe: apiRoot })

  let browser = null
  try {
    browser = await launchBrowser()
  } catch (e) {
    for (let id = 2; id <= 6; id++) {
      put(id, KEYS[id - 1], TITLES[id - 1], 'na', { environment: true, reason: `no browser: ${e.message}` })
    }
    put(1, KEYS[0], TITLES[0], 'fail', { ...items[0].readings, environment: true, browser: e.message })
    return
  }

  const { cdp } = browser
  try {
    // ② 读者登录。
    cdp.drain()
    const reader = await login(cdp, READER, '1-login')
    const readerSidebar = reader.ok ? await sidebar(cdp) : []
    // ① 的截图就是读者进门那一屏 —— 登录框里预填的是什么，主持人到场时也看得见。
    // Item 1's shot is the screen the reader lands on, so the prefilled box is visible on the record too.
    items[0].readings.shot = reader.readings.loginShot ?? null
    const readerShot = await shot(cdp, '2-reader-sidebar')
    const hasGovernance = readerSidebar.some((t) => t.includes('治理'))
    const hasCrm = readerSidebar.some((t) => /CRM/i.test(t))
    put(2, KEYS[1], TITLES[1], reader.ok && hasGovernance && hasCrm ? 'pass' : 'fail', {
      account: READER,
      path: reader.path ?? null,
      reason: reader.reason ?? null,
      errorText: reader.errorText ?? null,
      sidebar: readerSidebar,
      sidebarHasGovernance: hasGovernance,
      sidebarHasCrm: hasCrm,
      ...reader.readings,
      shot: readerShot,
      network: summarize(cdp.drain())
    })

    // ④ 治理边界（读者身份；B1 第 4 项问的就是读者看得见的那张表）。
    // ④ The boundary page as the reader — B1 item 4 is about the table the reader sees.
    let boundaryStatus = 'na'
    const boundaryReadings = { account: READER }
    if (reader.ok) {
      cdp.drain()
      await navigate(cdp, `${UI}/keelbase-governance/boundary`, 5000)
      // 只数路径表那一个表体。路径表是本页第一张 `el-table`；没渲出来（空白/报错）就继续等。
      // Count the path table's own body only. It is the page's first `el-table`; if nothing has rendered
      // (blank page, error state) keep waiting.
      const table = await waitFor(cdp, `(() => {
        const first = document.querySelector('.el-table')
        if (!first) return null
        const rows = first.querySelectorAll('.el-table__body tbody tr').length
        return rows ? { rows } : null
      })()`, { timeout: 25000 })
      const rows = table?.rows ?? 0
      boundaryReadings.pathTableRows = rows
      boundaryReadings.expectedAtLeast = 5
      boundaryReadings.path = await cdp.evaluate('location.pathname')
      boundaryReadings.pageText = await cdp.evaluate(`(${norm})(document.body.innerText || '').slice(0, 240)`)
      boundaryReadings.shot = await shot(cdp, '4-boundary')
      boundaryStatus = rows >= 5 ? 'pass' : 'fail'
      if (boundaryStatus === 'fail') boundaryReadings.network = summarize(cdp.drain())
    } else {
      boundaryReadings.reason = 'reader did not sign in'
      boundaryReadings.network = []
    }
    put(4, KEYS[3], TITLES[3], boundaryStatus, boundaryReadings)

    // ⑥ 助手：新会话 → 一句话 → 待确认卡或回话；401 记 no_model_key。
    // ⑥ Assistant: new session, one sentence, a card or a reply; a 401 is `no_model_key`.
    let assistantStatus = 'na'
    const assistantReadings = { account: READER }
    if (reader.ok) {
      cdp.drain()
      await navigate(cdp, `${UI}/index`, 4000)
      const entry = await cdp.evaluate(`(() => {
        // 助手的入口是顶栏那个纯图标：类名恰好是 custom-hover、图标是 24 格坐标系里画的那一个。
        // 其余图标（折叠、设置、全屏、语言、用户）各有自己的类名或别的坐标系。
        // The entry is the bare icon in the top bar: its class is exactly custom-hover and its glyph is
        // drawn on a 24-unit grid. The other icons carry their own classes or another grid.
        const candidates = [...document.querySelectorAll('.custom-hover')]
          .filter((el) => el.className.trim() === 'custom-hover')
          .filter((el) => (el.querySelector('svg')?.getAttribute('viewBox') || '').startsWith('0 0 24 24'))
        const el = candidates[0]
        if (!el) return { clicked: false }
        el.click()
        return { clicked: true, candidates: candidates.length }
      })()`)
      assistantReadings.entryClicked = !!entry?.clicked
      assistantReadings.entryCandidates = entry?.candidates ?? 0
      const panel = await waitFor(cdp, `(() => {
        const p = document.querySelector('.assistant-panel')
        if (!p) return null
        const r = p.getBoundingClientRect()
        return r.width > 0 && r.height > 0 ? { width: Math.round(r.width), height: Math.round(r.height) } : null
      })()`, { timeout: 10000 })
      assistantReadings.drawer = panel || null

      if (!panel) {
        assistantStatus = 'fail'
        assistantReadings.reason = 'assistant drawer did not open'
      } else {
        // 抽屉一出来就点「新会话」会点空：agent 读回来之前那个按钮是灰的。等它亮起来再点。
        // Clicking「新会话」the instant the drawer appears hits a grey button: it stays disabled until the
        // agent has been read back. Wait for it to light up first.
        const enabled = await waitFor(cdp, `(() => {
          const b = [...document.querySelectorAll('.assistant-panel button')].find((x) => (${norm})(x.textContent) === '新会话')
          return b && !b.disabled ? true : null
        })()`, { timeout: 20000 })
        assistantReadings.newSessionEnabled = !!enabled
        const newSession = await cdp.evaluate(`(() => {
          const b = [...document.querySelectorAll('.assistant-panel button')].find((x) => (${norm})(x.textContent) === '新会话')
          if (!b) return { clicked: false }
          const disabled = b.disabled
          b.click()
          return { clicked: true, wasDisabled: disabled }
        })()`)
        assistantReadings.newSessionClicked = !!newSession?.clicked
        assistantReadings.newSessionWasDisabled = newSession?.wasDisabled ?? null

        // 等「发送」真的亮起来 —— 会话是异步建的，点完那一瞬它仍是灰的。
        // Wait for「发送」to actually light up: the session is created asynchronously, so it is still grey
        // the instant after the click.
        const ready = await waitFor(cdp, `(() => {
          const t = document.querySelector('.assistant-panel textarea')
          const send = [...document.querySelectorAll('.assistant-panel button')].find((x) => (${norm})(x.textContent) === '发送')
          if (!t || !send || send.disabled) return null
          return { textareaDisabled: !!t.disabled, sendDisabled: !!send.disabled, sessions: (${norm})(document.querySelector('.panel-sub')?.textContent || '') }
        })()`, { timeout: 20000 })
        assistantReadings.sessionReady = ready || null

        if (!enabled || !ready || ready.sendDisabled) {
          assistantStatus = 'fail'
          assistantReadings.reason = !enabled
            ? '「新会话」一直是灰的 —— 助手的 agent 没配好（模型页那条）'
            : 'after「新会话」the send button never became clickable'
          assistantReadings.shot = await shot(cdp, '6-assistant')
          assistantReadings.network = summarize(cdp.drain())
        } else {
          const sentence = '你好，请告诉我你能做什么'
          await cdp.evaluate(`(() => {
            const t = document.querySelector('.assistant-panel textarea')
            if (!t) return false
            t.focus()
            const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set
            setter.call(t, ${JSON.stringify(sentence)})
            t.dispatchEvent(new Event('input', { bubbles: true }))
            return true
          })()`)
          await sleep(400)
          assistantReadings.sentence = sentence
          await cdp.evaluate(`(() => {
            const b = [...document.querySelectorAll('.assistant-panel button')].find((x) => (${norm})(x.textContent) === '发送')
            if (b) b.click()
            return !!b
          })()`)

          // 等到「不再流式」为止：出卡、出回话、出错误，或超时。
          // Wait until it stops streaming: a card, a reply, an error, or the timeout.
          let state = null
          for (let i = 0; i < 45; i++) {
            await sleep(1000)
            state = await cdp.evaluate(`(() => ({
              cards: document.querySelectorAll('.assistant-panel .confirm-card').length,
              cardText: (${norm})(document.querySelector('.assistant-panel .confirm-card')?.textContent || ''),
              replies: [...document.querySelectorAll('.assistant-panel .assistant-bubble .text')]
                .map((e) => (${norm})(e.textContent)).filter((t) => t && t !== '▌'),
              error: (${norm})(document.querySelector('.assistant-panel .panel-error')?.textContent || '') || null,
              streaming: !!document.querySelector('.assistant-panel .caret')
            }))()`)
            if (!state.streaming && (state.cards || state.replies.length || state.error)) break
          }
          assistantReadings.cards = state?.cards ?? 0
          assistantReadings.reply = state?.replies?.slice(-1)[0] || null
          assistantReadings.replyCount = state?.replies?.length ?? 0
          assistantReadings.error = state?.error || null
          assistantReadings.streaming = !!state?.streaming

          const payload = [state?.error, ...(state?.replies || [])].filter(Boolean).join(' ')
          const noKey = /401|unauthori[sz]ed|api key|invalid_api_key|authentication fails/i.test(payload)
          if (state?.cards) {
            assistantStatus = 'pass'
            assistantReadings.outcome = 'confirmation-card'
          } else if (state?.replies?.length && !noKey) {
            assistantStatus = 'pass'
            assistantReadings.outcome = 'reply'
          } else if (noKey) {
            // 无密钥单列：不是读者失败，也不是环境不可用 —— 照 B4 记「未测（环境）」。
            // Without a key this is one of its own: neither a reader failure nor an unusable environment.
            assistantStatus = 'no_model_key'
            assistantReadings.outcome = 'no-model-key'
          } else {
            assistantStatus = 'fail'
            assistantReadings.outcome = state?.error ? 'error' : state?.streaming ? 'still-streaming' : 'no-reply'
          }
          assistantReadings.shot = await shot(cdp, '6-assistant')
          if (assistantStatus !== 'pass') assistantReadings.network = summarize(cdp.drain())
        }
      }
    } else {
      assistantReadings.reason = 'reader did not sign in'
    }
    put(6, KEYS[5], TITLES[5], assistantStatus, assistantReadings)

    // ③ 审计读者登录：侧栏有审计链，且没有 CRM、没有助手。
    // ③ The auditor signs in: 审计链 present, CRM and the assistant absent.
    cdp.drain()
    const audit = await login(cdp, AUDITOR)
    const auditSidebar = audit.ok ? await sidebar(cdp) : []
    const auditShot = await shot(cdp, '3-audit-sidebar')
    const hasChain = auditSidebar.some((t) => t.includes('审计链'))
    const auditHasCrm = auditSidebar.some((t) => /CRM/i.test(t))
    const auditHasAssistant = auditSidebar.some((t) => t.includes('助手'))
    put(3, KEYS[2], TITLES[2], audit.ok && hasChain && !auditHasCrm && !auditHasAssistant ? 'pass' : 'fail', {
      account: AUDITOR,
      path: audit.path ?? null,
      reason: audit.reason ?? null,
      errorText: audit.errorText ?? null,
      sidebar: auditSidebar,
      sidebarHasAuditChain: hasChain,
      sidebarHasCrm: auditHasCrm,
      sidebarHasAssistant: auditHasAssistant,
      ...audit.readings,
      shot: auditShot,
      network: summarize(cdp.drain())
    })

    // ⑤ 审计链覆盖卡。
    // ⑤ The audit chain's coverage card.
    let chainStatus = 'na'
    const chainReadings = { account: AUDITOR, expectedGaps: EXPECTED_GAPS }
    if (audit.ok) {
      cdp.drain()
      await navigate(cdp, `${UI}/keelbase-governance/audit-chain`, 5000)
      const card = await waitFor(cdp, `(() => {
        const rows = [...document.querySelectorAll('.coverage-row')]
        const lines = [...document.querySelectorAll('.coverage-line')]
        const unavailable = (${norm})(document.querySelector('.el-alert--warning')?.textContent || '')
        if (!rows.length && !lines.length && !/取不到|unavailable/i.test(unavailable)) return null
        const registry = lines.map((l) => (${norm})(l.textContent)).find((l) => /缺口登记表版本|registry\\s*revision/i.test(l)) || null
        return {
          gaps: rows.length,
          gapIds: [...document.querySelectorAll('.coverage-gap-id')].map((e) => (${norm})(e.textContent)),
          registryLine: registry,
          registryRevision: registry ? (registry.match(/[0-9a-f]{7,40}/i) || [null])[0] : null,
          cardTitle: (${norm})(document.querySelector('.coverage-line')?.closest('.el-alert')?.querySelector('.el-alert__title')?.textContent || '') || null
        }
      })()`, { timeout: 25000 })
      Object.assign(chainReadings, card || {})
      chainReadings.path = await cdp.evaluate('location.pathname')
      chainReadings.shot = await shot(cdp, '5-audit-chain')
      chainStatus = card && card.gaps === EXPECTED_GAPS && card.registryRevision ? 'pass' : 'fail'
      if (chainStatus === 'fail') chainReadings.network = summarize(cdp.drain())
    } else {
      chainReadings.reason = 'auditor did not sign in'
      chainReadings.network = []
    }
    put(5, KEYS[4], TITLES[4], chainStatus, chainReadings)
  } catch (e) {
    // 任何没被上面接住的东西都记在该项上，不让它变成一次「什么都没说」的崩溃。
    // Anything not caught above lands on the current item rather than becoming a silent crash.
    for (let id = 2; id <= 6; id++) {
      if (!items[id - 1]) put(id, KEYS[id - 1], TITLES[id - 1], 'fail', { error: e.message })
    }
    if (items[0]) items[0].readings.fatal = e.message
  } finally {
    await closeBrowser(browser)
  }
}

// ---------- 收尾 / report ----------

function finish() {
  for (let id = 1; id <= 6; id++) {
    if (!items[id - 1]) put(id, KEYS[id - 1], TITLES[id - 1], 'na', { reason: 'not reached' })
  }
  const list = items.slice(0, 6)
  const fails = list.filter((i) => i.status === 'fail').length
  const noKey = list.filter((i) => i.status === 'no_model_key').length
  const nas = list.filter((i) => i.status === 'na').length
  const environment = isEnvBad()
  const verdict = environment ? 'environment' : fails ? 'fail' : noKey ? 'pass-without-model-key' : 'pass'
  const exitCode = environment ? 2 : fails ? 1 : 0
  const report = {
    tool: 'm7-preflight',
    startedAt,
    finishedAt: new Date().toISOString(),
    targets: { ui: UI, api: API, out: OUT },
    accounts: { reader: READER, auditor: AUDITOR, tenant: TENANT },
    verdict,
    exitCode,
    counts: { pass: list.filter((i) => i.status === 'pass').length, fail: fails, na: nas, no_model_key: noKey },
    items: list
  }
  const json = JSON.stringify(report, null, 2)
  // 落盘失败不该把结论一起吞掉：stdout 上那份仍然是完整的。
  // A failed write must not swallow the verdict: the copy on stdout is still complete.
  try {
    writeFileSync(join(OUT, 'm7-preflight.json'), json)
  } catch (e) {
    process.stderr.write(`could not write ${join(OUT, 'm7-preflight.json')}: ${e.message}
`)
  }
  process.stdout.write(`${json}\n`)

  // 人读的那几行走 stderr：stdout 只留给机读 JSON。
  // The human lines go to stderr; stdout is kept for the machine-readable JSON alone.
  const say = (line) => process.stderr.write(`${line}\n`)
  say(`M7 预检 · ${UI} → ${API}`)
  for (const i of list) {
    const mark = i.status === 'pass' ? 'ok  ' : i.status === 'no_model_key' ? 'nokey' : i.status === 'na' ? 'na  ' : 'FAIL'
    say(`  ${mark}  ${i.title}`)
  }
  if (verdict === 'environment') say('环境不可用 —— 先照演练记录把栈起起来，再跑一次。')
  else if (verdict === 'fail') say(`有 ${fails} 项红 —— 见上面每项的读数与截图（${OUT}）。`)
  else if (verdict === 'pass-without-model-key') say('其余全过；助手一项无模型密钥，照 B4 记「未测（环境）」。')
  else say('六项全过。')
  say(`JSON 与截图：${OUT}`)
  process.exitCode = exitCode
}

const startedAt = new Date().toISOString()
main()
  .catch((e) => {
    for (let id = 1; id <= 6; id++) {
      if (!items[id - 1]) put(id, KEYS[id - 1], TITLES[id - 1], 'fail', { error: e.message })
    }
    if (items[0]) items[0].readings.environment = items[0].readings.environment || isEnvBad()
  })
  .finally(finish)
