// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.module.ai1.harness.governance;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;

/**
 * 引擎对一次外部工具调用的裁决，以及**模型该看到的那句话**。
 *
 * <p>引擎的 outcome 带着 status/data/token/error；模型只需要知道「结果」或「没成、为什么」。这里只做那层
 * 翻译，不藏任何治理细节：被拦住了就说被拦住了，而不是假装工具返回了空结果。
 *
 * The engine's verdict on one external tool call, and **the sentence the model should see**.
 *
 * <p>The outcome carries status/data/token/error; the model only needs the result, or why there is
 * none. This is that translation and nothing else: a blocked call says it was blocked rather than
 * pretending the tool returned an empty result.
 */
public record Ai1GovernedOutcome(ExecutionOutcome outcome) {

    /** The model-facing text for this outcome. / 这次裁决给模型看的那段文字。 */
    public String forModel() {
        String status = outcome.status();
        if ("executed".equals(status)) {
            Object data = outcome.data();
            return data == null ? "" : String.valueOf(data);
        }
        if ("blocked".equals(status)) {
            return "Blocked by this deployment's governance policy. Tell the user it did not run.";
        }
        if ("pending_confirmation".equals(status) || "requires_approval".equals(status)) {
            return "Waiting for a person to decide; it has not run yet.";
        }
        return outcome.error() == null ? status : outcome.error();
    }

    public boolean executed() {
        return "executed".equals(outcome.status());
    }

}
