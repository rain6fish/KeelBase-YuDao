// SPDX-License-Identifier: Apache-2.0

// What a trace step says in the reader's words when the runtime sent no `content` of its own.
//
// A step that carries `content` is shown exactly as the runtime wrote it. A tool call, a decision and
// a side effect carry fields instead of prose, so the page composes a line from those fields: which
// tool ran and how the call ended, what a person decided, or which record a write produced and where
// that record now stands. The call's argument string is never part of the line: this console does not
// display `args`, and the coverage statement on the page promises exactly that.
//
// 运行时带 `content` 的步，原样显示它写的那句话。工具调用、裁决与副作用带的是字段而非散文，故页面用
// 这些字段拼一行：哪个工具跑了、这次调用怎么收场；有人作了什么裁决；这次写产出了哪条记录、它现在是
// 什么状态。**调用的参数串永远不在这行里**：这个控制台不显示 `args`，页面上的覆盖声明正是这样承诺的。

import type { TraceStep } from './governanceApi'
import { outcomeLabelKey, toolLabelKey, traceEffectStatusLabelKey, traceOutcomeLabelKey } from './labels'

/** The host's `t`, narrowed to what this module needs. */
/** 宿主的 `t`，收窄到本模块所需的那一点。 */
export type StepTranslator = (key: string) => string

/** Shown when a step carries nothing this page can read. */
/** 一步里没有任何本页读得出的东西时显示的那个。 */
const NOTHING = '—'

/** The word for a code: the reader-language key when there is one, else the code itself. */
/** 一个码对应的词：有读者语言的 key 就用它，否则用码本身。 */
function word(key: string | null, code: string | null | undefined, t: StepTranslator): string {
  if (key) return t(key)
  return code ?? ''
}

/** The non-empty parts, joined the way the rest of the console separates facts. */
/** 去掉空的那几段，按控制台别处分隔事实的写法连起来。 */
function parts(list: string[]): string {
  return list.filter(Boolean).join(' · ')
}

function toolCallText(step: TraceStep, t: StepTranslator): string {
  const name = word(toolLabelKey(step.toolName), step.toolName, t)
  let verdict = ''
  if (step.success === true) {
    verdict = word(outcomeLabelKey('executed'), 'executed', t)
  } else if (step.success === false) {
    verdict = word(outcomeLabelKey('failed'), 'failed', t)
    // The failure's own message, never the arguments that produced it.
    // 失败自己的那句话 —— 绝不带上产生它的参数。
    if (step.errorMessage) verdict += ` — ${step.errorMessage}`
  }
  return parts([name, verdict])
}

function confirmationText(step: TraceStep, t: StepTranslator): string {
  return word(traceOutcomeLabelKey(step.outcome), step.outcome, t)
}

function effectText(step: TraceStep, t: StepTranslator): string {
  const effect = step.effect
  if (!effect) return ''
  const hasId = effect.resultId !== null && effect.resultId !== undefined
  const record = effect.resultType
    ? hasId
      ? `${effect.resultType}#${effect.resultId}`
      : effect.resultType
    : hasId
      ? `#${effect.resultId}`
      : ''
  const status = word(traceEffectStatusLabelKey(effect.status), effect.status, t)
  return parts([record, status])
}

/**
 * One line for a step. The runtime's own `content` wins whenever it exists; otherwise the step's
 * fields speak, and a step with none of them still reads as the dash it read as before.
 *
 * 一步的一行。运行时自己的 `content` 只要在就优先；不在时由该步的字段说话；什么都没有的步，
 * 仍旧读作它此前一直读作的那个破折号。
 */
export function stepText(step: TraceStep, t: StepTranslator): string {
  if (step.content) return step.content
  switch (step.type) {
    case 'tool_call':
      return toolCallText(step, t) || NOTHING
    case 'confirmation':
      return confirmationText(step, t) || NOTHING
    case 'effect':
      return effectText(step, t) || NOTHING
    default:
      return NOTHING
  }
}
