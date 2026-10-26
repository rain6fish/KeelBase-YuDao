// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.module.ai1.harness.governance;

/**
 * 一个 ai1 工具在治理上的性质：是读还是写。
 *
 * <p>它不是工具自己声明的风险级——外部工具（MCP、SKILL）没有那种声明。这是本部署按**来源**给出的、
 * 保守的判断：只读的 MCP 工具与只读的 SKILL 工具走 R1；其余（破坏性/未知的 MCP、shell/文件/Skill、
 * 文件读取）走 R2，并以「默认禁用」起步，管理员在策略中心逐个放开。
 *
 * What an ai1 tool is, in governance terms: a read or a write.
 *
 * <p>It is not a risk level the tool declares — external tools (MCP, SKILL) have no such declaration.
 * It is this deployment's conservative judgement **by origin**: read-only MCP tools and read-only SKILL
 * tools go to R1; everything else (destructive/unknown MCP, shell/file/Skill, file reads) goes to R2
 * and starts **disabled**, for an administrator to enable one by one in the Policy Center.
 */
public enum Ai1ToolKind {

    MCP_READ,
    MCP_WRITE,
    SKILL_READ,
    SKILL_WRITE,
    RAG_READ;

    /** Whether the call is a read, and may run automatically. / 这次调用是不是读、可以自动放行。 */
    public boolean readsOnly() {
        return this == MCP_READ || this == SKILL_READ || this == RAG_READ;
    }

}
