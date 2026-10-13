// SPDX-License-Identifier: Apache-2.0

// The governance surfaces this console reads, in the shapes the runtime actually returns them.
//
// Every type below is the runtime's own answer, not the reference console's wider model. Where the
// reference has a field this runtime has no concept of — a username joined onto an audit row, a
// token count, an authorisation verdict — the field is simply absent, and the pages do not render
// it. The alternative would be to draw a column that can only ever be empty, or to invent a value,
// and both are worse than a narrower table.
//
// 这个控制台读的那些治理面，按运行时**实际**返回的形状。
//
// 下面每个类型都是运行时自己的答复，不是参照实现那个更大的模型。参照实现有、而本运行时根本没有
// 概念的字段——审计行上联出来的用户名、token 计数、授权结论——在这里就是**缺席**，页面也就不渲染
// 它们。另一条路是画一个永远只能空着的列、或者编一个值，两者都比一张更窄的表更糟。
import { runtime } from './runtime'

/** One audit row, in the frozen `ai-audit-log-row` shape the runtime fills. */
/** 一行审计行，按运行时填的冻结对象 `ai-audit-log-row` 的形状。 */
export interface AuditRow {
  id: number
  userId: string | null
  action: string | null
  detail: string | null
  isError: boolean
  createdAt: string
  /** C-3: the vocabulary's word for what this call came to; null on rows that are not one tool call. */
  /** C-3：词表里说这次调用**怎么了**的那个词；不是某一次工具调用的行上为 null。 */
  outcome: string | null
  /** C-3: the tool this row is about; null when the row is not about one. */
  /** C-3：这一行讲的那个工具；这一行不是在讲某个工具时为 null。 */
  tool: string | null
}

/** One node of the walk `/audit/verify` performs — the chain itself, not a summary of it. */
/** `/audit/verify` 那次走链的一个节点——是链本身，不是它的摘要。 */
export interface ChainNode {
  id: number
  action?: string | null
  hash?: string | null
  prevHash?: string | null
  broken?: boolean
}

export interface ChainVerification {
  valid: boolean
  checked: number
  brokenIndex: number | null
  chain: ChainNode[]
}

/** One row of `/ai/tool-effects` — the ledger of what AI wrote, as this runtime records it. */
/** `/ai/tool-effects` 的一行——AI 写了什么的台账，按本运行时记录的样子。 */
export interface EffectRow {
  id: number
  toolName: string | null
  resultType: string | null
  resultId: number | string | null
  createdAt: string
  targetExists: boolean
  targetSoftDeleted: boolean
  targetTitle: string | null
  revokeClass: string | null
  status: 'executed' | 'revoked' | string
  revokeStatus: string | null
  revocable: boolean
}

export interface EffectPage {
  total: number
  page: number
  limit: number
  items: EffectRow[]
}

/** One pending write of the caller's own, awaiting an out-of-conversation decision. */
/** 调用方**本人**的一笔待决写入，等一个对话之外的裁决。 */
export interface ConfirmationItem {
  token: string
  toolName: string | null
  /** Null in this runtime: a human-readable summary needs a presentation layer it does not have. */
  /** 本运行时为 null：给人读的摘要需要它没有的展示层。 */
  summary: string | null
  arguments: Record<string, unknown> | null
  mode: string
  riskLevel: string | null
  status: string
  impact: { actions: number } | null
  revokeClass: string | null
  createdAt: string
  decidedAt: string | null
  expiresAt?: string
  executionState: string | null
  executedAt: string | null
  executionError: string | null
}

export interface AuditLogQuery {
  userId?: string
  since?: string
  isError?: string
  limit?: number
  offset?: number
}

export const auditApi = {
  /**
   * The walk over the chain, with the nodes it walked.
   *
   * Only the filters this runtime honours are offered by the callers: it *refuses* `agentId` and
   * `orgId` rather than ignoring them, because a filter that is accepted and not applied returns a
   * list that looks filtered and is not. So the page must not offer them either.
   *
   * 走一遍链，连同它走过的节点。
   *
   * 调用方只提供本运行时**认**的那些筛选：`agentId` 与 `orgId` 是被**拒绝**的，不是被忽略的——
   * 一个被接受却没被施加的筛选回出来的是一份看着像筛过、其实没有的列表。故页面也不该提供它们。
   */
  verify: () => runtime.get<ChainVerification>('/audit/verify').then((r) => r.data),
  logs: (query: AuditLogQuery) => runtime.get<AuditRow[]>('/audit/logs', { params: query }).then((r) => r.data),
}

export const confirmationApi = {
  mine: (status?: string) =>
    runtime
      .get<ConfirmationItem[]>('/ai/my/confirmations', { params: status ? { status } : {} })
      .then((r) => r.data),
  decide: (token: string, decision: 'approve' | 'decline') =>
    runtime.post(`/ai/my/confirmations/${encodeURIComponent(token)}/decide`, { decision }).then((r) => r.data),
}

/**
 * The adversarial demonstration. It is a runtime-specific exhibit — no wire object, not in replay
 * scope — so nothing about it is promised by the contract, and it is read here the same way any
 * other route of this deployment's is.
 *
 * 对抗性演示。它是 runtime-specific 的展示物——无 wire 对象、不在 replay 范围——故契约对它不作任何
 * 承诺，这里读它的方式与读本部署别的路由一样。
 */
export interface ShowcaseScenario {
  id: string
  category: string
  /** What the scenario actually exercises, so a reader can tell what a result did and did not prove. */
  /** 这条场景**实际**驱动什么——好让读者分得清一个结果证明了什么、没证明什么。 */
  drives: string
  expect: string
}

export interface ShowcaseResult {
  id: string
  /** What the console shows. */
  /** 控制台显示的那个。 */
  outcome: string
  /** The runtime's own word for the same event; null when the call never reached the engine. */
  /** 运行时对同一件事自己的叫法；这次调用根本没到引擎时为 null。 */
  engine: string | null
  detail: string
}

export const showcaseApi = {
  scenarios: () => runtime.get<ShowcaseScenario[]>('/ai/security-showcase/scenarios').then((r) => r.data),
  run: (id: string) =>
    runtime.post<ShowcaseResult>(`/ai/security-showcase/run/${encodeURIComponent(id)}`).then((r) => r.data),
}

export const effectApi = {
  /**
   * The ledger. Its scope is the caller's, decided by the server: a non-manager sees their own rows
   * whatever they ask for, so the page does not pre-filter by user for them.
   *
   * 台账。范围由**服务端**按调用方定：非管理员无论请求什么看到的都是自己那些行，故页面不替他们
   * 预筛用户。
   */
  list: (page: number, limit: number, userId?: string) =>
    runtime
      .get<EffectPage>('/ai/tool-effects', { params: { page, limit, ...(userId ? { userId } : {}) } })
      .then((r) => r.data),
  revoke: (id: number) => runtime.delete(`/ai/tool-effects/${id}`).then((r) => r.data),
}
