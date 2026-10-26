// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolParameter;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * 模型侧看到的工具就是运行时的工具：**每个工具一个回调**、名字不加前缀、schema 来自工具自己的声明，
 * 而**调用一律落到引擎**——这段代码里没有第二条通往业务表的路径，那正是它存在的理由。
 *
 * <p>没有登录调用方时，引擎收到的是本部署的「没有身份」哨兵，而不是一次「谁也没代表」的调用。
 *
 * <p>The tools the model sees are the runtime's tools: one callback each, the tool's own name
 * unprefixed, the schema taken from what the tool declares, and <b>every call lands on the engine</b> —
 * there is no second path to a business table here, which is the reason this class exists. With no
 * logged-in caller the engine receives this deployment's "no identity" sentinel rather than a call
 * made on nobody's behalf.
 */
class YudaoGovernedAgentToolsTest {

    private ToolRegistry tools;
    private GovernedExecutionEngine engine;
    private YudaoAdministrator administrators;
    private YudaoGovernedAgentTools provider;
    private AiTool tool;

    @BeforeEach
    void setUp() {
        tools = mock(ToolRegistry.class);
        engine = mock(GovernedExecutionEngine.class);
        administrators = mock(YudaoAdministrator.class);
        provider = new YudaoGovernedAgentTools(tools, engine, administrators);

        tool = mock(AiTool.class);
        when(tool.name()).thenReturn("list_customers");
        when(tool.description()).thenReturn("List the caller's customers");
        when(tool.parameters()).thenReturn(List.of(
                ToolParameter.required("keyword", ToolParameter.STRING, "搜索词")));
        // The deployment catalogue, not every registered tool: host-assembled MCP/SKILL tools belong
        // to one agent's binding and must not be handed to every agent here.
        //
        // 部署自己的目录，而不是全部注册工具：宿主装配的 MCP/SKILL 工具属于某一个 agent 的绑定，不能在这里发给
        // 所有 agent。
        when(tools.catalogue()).thenReturn(List.of(tool));
    }

    @Test
    void everyRuntimeToolBecomesOneCallbackUnderItsOwnName() {
        List<ToolCallback> callbacks = provider.buildTools(null, 7L);

        assertEquals(1, callbacks.size());
        assertEquals("list_customers", callbacks.get(0).getToolDefinition().name(),
                "the name is the tool's own — one vocabulary across conversation, inventory and audit");
        assertEquals("List the caller's customers", callbacks.get(0).getToolDefinition().description());
    }

    @Test
    void theSchemaComesFromWhatTheToolDeclares() {
        List<ToolCallback> callbacks = provider.buildTools(null, 7L);

        String schema = callbacks.get(0).getToolDefinition().inputSchema();
        assertTrue(schema.contains("\"keyword\""), schema);
        assertTrue(schema.contains("\"required\""), schema);
    }

    @Test
    void aToolWithNoDeclarationsGetsAnEmptySchema() {
        when(tool.parameters()).thenReturn(List.of());

        String schema = provider.buildTools(null, 7L).get(0).getToolDefinition().inputSchema();

        assertTrue(schema.contains("\"properties\":{}") || schema.contains("\"properties\": {}"), schema);
        assertTrue(!schema.contains("\"required\""), "nothing declared means nothing required: " + schema);
    }

    @Test
    void aCallGoesToTheEngineWithTheToolsOwnName() {
        when(engine.execute(any(), any(), any(), any())).thenReturn(mock(ExecutionOutcome.class));

        String out = provider.buildTools(null, 7L).get(0).call("{\"keyword\":\"临海\"}");

        assertNotNull(out);
        verify(engine).execute(eq("list_customers"), eq(java.util.Map.of("keyword", "临海")),
                argThat(principal -> "0".equals(principal.userId())), eq("7"));
    }
}
