// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.audit.AuditService;
import cn.iocoder.yudao.module.ai1.dal.dataobject.mcp.Ai1McpDO;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Governance over the host's <em>own</em> tool path: the MCP tools its agents hand to the model.
 *
 * <p>This is `D32` — "govern it, do not change how it is assembled". The host assembles those tools
 * itself: `Ai1McpToolFactory.buildToolCallback` builds a Spring AI `FunctionToolCallback` whose
 * function is {@code arguments -> mcpClientTool.callTool(mcp, tool.name(), arguments)}. So the one
 * place every model-issued call lands is {@link Ai1McpClientTool#callTool}, a public method on
 * upstream's own class — and a pointcut there governs the call without touching a line of assembly,
 * which is the same shape `YD-2` used for CRM: govern where the thing is <em>done</em>, not where one
 * caller happens to sit.
 *
 * <p><b>What this layer does and what it deliberately does not.</b> This path is driven by Spring AI's
 * tool-calling loop and does <b>not</b> pass through `GovernedExecutionEngine`, so nothing here holds a
 * call for a human — the engine's confirmation machinery simply is not on this road. This first cut
 * therefore carries the three things that need no such design: <b>identity</b> (who is acting),
 * <b>audit</b> (including failures), and <b>refusal</b> when there is no identity to act as. Mid-loop
 * confirmation is left out on purpose rather than faked, and is recorded as undesigned.
 *
 * <p><b>The arguments are not written to the audit.</b> A tool's arguments are data the caller may not
 * be allowed to keep, so the row names the tool and the outcome — never the payload. The admin
 * console's red line is that identity fields are masked on the way out; the same instinct applies to
 * what an audit keeps.
 *
 * 治理**宿主自己**的那条工具路径：它的 agent 交给模型的 MCP 工具。
 *
 * <p>这是 `D32`——「治理它，但**不动它的装配**」。宿主自己装配那些工具：`Ai1McpToolFactory.buildToolCallback`
 * 造一个 Spring AI 的 `FunctionToolCallback`，其 function 是
 * {@code arguments -> mcpClientTool.callTool(mcp, tool.name(), arguments)}。于是**模型的每一次调用**都落在同一个地方：
 * {@link Ai1McpClientTool#callTool}——上游自己类上的一个 `public` 方法。在那里点一个切面就管住了调用、**装配一行不用碰**；
 * 这与 `YD-2` 对 CRM 的做法同形：治理在**事情被做的地方**，而不是**某一个调用方恰好坐着的地方**。
 *
 * <p><b>这一层做什么、以及它刻意不做什么。</b>这条路由 Spring AI 的工具调用循环驱动，**不经过**
 * `GovernedExecutionEngine`，故它里面**没有任何东西替你把调用扣下等人**——引擎那套确认机器根本不在这条路上。于是第一刀
 * 只承担三件**不需要那套设计**的事：**身份**（谁在行动）、**审计**（含失败）、以及**身份无从谈起时的拒绝**。循环中途的确认
 * **刻意不做，而不是假装做了**，并记为未设计。
 *
 * <p><b>参数不写进审计。</b>工具的入参是调用方可能无权留存的数据，故那一行只写**工具**与**结果**、从不写载荷。管理端那条红线是
 * 身份字段**输出时掩码**；审计留什么，同一个直觉适用。
 */
@Aspect
@Component
public class Ai1McpGovernance {

    /**
     * What this platform writes for "there is no identity". The runtime's governance rules pin their
     * refuse-on-absent-identity test to the same sentinel, so an audit row written here is legible to
     * whatever reads those.
     *
     * 本平台为「没有身份」写下的那个值。运行时的治理规则把「身份缺失即拒」这条判据钉在同一个哨兵上，故这里写的审计行
     * 对读那些规则的人是可读的。
     */
    private static final String NO_IDENTITY = "0";

    private final YudaoCallerId callers;
    private final AuditService audit;

    public Ai1McpGovernance(YudaoCallerId callers, AuditService audit) {
        this.callers = callers;
        this.audit = audit;
    }

    @Around("execution(* cn.iocoder.yudao.module.ai1.harness.mcp.Ai1McpClientTool.callTool(..))")
    public Object govern(ProceedingJoinPoint call) throws Throwable {
        String tool = name(call.getArgs());
        Long caller = callers.current();
        if (caller == null || caller == 0L) {
            audit.append("tool_call", NO_IDENTITY, tool + " refused: no identity");
            throw new AccessDeniedException("这个工具要在有身份的情况下调用");
        }
        String who = String.valueOf(caller);
        try {
            Object result = call.proceed();
            audit.append("tool_call", who, tool + " -> ok");
            return result;
        } catch (Throwable failure) {
            // A failed call is the half an audit is most useful for, so it is written before the
            // failure is handed on. The failure itself is not swallowed.
            // 失败的调用正是审计最有用的那一半，故在把失败递出去**之前**先写下来。失败本身不被吞掉。
            audit.append("tool_call", who, tool + " failed: " + failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /**
     * The tool as the audit should name it: the MCP server and the tool on it. The factory's own
     * assembled name is private to it; this reads the two arguments the method is given, so the row
     * points at the same thing a person would look at in the MCP configuration.
     *
     * 审计该怎么称呼它：MCP 服务 + 该服务上的那个工具。工厂自己拼的名字是它私有的；这里读方法拿到的两个入参，
     * 故那一行指向的东西，与人在 MCP 配置里会看到的是同一个。
     */
    private static String name(Object[] args) {
        String server = args.length > 0 && args[0] instanceof Ai1McpDO mcp ? mcp.getName() : "?";
        String tool = args.length > 1 && args[1] != null ? String.valueOf(args[1]) : "?";
        return server + ":" + tool;
    }
}
