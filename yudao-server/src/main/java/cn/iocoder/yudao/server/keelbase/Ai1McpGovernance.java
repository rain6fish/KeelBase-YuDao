// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.audit.AuditService;
import cn.iocoder.yudao.module.ai1.dal.dataobject.mcp.Ai1McpDO;
import cn.iocoder.yudao.module.ai1.harness.mcp.Ai1McpClientTool;
import cn.iocoder.yudao.module.ai1.harness.mcp.Ai1ToolCallSession;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
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
 * <p><b>The outcome and the tool name go on the row's own columns.</b> `C-3` added those two columns
 * and filled them at every writer in the runtime; this writer was missed, so its rows carried the two
 * facts only inside the sentence — and a reader that had switched to the columns, as the runtime's
 * readers did, found nothing. The sentence stays, because it is what a person reads in the raw table;
 * the columns are what the evaluation plane reads.
 *
 * 参数不写进审计。工具的入参是调用方可能无权留存的数据，故那一行只写**工具**与**结果**、从不写载荷。管理端那条红线是
 * 身份字段**输出时掩码**；审计留什么，同一个直觉适用。
 *
 * <p><b>结局与工具名落在行**自己的列**上。</b>`C-3` 加了那两列、并在运行时的**每一个写入点**填好；这一个写入点被漏了，
 * 于是它的行只把这两个事实带在**那句话里** —— 而一个像运行时那些读取方一样改用**列**的读者，什么都找不到。那句话留着，
 * 因为那是人**在原始表里读**的东西；而列是**评估面**读的东西。
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
 *
 * <p><b>写：明确声明是写的，一律拒绝（`YD-T22` 的「丙」，2026-10-16）。</b>这条路由 Spring AI 的工具循环驱动、
 * 不经过引擎，故**扣下来等人**在这条路上没有落点：批准之后没有谁能执行它（引擎只执行注册表里的工具，而 MCP 工具是按
 * agent 动态发现的、进不去那张表）。于是本层选**拒绝**而不是**假确认**：一个**明确声明**了自己是写的工具
 * （MCP 的 `destructiveHint: true`，或 `readOnlyHint: false`）**当场被拒**，既不执行、也不留存任何载荷。
 *
 * <p><b>⚠ 覆盖面 = 服务端声明的质量，这是刻意的。</b>**没有声明**的工具照旧放行（即今天的行为）—— 若按 MCP 规范
 * 的缺省值（「未声明即非只读」）去拒，那些不声明的服务里**连读都会一起被拒**，那是把一个安全收紧做成了功能停摆。
 * 故此处取**宽松**一侧：只拒**明确说过**自己是写的。拿不到工具清单时**同样不拒**（记一条告警）—— 一次元数据抖动不该
 * 变成一次拒绝。
 *
 * <p><b>The write case: an explicitly declared write is refused (`YD-T22`'s「丙」, 2026-10-16).</b> This path is
 * driven by Spring AI's tool loop and does not pass through the engine, so holding a call for a person has nowhere
 * to land here: after approval nothing could execute it (the engine runs only tools in its registry, and MCP tools
 * are discovered per agent and cannot enter it). So this layer **refuses** rather than **fakes a confirmation**: a
 * tool that **explicitly declares itself** a write (`destructiveHint: true`, or `readOnlyHint: false`) is refused
 * on the spot — nothing runs, and no payload is retained.
 *
 * <p><b>⚠ The coverage equals the quality of the servers' declarations, deliberately.</b> A tool that declares
 * **nothing** is still allowed (today's behaviour). Refusing on the MCP spec's defaults ("undeclared is not
 * read-only") would refuse **reads too** on servers that declare nothing — turning a security tightening into an
 * outage. Hence the lenient side: only an **explicit** claim of being a write is refused. When the tool list cannot
 * be read it likewise does not refuse (a warning is logged) — a metadata hiccup must not become a refusal.
 */
@Aspect
@Component
@Slf4j
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
    private final Ai1McpClientTool mcpClientTool;

    /**
     * 写策略：`refuse`（缺省）＝**明确声明**自己是写的调用一律拒绝；`allow` ＝ 回到今天的行为（不拒）。
     * 做成一条开关而不是一次改码：部署要**临时放行**时不必改代码。
     *
     * The write policy: `refuse` (default) refuses calls whose tool **explicitly** declares itself a write;
     * `allow` restores today's behaviour. A switch rather than a code change, so a deployment can lift it
     * temporarily without editing anything.
     */
    @Value("${keelbase.mcp.write-policy:refuse}")
    private String writePolicy;

    public Ai1McpGovernance(YudaoCallerId callers, AuditService audit, Ai1McpClientTool mcpClientTool) {
        this.callers = callers;
        this.audit = audit;
        this.mcpClientTool = mcpClientTool;
    }

    @Around("execution(* cn.iocoder.yudao.module.ai1.harness.mcp.Ai1McpClientTool.callTool(..))")
    public Object govern(ProceedingJoinPoint call) throws Throwable {
        String tool = name(call.getArgs());
        Long caller = callers.current();
        // 这一轮所属的会话，与「谁在行动」同一条线索：它在装配处被发布（见 `Ai1ToolCallSession`），故这几行
        // **说得清自己属于哪一轮** —— 直到本次改动之前，它们只能被读成「这个调用方最近的调用」。
        String conversationId = conversationId();
        if (caller == null || caller == 0L) {
            audit.append("tool_call", NO_IDENTITY, tool + " refused: no identity", true, "refused", tool,
                    conversationId);
            throw new AccessDeniedException("这个工具要在有身份的情况下调用");
        }
        String who = String.valueOf(caller);
        // 明确声明是写的 ⇒ 拒（见文件头）。**在**身份之后：无身份那条判据仍是第一位的、措辞不变。
        if (declaredWrite(call.getArgs())) {
            audit.append("tool_call", who, tool + " refused: declared a write, and this path does not confirm",
                    true, "refused", tool, conversationId);
            throw new AccessDeniedException("拒绝：这个 MCP 工具明确声明了自己是写操作，而本运行时的这条路径不提供"
                    + "确认（扣下来等人在这条路上没有落点 —— 批准之后没有谁能执行它）。"
                    + "请改走受治理的工具面；若它其实是只读的，让该服务把它声明为 readOnlyHint。");
        }
        try {
            Object result = call.proceed();
            audit.append("tool_call", who, tool + " -> ok", false, "executed", tool, conversationId);
            return result;
        } catch (Throwable failure) {
            // A failed call is the half an audit is most useful for, so it is written before the
            // failure is handed on. The failure itself is not swallowed.
            // 失败的调用正是审计最有用的那一半，故在把失败递出去**之前**先写下来。失败本身不被吞掉。
            audit.append("tool_call", who, tool + " failed: " + failure.getClass().getSimpleName(),
                    true, "failed", tool, conversationId);
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
    /**
     * **明确声明**自己是写的工具：`destructiveHint: true`，或 `readOnlyHint: false`。**没声明的不算** ——
     * 覆盖面的上限是刻意的（见文件头）。工具清单取自与装配处**同一个缓存**，故这一步不额外打网络。
     *
     * A tool that **explicitly** declares itself a write: `destructiveHint: true`, or `readOnlyHint: false`.
     * Declaring nothing does not count — the coverage limit is deliberate (see the header). The list comes from
     * the same cache the assembly reads, so this adds no round trip.
     */
    private boolean declaredWrite(Object[] args) {
        if (!"refuse".equals(writePolicy)) {
            return false;
        }
        if (args.length < 2 || !(args[0] instanceof Ai1McpDO mcp) || args[1] == null) {
            return false;
        }
        String name = String.valueOf(args[1]);
        try {
            for (McpSchema.Tool tool : mcpClientTool.listTools(mcp)) {
                if (!name.equals(tool.name())) {
                    continue;
                }
                McpSchema.ToolAnnotations declared = tool.annotations();
                if (declared == null) {
                    return false;
                }
                return Boolean.TRUE.equals(declared.destructiveHint())
                        || Boolean.FALSE.equals(declared.readOnlyHint());
            }
        } catch (RuntimeException unreadable) {
            // 读不到清单 ⇒ **不拒**，只记一条：一次元数据抖动不该变成一次拒绝（而今天的行为本就是放行）。
            // Unreadable list -> do **not** refuse, only warn: a metadata hiccup must not become a refusal, and
            // today's behaviour lets it through anyway.
            log.warn("[declaredWrite][读不到 MCP({}) 的工具清单，本条按放行处理]", mcp.getName(), unreadable);
        }
        return false;
    }

    private static String name(Object[] args) {
        String server = args.length > 0 && args[0] instanceof Ai1McpDO mcp ? mcp.getName() : "?";
        String tool = args.length > 1 && args[1] != null ? String.valueOf(args[1]) : "?";
        return server + ":" + tool;
    }

    /**
     * 这一轮所属的会话，按引擎自己那一列的叫法；不在任何会话里时为 {@code null}。
     *
     * <p>会话号由 {@code Ai1McpToolFactory} 在**装配处**发布（见 {@link Ai1ToolCallSession}），与调用方同一处、
     * 同一理由：工具真正被调用时那条线程既不知道调用方、也不知道会话。
     *
     * The session this turn belongs to, spelled the way the engine's own column spells it, or null when it
     * belongs to none. Published at assembly by the MCP tool factory, for the same reason the caller is.
     */
    private static String conversationId() {
        Long sessionId = Ai1ToolCallSession.current();
        return sessionId == null ? null : String.valueOf(sessionId);
    }
}
