// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolResult;
import cn.iocoder.yudao.module.ai1.harness.governance.Ai1GovernedTool;
import java.util.List;
import java.util.Map;

/**
 * 把一个 ai1 外部工具（MCP / SKILL / RAG）交给引擎的 `AiTool` 外壳。
 *
 * <p>它只做三件事：报出工具的名字与描述、报出这台部署给它的风险级、在引擎调用时执行那段执行体。**没有
 * `resultType`**：外部工具没有可登记的结果 id，故引擎不会为它记副作用台账——这是本阶段的诚实边界，而不是遗漏。
 * 风险级由宿主按**来源**给（只读 R1、其余 R2），工具自己申报不了。
 *
 * <p>执行体抛出的运行时异常被转成 `ToolResult.fail`，于是引擎记下 `failed` 并与其它失败同形，而不是让一次
 * 已经发生的外部调用以异常形式穿出去、留下一个没有审计的洞。
 *
 * The `AiTool` shell that hands an ai1 external tool (MCP / SKILL / RAG) to the engine.
 *
 * <p>It does three things: report the name and description, report the risk level this deployment gives
 * it, and run the body when the engine calls it. **No `resultType`**: an external tool has no result id
 * to record, so the engine keeps no side-effect ledger row for it — the honest boundary of this phase,
 * not an omission. The risk level comes from the host **by origin** (reads R1, the rest R2); the tool
 * itself cannot declare one.
 *
 * <p>A runtime exception from the body becomes `ToolResult.fail`, so the engine records `failed` in the
 * same shape as any other failure instead of letting an external call that already happened escape as
 * an exception with no audit row behind it.
 */
public class Ai1ExternalToolAdapter implements AiTool {

    private final Ai1GovernedTool spec;
    private final String riskLevel;

    public Ai1ExternalToolAdapter(Ai1GovernedTool spec, String riskLevel) {
        this.spec = spec;
        this.riskLevel = riskLevel;
    }

    @Override
    public String name() {
        return spec.name();
    }

    @Override
    public String description() {
        return spec.description();
    }

    @Override
    public String riskLevel() {
        return riskLevel;
    }

    @Override
    public List<cn.com.keelbase.runtime.tool.ToolParameter> parameters() {
        // External tools carry their own JSON schema to the model; this runtime cannot translate it into
        // the declared-argument shape without guessing, so it declares nothing and the engine's
        // argument check stays out of the way. Declaring nothing is the truthful reading of "we did not
        // translate it", not a claim that the tool takes no arguments.
        //
        // 外部工具把 JSON schema 直接交给模型；运行时没有可靠办法把它翻成声明的入参形状，故这里**什么都不声明**，
        // 引擎的入参校验也就不介入。什么都不声明是「我们没有翻译它」的诚实说法，不是「这个工具不收参数」的主张。
        return List.of();
    }

    @Override
    public String resultType() {
        return null;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, Principal principal) {
        try {
            return ToolResult.ok(spec.body().apply(arguments == null ? Map.of() : arguments));
        } catch (RuntimeException e) {
            return ToolResult.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
