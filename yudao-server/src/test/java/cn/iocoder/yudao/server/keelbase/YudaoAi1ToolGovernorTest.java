// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.governance.GovernancePolicyStore;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import cn.iocoder.yudao.module.ai1.harness.governance.Ai1GovernedTool;
import cn.iocoder.yudao.module.ai1.harness.governance.Ai1ToolKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 宿主治理缝：注册成宿主装配的工具，写默认禁用，执行落到引擎。
 *
 * <p>三条断言各挡一种假绿：只读工具不进部署目录（否则每个 agent 都会拿到别人的工具）、写工具注册时被默认禁用
 * （否则「默认拦住」只是一句话）、执行带着调用方与会话落到引擎（否则审计行归不了这一轮）。
 *
 * The host seam: register as host-assembled tools, disable writes by default, execute in the engine.
 *
 * <p>Three assertions, each closing a different way of passing vacuously: a read does not enter the
 * deployment catalogue (or every agent gets everyone's tools), a write is disabled at registration (or
 * "blocked by default" is just a sentence), and execution reaches the engine with the caller and session
 * (or the audit row cannot name this turn).
 */
class YudaoAi1ToolGovernorTest {

    private final ToolRegistry tools = new ToolRegistry(List.of());
    private final GovernedExecutionEngine engine = mock(GovernedExecutionEngine.class);
    private final GovernancePolicyStore policy = mock(GovernancePolicyStore.class);
    private final YudaoAdministrator administrators = mock(YudaoAdministrator.class);

    private final YudaoAi1ToolGovernor governor =
            new YudaoAi1ToolGovernor(tools, engine, policy, administrators);

    @Test
    void readsAreRegisteredButNotInTheDeploymentCatalogue() {
        governor.registerAll(List.of(tool("mcp__server__search", Ai1ToolKind.MCP_READ)));

        assertEquals(List.of("mcp__server__search"), tools.all().stream().map(AiTool::name).toList());
        assertTrue(tools.catalogue().isEmpty(),
                "a host-assembled tool must not be offered to every agent as a deployment tool");
    }

    @Test
    void writesAreRegisteredDisabledByDefault() {
        governor.registerAll(List.of(tool("mcp__server__delete", Ai1ToolKind.MCP_WRITE)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(policy).ensureDisabled(captor.capture());
        assertEquals(List.of("mcp__server__delete"), captor.getValue());
    }

    @Test
    void executionReachesTheEngineWithCallerAndSession() {
        when(administrators.isAdmin(7L)).thenReturn(false);
        when(engine.execute(eq("mcp__server__search"), eq(Map.of()), any(Principal.class), eq("42")))
                .thenReturn(new ExecutionOutcome("executed", "ok", null, null, null));

        var outcome = governor.execute("mcp__server__search", Map.of(), 7L, 42L);

        assertEquals("ok", outcome.forModel());
        ArgumentCaptor<Principal> principal = ArgumentCaptor.forClass(Principal.class);
        verify(engine).execute(eq("mcp__server__search"), eq(Map.of()), principal.capture(), eq("42"));
        assertEquals("7", principal.getValue().userId());
        assertEquals(Principal.ROLE_USER, principal.getValue().role());
    }

    @Test
    void anAdministratorKeepsTheAdministratorRole() {
        when(administrators.isAdmin(7L)).thenReturn(true);
        when(engine.execute(any(), any(), any(Principal.class), any()))
                .thenReturn(new ExecutionOutcome("blocked", Map.of(), null, null, "blocked"));

        governor.execute("mcp__server__delete", Map.of(), 7L, null);

        ArgumentCaptor<Principal> principal = ArgumentCaptor.forClass(Principal.class);
        verify(engine).execute(any(), any(), principal.capture(), any());
        assertEquals(Principal.ROLE_ADMIN, principal.getValue().role());
    }

    private static Ai1GovernedTool tool(String name, Ai1ToolKind kind) {
        return new Ai1GovernedTool(name, "test tool", kind, null, arguments -> "ok");
    }
}
