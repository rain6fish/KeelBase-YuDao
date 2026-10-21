// SPDX-License-Identifier: Apache-2.0

// Human words for the runtime's frozen vocabularies, and the colour a tag should take.
//
// The runtime speaks in codes because a code is what a frozen vocabulary is for: `tool_call`,
// `attempted`, `refused`. An auditor reads sentences. This is the one place the console turns one
// into the other — the words themselves stay in the host's i18n, so the reader gets them in their
// own language, and this file only decides *which* word (or that there is none).
//
// Each set below is closed on purpose. A code the runtime sends that is not in its set returns
// `null` rather than a guessed key, and the callers fall back to the raw code — an unknown word is
// shown as itself, never dressed into a meaning it might not have.
//
// 运行时用**码**说话，因为码正是一份**冻结词表**的用处所在：`tool_call`、`attempted`、`refused`。
// 审计人员读的是**句子**。这里是控制台唯一把前者变成后者的地方——**词本身**留在宿主的 i18n 里，好让读者
// 用**自己的语言**拿到它们；本文件只决定**用哪个词**（或**没有词**）。
//
// 下面每一组都是**封闭的**。运行时送来的码若不在这组里，返回 `null`、而不是猜一个 key——调用方回落到
// **原码**：一个不认识的词**如实显示为它自己**，绝不装扮成它可能并不具备的含义。

/** A tag's colour, as Element Plus takes it. */
/** 标签的颜色，按 Element Plus 收的样子。 */
export type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

// The frozen `action` enum of `audit-payload` v2 — v1's twelve plus `effect_revoke`, which v2 added
// for exactly the action of undoing a real side effect. Read off the contract, not guessed: the live
// trail is what showed `effect_revoke` was missing from the first cut of this set.
// `audit-payload` v2 冻结的 `action` 枚举 —— v1 那十二个，加上 v2 **为「撤销一个真实副作用」这一动作**
// 添的 `effect_revoke`。**读自契约、不是猜的**：正是活的审计轨让「这个集合漏了 `effect_revoke`」显形。
const ACTIONS = [
  'chat', 'tool_call', 'navigate', 'error', 'login', 'plan', 'analyze', 'knowledge',
  'delegate', 'tool_confirmation', 'flow_node', 'content_blocked', 'effect_revoke',
]

// The frozen `outcome` vocabulary of `ai-audit-log-row` v5.
// `ai-audit-log-row` v5 冻结的 `outcome` 词表。
const OUTCOMES = [
  'attempted', 'executed', 'pending', 'blocked', 'refused', 'declined', 'failed',
  'approved', 'timeout', 'revoked',
]

// The effective gate `ai-tool-inventory` reports — `auto` | `confirm` | `approval` | `blocked`.
// `ai-tool-inventory` 报的**生效的**闸——`auto` | `confirm` | `approval` | `blocked`。
const GATES = ['auto', 'confirm', 'approval', 'blocked']

// The showcase's own outcomes. Runtime-specific, so the words live here with the rest.
// 对抗展示**自己**的结局。属 runtime-specific，故这些词与其余的一起放在这里。
const SHOWCASE_OUTCOMES: Record<string, string> = {
  allowed: 'allowed',
  denied: 'denied',
  unresolved: 'unresolved',
  requiresConfirmation: 'requiresConfirmation',
  'unknown-scenario': 'unknownScenario',
}

// `trace-step` v3 冻结的 `type` 枚举 —— 六个取值全在，任何实现会发的步都有词。这个集合取自**契约**，
// 不是从本运行时眼下发的那几种推出来的：一份词表要认得出**别人**送来的步。枚举之外的类型仍原样显示。
// The frozen `type` enum of `trace-step` v3 — all six values, so every step any implementation can
// send has a word. The set comes from the **contract**, not from the handful this runtime happens to
// emit today: one vocabulary has to recognise a step sent by **another** implementation. A type
// outside the enum is still shown as itself.
const TRACE_STEPS = ['input', 'assistant', 'tool_call', 'confirmation', 'effect', 'notice']

// The operations audit's own word for the act — `operation-audit-log-row`'s `action` values, which
// its interceptor derives from the method and the path.
// 操作审计对动作自己的叫法 —— `operation-audit-log-row` 的 `action` 取值，由它的拦截器从方法与路径推出。
const OP_ACTIONS = ['LOGIN', 'LOGOUT', 'UPLOAD', 'CREATE', 'UPDATE', 'DELETE']

function inSet(set: readonly string[], code: string | null | undefined): string | null {
  return code && set.includes(code) ? code : null
}

/** The i18n key for an audit row's action, or null when the code is one this console does not know. */
/** 审计行**动作**的 i18n key；码不认识时为 null。 */
export function actionLabelKey(action: string | null | undefined): string | null {
  const code = inSet(ACTIONS, action)
  return code ? `keelbase.vocab.action.${code}` : null
}

/** The i18n key for an audit row's outcome, or null. */
/** 审计行**结局**的 i18n key；否则 null。 */
export function outcomeLabelKey(outcome: string | null | undefined): string | null {
  const code = inSet(OUTCOMES, outcome)
  return code ? `keelbase.vocab.outcome.${code}` : null
}

/** The i18n key for a tool's gate, or null. */
/** 工具**门槛**的 i18n key；否则 null。 */
export function gateLabelKey(gate: string | null | undefined): string | null {
  const code = inSet(GATES, gate)
  return code ? `keelbase.vocab.gate.${code}` : null
}

/** The i18n key for a showcase outcome, or null. */
/** 对抗展示**结局**的 i18n key；否则 null。 */
export function showcaseOutcomeLabelKey(outcome: string | null | undefined): string | null {
  const name = outcome ? SHOWCASE_OUTCOMES[outcome] : null
  return name ? `keelbase.vocab.showcaseOutcome.${name}` : null
}

/** The i18n key for a trace step's type, or null. */
/** 轨迹**步类型**的 i18n key；否则 null。 */
export function traceStepLabelKey(type: string | null | undefined): string | null {
  const code = inSet(TRACE_STEPS, type)
  return code ? `keelbase.vocab.traceStep.${code}` : null
}

/** The i18n key for an operations row's action, or null. */
/** 操作审计行**动作**的 i18n key；否则 null。 */
export function opActionLabelKey(action: string | null | undefined): string | null {
  const code = inSet(OP_ACTIONS, action)
  return code ? `keelbase.vocab.opAction.${code.toLowerCase()}` : null
}

/**
 * What an operations row came to, from its status code: anything under 400 went through. A row with
 * no code is neither — it gets the neutral colour rather than a guess.
 *
 * 操作审计行**结果**如何，按状态码：低于 400 就是过了。没有码的行两者都不是 —— 给它中性的颜色，
 * 而不是猜一个。
 */
export function statusTagType(code: number | null | undefined): TagType {
  if (code === null || code === undefined) return 'info'
  return code >= 400 ? 'danger' : 'success'
}

// The tools this runtime registers, by the names it registers them under. The console names each in the
// reader's language and keeps the registered name beside it: a business reader knows 「新建客户」, and an
// engineer can still see which tool that is. Each name below is a **translation of what that tool
// declares it does** — `/ai/tools` answers a description per tool — and not a guess from its spelling.
// A tool this console does not know keeps its registered name, which is honest rather than invented.
//
// 本运行时注册的那些工具，按它注册的名字。控制台给每一个配**读者语言**的名字、并把注册名留在旁边：业务读者
// 认得「新建客户」，工程师也看得出那是哪个工具。下面每个名字都是**那个工具自己声明的用途**（`/ai/tools`
// 逐个答了描述）的**译文**，不是从拼写猜的。控制台不认识的工具**保留它的注册名** —— 那是诚实，不是编造。
const TOOLS = [
  'list_yudao_users', 'analyze_customer_risk', 'create_followup', 'escalate_customer',
  'list_my_oa_leaves', 'approve_oa_leave', 'navigate_console_page',
  'list_crm_customers', 'create_crm_customer', 'update_crm_customer',
  'list_my_notify_messages', 'get_notify_message', 'mark_notify_message_read', 'mark_all_notify_messages_read',
  'list_generated_products', 'create_generated_products',
]

/** The i18n key for a tool's name in the reader's language, or null when this console does not know it. */
/** 工具**读者语言名字**的 i18n key；控制台不认识它时为 null。 */
export function toolLabelKey(name: string | null | undefined): string | null {
  const code = inSet(TOOLS, name)
  return code ? `keelbase.tool.${code}` : null
}

/**
 * 工具**读者语言那一行用途**的 i18n key；控制台不认识它时为 null。
 *
 * 名字与用途是**两条** key，不是一个：名字是一行标题里的词，用途是政策页上让人**不用打开工具**就知道它
 * 会做什么的那一句。两者的中文都是**对工具自己声明的用途的翻译**（`/ai/tools` 逐个答了描述），不是从名字
 * 猜的；控制台不认识某个工具时两条都回落到**它的注册名与它自己的声明**，那是诚实，不是编造。
 */
export function toolDescriptionKey(name: string | null | undefined): string | null {
  const code = inSet(TOOLS, name)
  return code ? `keelbase.toolDesc.${code}` : null
}

/**
 * The i18n key for **what an outcome means**, one line — the thing a reader who is not an engineer needs
 * and a single word cannot carry. "Pending" is a state; 「还没真的写入，等有人批准」is what it means to
 * the business.
 *
 * **一个结局意味着什么**的 i18n key，一句话 —— 那不是工程师的读者需要它，而**一个词**载不动它。「待决」
 * 是一种状态；「还没真的写入，等有人批准」才是它对业务的意义。
 */
export function outcomeMeaningKey(outcome: string | null | undefined): string | null {
  const code = inSet(OUTCOMES, outcome)
  return code ? `keelbase.meaning.${code}` : null
}

/**
 * The colour an audit row's outcome reads as. `attempted` is deliberately *not* a verdict — the
 * engine writes it before the call has a result — so it takes the neutral colour rather than a
 * success or a failure one. A call with no outcome (a non-tool row) falls back to `isError`.
 *
 * 审计行的**结局**读起来该是什么颜色。`attempted` **刻意不是**一个判定——引擎在调用**还没有**结果时
 * 就写下它——故它取中性的颜色，而不是成功或失败的颜色。没有 outcome 的行（非工具行）回落到 `isError`。
 */
export function outcomeTagType(outcome: string | null | undefined, isError: boolean): TagType {
  switch (outcome) {
    case 'executed':
    case 'approved':
      return 'success'
    case 'revoked':
    case 'attempted':
      return 'info'
    case 'pending':
    case 'timeout':
      return 'warning'
    case 'blocked':
    case 'refused':
    case 'declined':
    case 'failed':
      return 'danger'
    default:
      return isError ? 'danger' : 'success'
  }
}

/** The colour a tool's gate reads as. */
/** 工具**门槛**读起来该是什么颜色。 */
export function gateTagType(gate: string | null | undefined): TagType {
  switch (gate) {
    case 'auto':
      return 'success'
    case 'blocked':
      return 'danger'
    case 'confirm':
    case 'approval':
      return 'warning'
    default:
      return 'info'
  }
}

/** The colour a showcase outcome reads as: a finding is red, the expected result is not. */
/** 对抗展示**结局**读起来该是什么颜色：一条**发现**是红的，期望的结果不是。 */
export function showcaseOutcomeTagType(outcome: string | null | undefined): TagType {
  switch (outcome) {
    case 'allowed':
    case 'denied':
    case 'unresolved':
      return 'success'
    case 'requiresConfirmation':
      return 'warning'
    case 'unknown-scenario':
      return 'info'
    default:
      return 'danger'
  }
}
