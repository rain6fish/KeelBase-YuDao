// SPDX-License-Identifier: Apache-2.0

// The KeelBase runtime's own client, separate from the host's `@/config/axios`.
//
// Two reasons it is not that one. The runtime answers at the server's *root* — `/audit/verify`,
// `/ai/my/confirmations`, `/ai/tool-effects` — not under `/admin-api`, which is where the host's
// client points. And the host's response interceptor reads `{code, data, msg}`: the runtime returns
// the object itself, so `code` is undefined and every successful answer would be taken for a
// failure. The deployment's nginx already routes these paths to the runtime; this client is the
// other half of that.
//
// KeelBase 运行时自己的客户端，与宿主的 `@/config/axios` 分开。
//
// 不共用有两条理由。运行时挂在服务**根路径**上——`/audit/verify`、`/ai/my/confirmations`、
// `/ai/tool-effects`——不在宿主客户端所指的 `/admin-api` 之下。而宿主的响应拦截器读的是
// `{code, data, msg}`：运行时回的是对象本身，于是 `code` 是 undefined，每一个成功的答复都会被当成
// 失败。部署的 nginx 已经把这些路径转给运行时了；这个客户端是那件事的另一半。
import axios, { type AxiosInstance } from 'axios'
import { getAccessToken, getTenantId } from '@/utils/auth'

/** The site's own origin — the runtime shares it, so the paths above are relative to it. */
/** 站点自己的源——运行时与它共用，故上面那些路径是相对它的。 */
const baseURL = (import.meta.env.VITE_BASE_URL as string | undefined) ?? ''

export const runtime: AxiosInstance = axios.create({ baseURL, timeout: 20000 })

/**
 * The runtime's answers arrive inside the host's envelope — `{code, message, data}` — because the
 * runtime carries an advice of its own that wraps them so an embedded deployment answers like its
 * host. Callers here want the object, not the envelope around it. A bare object is passed through
 * unchanged, so the client does not depend on which of the two it is talking to; the test for "is
 * this an envelope" is the numeric `code`.
 *
 * **A non-success code is an error, not data.** The host answers a failed call as HTTP 200 with
 * `{code:500, data:null, msg:'系统异常'}`, and the runtime's own advice says 200 for success (the
 * host's own envelope says 0) — so unwrapping without checking the code handed callers `null` and
 * made a failed decision look like an answer. A decision page then reported "approved" for a write
 * the server had refused. Both success codes are accepted; anything else rejects with the server's
 * message.
 *
 * 运行时的答复是包在**宿主的信封**里的——`{code, message, data}`——因为运行时自带一条 advice 把它们
 * 包起来，好让嵌入部署答得像它的宿主。这里的调用方要的是那个对象，不是它外面那层信封。裸对象原样
 * 放行，故客户端不依赖自己在跟哪一个说话；判「是不是信封」看的是那个**数字** `code`。
 *
 * **非成功码是错误、不是数据。** 宿主把一次失败的调用答成 HTTP 200 + `{code:500,data:null,msg:'系统异常'}`，
 * 而运行时自己的 advice 成功时写 200（宿主自己的信封写 0）—— 于是不看码就拆信封会把 `null` 交给调用方，
 * 一次失败的裁决被读成了答复，页面随即对一次**服务端已拒绝**的写报「已批准」。两个成功码都认，其余一律以
 * 服务端自己的消息 reject。
 */
const ENVELOPE_OK_CODES = new Set([0, 200])

function unwrapEnvelope(payload: unknown): unknown {
  const p = payload as { code?: unknown; data?: unknown; message?: unknown; msg?: unknown } | null | undefined
  if (p && typeof p === 'object' && typeof p.code === 'number') {
    if (!ENVELOPE_OK_CODES.has(p.code)) {
      const said = p.message ?? p.msg
      throw new Error(typeof said === 'string' && said ? said : `runtime error ${p.code}`)
    }
    return 'data' in p ? p.data : payload
  }
  return payload
}

runtime.interceptors.response.use((response) => {
  // A path the front-end's proxy does not know is answered by the SPA fallback: `index.html`, HTTP
  // 200. Nothing here is JSON, and a page that takes that string for its data renders a plausible
  // lie — a list whose length is the byte count of a web page. So this refuses it instead, and says
  // which thing to go and check.
  //
  // 前端的代理**不认识**的路径，会被 SPA 兜底接走：`index.html`、状态码 **200**。这里没有任何 JSON，而
  // 一个把那个字符串当成自己数据的页面，会渲染出一个**像模像样的谎**——一个长度等于某个网页字节数的列表。
  // 故这里把它拒掉，并说出该去查哪一样。
  const contentType = String(response.headers?.['content-type'] ?? '')
  if (!contentType.includes('json')) {
    return Promise.reject(new Error(
      `not a JSON response (content-type: ${contentType || 'none'}) — the proxy has no rule for `
        + `${response.config?.url ?? 'this path'}, so the SPA fallback answered it`,
    ))
  }
  response.data = unwrapEnvelope(response.data)
  return response
})

runtime.interceptors.request.use((config) => {
  // The same bearer token the host's own calls carry: in this deployment the runtime reads the
  // caller from the host's security context, so there is nothing else to send.
  // 与宿主自己的调用所带的是同一个 bearer token：本部署里运行时从宿主的安全上下文取调用方，
  // 故没有别的东西要送。
  const token = getAccessToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  const tenantId = getTenantId()
  if (tenantId) config.headers['tenant-id'] = String(tenantId)
  return config
})

/** The message a failed call should show. The runtime answers Spring's own error bodies. */
/** 一次失败的调用该显示的话。运行时回的是 Spring 自己的错误体。 */
export function runtimeErrorMessage(error: unknown, fallback: string): string {
  const e = error as { response?: { status?: number; data?: unknown }; message?: string }
  const data = e.response?.data as { message?: string; error?: string; detail?: string } | undefined
  const status = e.response?.status
  const said = data?.detail || data?.message || data?.error
  if (said) return status ? `${said} (HTTP ${status})` : said
  return e.message || fallback
}
