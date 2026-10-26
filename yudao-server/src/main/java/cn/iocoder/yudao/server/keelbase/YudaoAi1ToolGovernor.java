// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.governance.GovernancePolicyStore;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import cn.iocoder.yudao.module.ai1.harness.governance.Ai1GovernedOutcome;
import cn.iocoder.yudao.module.ai1.harness.governance.Ai1GovernedTool;
import cn.iocoder.yudao.module.ai1.harness.governance.Ai1ToolGovernor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 宿主对 ai1 治理缝的实现：把外部工具注册成宿主装配的工具，并让每次调用落进引擎。
 *
 * <p><b>只读与写。</b>只读工具（MCP `readOnlyHint`、Glob/Grep/ListDirectory、RAG 检索）声明 R1，调用即执行并
 * 留下审计行；其余声明 R2，并在**注册时**以 `enabled:false` 落一条策略——默认被引擎挡住，管理员在策略中心
 * 逐个放开。这不是「确认」：外部工具没有之后能执行确认 token 的人，发一个 token 只是没人能兑现的承诺。
 *
 * <p><b>为什么它们必须留在注册表里。</b>引擎按名字取工具，策略库按名字校验，清单按注册表列——而一个 R2 工具
 * 被放开之后要能被执行。故注册是**持久的**（进程生命周期内），不是「这一轮用完就扔」。
 *
 * <p><b>它不是一个交工具给 Spring AI 的 bean。</b>它交给引擎，工具给模型的那条路仍是 `Ai1McpToolFactory` /
 * `Ai1SkillToolFactory` 造的回调——`D42` 拒的是「声明返回类型就是 `ToolCallback` 的 bean 方法」。
 *
 * The host's implementation of ai1's governance seam: register external tools as host-assembled tools,
 * and make every call land in the engine.
 *
 * <p><b>Reads and writes.</b> A read (MCP `readOnlyHint`, Glob/Grep/ListDirectory, RAG retrieval)
 * declares R1 and runs immediately with an audit row; everything else declares R2 and, **at
 * registration**, gets an `enabled:false` policy entry — blocked by default, for an administrator to
 * enable one by one in the Policy Center. This is not a confirmation: an external tool has no later
 * executor for a token, so a token would be a promise nobody can keep.
 *
 * <p><b>Why they stay registered.</b> The engine resolves a tool by name, the policy store validates by
 * name, the inventory lists the registry — and an R2 tool must be executable once someone enables it.
 * Registration is therefore **durable** for the process lifetime, not scoped to one turn.
 *
 * <p><b>It is not a bean that hands tools to Spring AI.</b> It hands them to the engine; the path to
 * the model is still the callbacks `Ai1McpToolFactory` / `Ai1SkillToolFactory` build — `D42` refuses a
 * bean method whose declared return type is `ToolCallback`.
 */
@Component
public class YudaoAi1ToolGovernor implements Ai1ToolGovernor {

    /** The same "no identity" sentinel the local tool face uses. / 与本地工具面同一个「没有身份」哨兵。 */
    private static final String NO_IDENTITY = "0";
    private static final String READ = "R1";
    private static final String WRITE = "R2";

    private final ToolRegistry tools;
    private final GovernedExecutionEngine engine;
    private final GovernancePolicyStore policy;
    private final YudaoAdministrator administrators;

    public YudaoAi1ToolGovernor(ToolRegistry tools, GovernedExecutionEngine engine,
                                GovernancePolicyStore policy, YudaoAdministrator administrators) {
        this.tools = tools;
        this.engine = engine;
        this.policy = policy;
        this.administrators = administrators;
    }

    @Override
    public synchronized void registerAll(List<Ai1GovernedTool> governed) {
        if (governed == null || governed.isEmpty()) {
            return;
        }
        List<String> writes = new ArrayList<>();
        for (Ai1GovernedTool tool : governed) {
            if (!tools.contains(tool.name())) {
                tools.register(new Ai1ExternalToolAdapter(tool, tool.kind().readsOnly() ? READ : WRITE),
                        ToolRegistry.Origin.HOST_ASSEMBLED);
            }
            // A tool the deployment already owns was refused registration; it is the deployment's and
            // the deployment's gate applies, so there is no external default to add.
            //
            // 部署已经拥有的工具会被拒绝注册；它归部署、按部署的闸走，故不需要为它补外部默认值。
            if (!tool.kind().readsOnly() && tools.contains(tool.name())) {
                writes.add(tool.name());
            }
        }
        if (!writes.isEmpty()) {
            policy.ensureDisabled(writes);
        }
    }

    @Override
    public Ai1GovernedOutcome execute(String name, Map<String, Object> args, Long caller, Long sessionId) {
        ExecutionOutcome outcome = engine.execute(name, args == null ? Map.of() : args, principal(caller),
                sessionId == null ? null : String.valueOf(sessionId));
        return new Ai1GovernedOutcome(outcome);
    }

    /**
     * Who the engine should treat as acting — the host's directory decides whether the caller is an
     * administrator, never the caller.
     *
     * 引擎该把谁当成行动者——是不是管理员由**宿主的名录**回答，从不由调用方自述。
     */
    private Principal principal(Long caller) {
        if (caller == null) {
            return new Principal(NO_IDENTITY, Principal.ROLE_USER);
        }
        return new Principal(String.valueOf(caller),
                administrators.isAdmin(caller) ? Principal.ROLE_ADMIN : Principal.ROLE_USER);
    }
}
