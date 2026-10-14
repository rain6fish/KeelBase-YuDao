// SPDX-License-Identifier: Apache-2.0

// 助手面对的那几条面，按它们**实际**返回的形状。
//
// 与 `governanceApi.ts` 分开，因为面住的地方不同：会话与消息在 `ai1` 模块下（`/admin-api/ai1/...`，
// 宿主自己的 axios），而工具卡与确认卡读的是**运行时**在站点根路径上的面（`/ai/...`，见 `runtime.ts`）。
// 两张卡**不在这里重新包一遍**——`governanceApi.ts` 已经包好了（`effectApi` / `confirmationApi`），
// 页面直接用它那两个：一处包、一处改。
//
// 唯一走不了 axios 的是流式那条：它是 POST，而 `EventSource` 只会 GET。故这里自己用 `fetch` +
// `ReadableStream` 读 `text/event-stream`，帧的切分照 SSE 规范（空行分帧、`event:` / `data:` 行）。

// The surfaces the assistant reads, in the shapes they actually return.
//
// Kept apart from `governanceApi.ts` because the surfaces live in different places: sessions and messages
// are the `ai1` module's (`/admin-api/ai1/...`, the host's own axios), while the tool card and the
// confirmation card read the **runtime's** routes at the site root (`/ai/...`, see `runtime.ts`). Those
// two cards are **not** wrapped again here — `governanceApi.ts` already wraps them (`effectApi` /
// `confirmationApi`) and the page uses those: one place to wrap, one place to change.
//
// The only call axios cannot make is the streaming one: it is a POST, and `EventSource` only does GET.
// So this reads `text/event-stream` itself with `fetch` + `ReadableStream`, framing per the SSE spec
// (frames split on a blank line, `event:` / `data:` lines).

import request from '@/config/axios'
import { getAccessToken, getTenantId } from '@/utils/auth'

/**
 * 流式那条路要自己拼 URL，而宿主的接口前缀是一个**模块常量**（`/admin-api`），不是 `request` 上的属性：
 * `@/config/axios` 的默认导出是宿主自己的请求包装（`request.get({ url, params })` 那种形状），**不是**一个
 * axios 实例，故它**没有** `defaults.baseURL` —— 实测拿它会抛 `Cannot read properties of undefined
 * (reading 'baseURL')`，而那一刻页面看上去只是「按了发送、什么都没发生」。
 *
 * The streaming call has to build its own URL, and the host's API prefix is a **module constant**
 * (`/admin-api`) rather than a property of `request`: that default export is the host's own request
 * wrapper (`request.get({ url, params })`), **not** an axios instance, so it has no
 * `defaults.baseURL` — measured, reading it throws `Cannot read properties of undefined (reading
 * 'baseURL')`, and at that moment the page merely looks like "send did nothing".
 */
const ADMIN_API = '/admin-api'

/** 一个会话，按 `Ai1SessionRespVO`。 */
/** One session, per `Ai1SessionRespVO`. */
export interface AssistantSession {
  id: number
  agentId: number
  title: string | null
  createTime: string
  updateTime: string
}

/** 一条消息，按 `Ai1SessionMessageRespVO`。`reasoning` 只有推理模型才有。 */
/** One message, per `Ai1SessionMessageRespVO`. `reasoning` is only there for reasoning models. */
export interface AssistantMessage {
  id: number
  sessionId: number
  role: string
  reasoning: string | null
  content: string | null
  status: number
  createTime: string
}

/** 本部署的助手用哪个 agent；`configured` 为假时 `agentId` 为 null。 */
/** Which agent this deployment's assistant uses; `agentId` is null when `configured` is false. */
export interface AssistantAgent {
  configured: boolean
  agentId: string | null
}

/** 流里的一次事件。`stream`/`done`/`error`/`ping` 不带内容。 */
/** One event off the stream. `stream` / `done` / `error` / `ping` carry no content. */
export interface AssistantStreamEvent {
  event: 'stream' | 'thinking' | 'message' | 'ping' | 'done' | 'error' | string
  data: string
}

export const assistantApi = {
  /**
   * 本部署的助手跟哪个 agent 说话。**不按调用方**——每个人拿到同一个答案，那正是它能答给任何已登录调用方的
   * 原因；没配时 `configured: false`，页面据此如实说，而不是画一个空选择器。
   *
   * Which agent this deployment's assistant talks to. **Not per caller** — everyone gets the same answer,
   * which is why any logged-in caller may read it; unconfigured answers `configured: false`, and the page
   * says so rather than drawing an empty picker.
   */
  agent: () => request.get({ url: '/keelbase/assistant/agent' }) as Promise<AssistantAgent>,

  /**
   * 调用方**在某个 agent 下**的会话，按创建时间倒序——`agentId` 是必填，接口不是「我所有的会话」。
   * 范围由服务端按调用方定，页面不预筛。
   *
   * The caller's sessions **under one agent**, newest first — `agentId` is required; the route is not
   * "all of my sessions". The server scopes them by caller; the page does not pre-filter.
   */
  mySessions: (agentId: number) =>
    request.get({ url: '/ai1/session/my-list', params: { agentId } }) as Promise<AssistantSession[]>,

  /** 以某个 agent 开一条会话；新会话的标题在第一条消息之后才被替换。 */
  /** Opens a session on an agent; the title is replaced only after the first message. */
  createSession: (agentId: number) =>
    request.post({ url: '/ai1/session/create-my', data: { agentId } }) as Promise<number>,

  /** 一条会话里的消息，按时间升序（服务端给的顺序）。 */
  /** The messages of one session, oldest first (the order the server gives). */
  messages: (sessionId: number) =>
    request.get({ url: '/ai1/session/message/my-list', params: { sessionId } }) as Promise<AssistantMessage[]>,

  /**
   * 发一条消息，把流里的事件交给调用方，直到 `done` 或 `error`。
   *
   * **必须走流式**：非流式那条路不会把思考过程与生成状态分开发，页面也就画不出「正在生成」。而这里读流的方式
   * 与 `EventSource` 无关（那条只支持 GET），故走 `fetch`；中止用传进来的 `AbortSignal`。
   *
   * Sends one message and hands each event to the caller until `done` or `error`.
   *
   * **Streaming is not optional here**: the blocking call does not separate the reasoning from the reply
   * and reports nothing about generation state, so a page could not draw "still generating". And the read
   * is not `EventSource` (GET-only), so it is `fetch`; stopping is the caller's `AbortSignal`.
   */
  sendStream: async (
    sessionId: number,
    content: string,
    onEvent: (event: AssistantStreamEvent) => void,
    signal?: AbortSignal,
  ): Promise<void> => {
    const base = (import.meta.env.VITE_BASE_URL as string | undefined) ?? ''
    const response = await fetch(`${base}${ADMIN_API}/ai1/session/message/send-stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${getAccessToken()}`,
        'tenant-id': getTenantId(),
      },
      body: JSON.stringify({ sessionId, content }),
      signal,
    })
    if (!response.ok || !response.body) {
      throw new Error(`send-stream -> HTTP ${response.status}`)
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      // 帧以空行分（`\n\n`；有的实现发 `\r\n\r\n`，两者都收）。
      // Frames split on a blank line (`\n\n`; some implementations send `\r\n\r\n` — both accepted).
      let boundary = buffer.search(/\r?\n\r?\n/)
      while (boundary >= 0) {
        const frame = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + (buffer[boundary] === '\r' ? 4 : 2))
        const parsed = parseFrame(frame)
        if (parsed) {
          onEvent(parsed)
          if (parsed.event === 'done' || parsed.event === 'error') {
            await reader.cancel()
            return
          }
        }
        boundary = buffer.search(/\r?\n\r?\n/)
      }
    }
  },
}

/** 一帧 SSE：`event:` 与 `data:` 行；注释行（以 `:` 开头）与心跳不管。数据按 JSON 字符串解。 */
/** One SSE frame: the `event:` and `data:` lines; comment lines (`:`) and keep-alives are dropped. Data is a JSON string. */
function parseFrame(frame: string): AssistantStreamEvent | null {
  let event = 'message'
  const dataLines: string[] = []
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith(':')) continue
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''))
  }
  if (dataLines.length === 0 && event === 'message') return null
  const raw = dataLines.join('\n')
  let data = raw
  if (raw.startsWith('"') || raw.startsWith('{') || raw.startsWith('[')) {
    try {
      data = JSON.parse(raw)
    } catch {
      // 不是 JSON 就按原样用；一条解析不了的 data 不值得把整条流扔掉。
      // Not JSON: use it as it came — one unparseable `data` is not worth dropping the stream for.
    }
  }
  return { event, data }
}
