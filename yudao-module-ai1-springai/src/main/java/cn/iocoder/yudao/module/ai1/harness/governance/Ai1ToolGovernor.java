// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.module.ai1.harness.governance;

import java.util.List;
import java.util.Map;

/**
 * 宿主交给 ai1 的那条缝：把外部工具注册进治理引擎，并按引擎的裁决执行一次调用。
 *
 * <p>ai1 **不自己**决定风险、不自己写审计、不自己拦：它只把「有哪些工具、怎么执行」说出来，剩下的由宿主实现
 * 转到 {@code GovernedExecutionEngine}。没有这条缝的实现时，ai1 的会话装配**明确失败**，而不是悄悄退回一条
 * 不经引擎的路——那正是这次改动要消灭的形态。
 *
 * The seam the host hands ai1: register external tools with the governance engine, and execute one
 * call under the engine's verdict.
 *
 * <p>ai1 does **not** decide risk, write audit rows or block by itself: it says which tools exist and
 * how to run them, and the host implementation forwards that to `GovernedExecutionEngine`. With no
 * implementation of this seam, ai1's session assembly **fails loudly** rather than quietly falling
 * back to a path that bypasses the engine — the shape this change exists to remove.
 */
public interface Ai1ToolGovernor {

    /**
     * Register these tools, idempotently. A write starts disabled; a read is enabled.
     *
     * 注册这些工具，幂等。写从**禁用**起步；读可直接用。
     */
    void registerAll(List<Ai1GovernedTool> tools);

    /**
     * Run one call through the engine.
     *
     * @param caller    the submitting caller; {@code null} when there is none
     * @param sessionId the ai1 session this turn belongs to; {@code null} outside a session
     *
     * 经引擎执行一次调用。
     */
    Ai1GovernedOutcome execute(String name, Map<String, Object> args, Long caller, Long sessionId);

}
