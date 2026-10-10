// SPDX-License-Identifier: Apache-2.0
//
// Serve the built admin UI locally, making the same split the deployment makes: static files at the
// root, the API behind /admin-api and /app-api, KeelBase's runtime surface at the root.
//
// It exists because the built page asks for its API at the *same origin* on purpose: the build empties
// VITE_BASE_URL so the page asks whichever host served it, and the outer nginx decides where
// /admin-api goes (see build-host-ui.sh). That is right on a deployed host, and it means a plain
// static server is not enough locally. Without a proxy every API call is answered by the SPA fallback
// — `index.html`, with HTTP 200 — and the app reads that as a successful response whose body carries
// no data. Sign-in then fails somewhere else entirely, with an error that names neither the API nor
// the proxy, so the fault looks like it is in the client. Proxying here puts the API back where the
// page expects it, and the forwarding rules mirror deploy/demo-ecs/admin-nginx.conf so what you check
// locally is what the deployment does.
//
// 本地起一个「与部署同样的划分」的服务：根路径是静态文件，接口在 /admin-api 与 /app-api 之下，
// KeelBase 自己的面挂在根路径。
//
// 之所以要有它：构建产物**故意**把接口打成**同源**——构建清空 VITE_BASE_URL，页面就问服务它的那个
// 主机，/admin-api 去哪由外层 nginx 决定（见 build-host-ui.sh）。这在部署的机器上是对的，也就意味着
// 本地光有静态服务不够。没有代理时，每个接口请求都被 SPA 兜底接走——返回 `index.html`、状态码 200——
// 应用把它当成一次成功响应，只是响应体里没有数据。登录于是**在别处**失败，报的错既不提接口也不提代理，
// 看上去像是客户端的问题。在这里做代理，就是把接口放回页面预期的位置；转发规则照
// deploy/demo-ecs/admin-nginx.conf 来，于是本地核到的就是部署会做的。
//
// usage: node scripts/serve-host-ui.mjs [--dir <built-dir>] [--port <n>] [--backend <origin>]
//   --dir      the built page to serve      (default <repo>/target/yudao-ui-admin-vue3/dist-prod)
//   --port     the port to listen on        (default 18099)
//   --backend  where the API actually lives (default http://127.0.0.1:48080)
//
// 用法：node scripts/serve-host-ui.mjs [--dir <产物目录>] [--port <端口>] [--backend <接口源>]

import { createServer, request as httpRequest } from 'node:http'
import { request as httpsRequest } from 'node:https'
import { readFile, stat } from 'node:fs/promises'
import { connect } from 'node:net'
import { fileURLToPath } from 'node:url'
import { dirname, extname, join, normalize, resolve } from 'node:path'

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')

const argv = process.argv.slice(2)
const arg = (name, fallback) => {
  const i = argv.indexOf(`--${name}`)
  return i !== -1 && argv[i + 1] ? argv[i + 1] : fallback
}

const ROOT = resolve(arg('dir', join(REPO_ROOT, 'target/yudao-ui-admin-vue3/dist-prod')))
const PORT = Number(arg('port', '18099'))
const BACKEND = arg('backend', 'http://127.0.0.1:48080')
const backend = new URL(BACKEND)

// Which paths go to the API, kept in step with deploy/demo-ecs/admin-nginx.conf: two literal prefixes,
// and KeelBase's root-mounted surface (`/app`, `/ai`, `/audit`) as a regex.
// 哪些路径转给接口，与 deploy/demo-ecs/admin-nginx.conf 保持一致：两条字面前缀，加上 KeelBase 挂在根
// 路径上的面（`/app`、`/ai`、`/audit`）用正则匹配。
const API_PREFIXES = ['/admin-api/', '/app-api/']
const API_ROOT_RE = /^\/(app|ai|audit)\//
const isApiPath = (p) => API_PREFIXES.some((prefix) => p.startsWith(prefix)) || API_ROOT_RE.test(p)

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.map': 'application/json; charset=utf-8'
}

// Proxy instead of falling back to the SPA. A failure here is reported as a failure — never answered
// with `index.html`, which is the silent shape this script exists to remove.
// 走代理，而不是回落到 SPA。这里的失败就报成失败——绝不用 `index.html` 作答，那正是本脚本要消除的
// **静默**形状。
function proxy(req, res, path) {
  const headers = { ...req.headers, host: backend.host }
  const send = backend.protocol === 'https:' ? httpsRequest : httpRequest
  const upstream = send(
    {
      protocol: backend.protocol,
      hostname: backend.hostname,
      port: backend.port || (backend.protocol === 'https:' ? 443 : 80),
      method: req.method,
      path: path || '/',
      headers
    },
    (up) => {
      res.writeHead(up.statusCode || 502, up.headers)
      up.pipe(res)
    }
  )
  upstream.on('error', (err) => {
    console.error(`proxy failed: ${req.method} ${path} → ${BACKEND} (${err.code || err.message})`)
    if (!res.headersSent) {
      res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' })
    }
    res.end(`backend unreachable: ${BACKEND} (${err.code || err.message})\n`)
  })
  req.pipe(upstream)
}

const server = createServer(async (req, res) => {
  const path = (req.url || '/').split('?')[0]

  if (isApiPath(path)) {
    console.log(`→ ${req.method} ${req.url}`)
    proxy(req, res, req.url)
    return
  }

  let url = path
  try {
    url = decodeURIComponent(path)
  } catch {
    res.writeHead(400, { 'Content-Type': 'text/plain' }).end('400')
    return
  }
  let file = normalize(join(ROOT, url))
  if (!file.startsWith(normalize(ROOT))) {
    res.writeHead(403).end('403')
    return
  }
  try {
    if ((await stat(file)).isDirectory()) file = join(file, 'index.html')
  } catch {
    file = join(ROOT, 'index.html') // SPA fallback
  }
  try {
    const body = await readFile(file)
    res.writeHead(200, { 'Content-Type': TYPES[extname(file)] || 'application/octet-stream' })
    res.end(body)
  } catch {
    res.writeHead(404, { 'Content-Type': 'text/plain' })
    res.end('404')
  }
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`serving ${ROOT}`)
  console.log(`  page    http://127.0.0.1:${PORT}/`)
  console.log(`  proxied /admin-api, /app-api, /(app|ai|audit) → ${BACKEND}`)
  // Probe the backend once, so a backend that is simply not running is said out loud at start rather
  // than discovered as a failing sign-in later.
  // 启动时探一次后端：后端只是没在跑，也要在启动时说出来，而不是之后以「登录失败」的形式被发现。
  const probe = connect({ host: backend.hostname, port: Number(backend.port || 80) }, () => {
    console.log(`  backend reachable`)
    probe.end()
  })
  probe.on('error', (err) => {
    console.warn(`  backend NOT reachable at ${BACKEND} (${err.code}) — API calls will return 502`)
    probe.destroy()
  })
})
