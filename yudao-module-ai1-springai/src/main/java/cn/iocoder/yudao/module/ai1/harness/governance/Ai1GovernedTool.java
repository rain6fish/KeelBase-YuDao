// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.module.ai1.harness.governance;

import java.util.Map;
import java.util.function.Function;

/**
 * 一个要交给治理引擎的外部工具：模型看到的名字与描述、它在治理上的性质，以及**真正执行**的那段。
 *
 * <p>工具只在这里被**描述**一次；注册、风险级、默认禁用与审计都由 {@link Ai1ToolGovernor} 与引擎做。执行体
 * 返回工具自己的结果（MCP/SKILL 是一段文本，RAG 是一串 `Document`），引擎把它原样交回调用方。
 *
 * <p><b>为什么名字必须稳定。</b>注册是幂等的，确认与策略都按名字寻址，故同一个 MCP 工具在每一轮装配时必须
 * 得到同一个名字——那是 `Ai1McpToolFactory.buildToolName` 已经在做的事。SKILL 的 delegate 随 agent 变化，
 * 但名字不变；执行时由线程上下文给出**这一次**的 delegate。
 *
 * An external tool handed to the governance engine: the name and description a model sees, what it is
 * in governance terms, and the body that actually executes it.
 *
 * <p>The tool is **described** here once; registration, risk level, default-disabled and audit are the
 * governor's and the engine's. The body returns the tool's own result (a String for MCP/SKILL, a list
 * of `Document`s for RAG), which the engine hands back to the caller unchanged.
 *
 * <p><b>Why the name must be stable.</b> Registration is idempotent and both confirmations and policy
 * address a tool by name, so the same MCP tool must get the same name on every assembly — already what
 * `Ai1McpToolFactory.buildToolName` does. A SKILL's delegate varies by agent while its name does not;
 * the thread context supplies **this turn's** delegate when the body runs.
 */
public record Ai1GovernedTool(
        String name,
        String description,
        Ai1ToolKind kind,
        String inputSchema,
        Function<Map<String, Object>, Object> body) {
}
