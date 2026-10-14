// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.module.ai1.harness.local;

import cn.iocoder.yudao.module.ai1.dal.dataobject.agent.Ai1AgentDO;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Tools a session gets that are neither an MCP server nor a SKILL: ones the deployment hands over
 * directly, in the process.
 *
 * <p>The two sources this module already has both leave it — an MCP client reaches a server, a SKILL
 * is materialised into a sandbox. A deployment that keeps its own governed tool catalogue has no
 * server to point at and no reason to run one: the tools are objects it already holds, and what was
 * missing was a place to put them. This is that place, and it sits beside the other two on purpose —
 * {@code Ai1McpToolFactory} and {@code Ai1SkillToolFactory} are the shape it follows, including the
 * detail that matters most: <b>it is a factory, not a {@code @Bean} that hands tools to Spring AI</b>.
 * The caller is captured where the session is assembled and carried to wherever the model ends up
 * running the call, because those are not the same thread.
 *
 * <p>Everything a deployment hands over this way is still the deployment's to govern. This interface
 * only carries them into the session; what happens when one is called is decided by whoever wrote it.
 *
 * 一次会话拿到的、既不是 MCP 服务、也不是 SKILL 的工具：部署方**在进程内**直接交过来的那些。
 *
 * <p>本模块已有的两个来源都要**走出去**——MCP 客户端去够一台服务端，SKILL 被物化进一个沙箱。一个自己就有
 * 受治理工具目录的部署，没有服务端可指、也没有理由为它跑一个：那些工具是它**本来就握着的对象**，缺的只是一个
 * **放它们的地方**。这就是那个地方，而它**刻意**与另外两个并排——`Ai1McpToolFactory` 与
 * `Ai1SkillToolFactory` 就是它照着做的形状，包括最要紧的那一处细节：<b>它是一个工厂，不是一个把工具交给
 * Spring AI 的 `@Bean`</b>。调用方在**装配会话**的地方被捕获、并一路带到模型最终跑那次调用的地方，因为两者
 * 不是同一条线程。
 *
 * <p>由本接口交出去的一切，**仍然归部署方治理**。本接口只把它们带进会话；被调用时发生什么，由写下它的人决定。
 *
 * <p><b>A file of KeelBase's inside an upstream module</b> — the seam is ours and so is its one host-side
 * implementation; both are registered in {@code keelbase-docs/KEELBASE-UPSTREAM.md}.
 *
 * <p><b>上游模块里的一份 KeelBase 文件</b> —— 这条缝是我们的、宿主侧那唯一的实现也是；两者都登记在
 * {@code keelbase-docs/KEELBASE-UPSTREAM.md}。
 */
public interface Ai1LocalToolProvider {

    /**
     * The tools this agent's session gets, on top of the MCP and SKILL ones.
     *
     * 这个 agent 的会话除 MCP 与 SKILL 之外还拿到的工具。
     */
    List<ToolCallback> buildTools(Ai1AgentDO agent);
}
