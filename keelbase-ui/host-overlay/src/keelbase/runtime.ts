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
 * this an envelope" is the numeric `code`, which is what the host's own interceptor tests too.
 *
 * 运行时的答复是包在**宿主的信封**里的——`{code, message, data}`——因为运行时自带一条 advice 把它们
 * 包起来，好让嵌入部署答得像它的宿主。这里的调用方要的是那个对象，不是它外面那层信封。裸对象原样
 * 放行，故客户端不依赖自己在跟哪一个说话；判「是不是信封」看的是那个**数字** `code`——宿主自己的
 * 拦截器判的也是它。
 */
function unwrapEnvelope(payload: unknown): unknown {
  const p = payload as { code?: unknown; data?: unknown } | null | undefined
  if (p && typeof p === 'object' && typeof p.code === 'number' && 'data' in p) return p.data
  return payload
}

runtime.interceptors.response.use((response) => {
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
