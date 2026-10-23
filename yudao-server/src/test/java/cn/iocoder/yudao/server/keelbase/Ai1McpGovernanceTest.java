// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.com.keelbase.runtime.audit.AuditService;
import cn.iocoder.yudao.module.ai1.dal.dataobject.mcp.Ai1McpDO;
import cn.iocoder.yudao.module.ai1.harness.mcp.Ai1McpClientTool;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 宿主自带 AI 路径上的那条 MCP 闸：**没有身份即拒**、**明确声明是写即拒**、成败**都写审计**。
 *
 * <p>三条判据的次序也是内容的一部分：身份先于「是不是写」，故一个既无身份又声明为写的调用，审计里写的是
 * **无身份**那一条。工具清单读不出来时**不拒**——一次元数据抖动不该变成一次拒绝。
 *
 * <p>The MCP gate on the host's own AI path: no identity is refused, an explicitly declared write is
 * refused, and both outcomes are audited. The order is part of the contract — identity comes before
 * "is it a write", so a call that is both is audited as <b>no identity</b>. An unreadable tool list
 * does <b>not</b> refuse: a metadata hiccup must not become a refusal.
 */
class Ai1McpGovernanceTest {

    private static final long CALLER = 42L;
    private static final String SERVER = "crm-mcp";
    private static final String TOOL = "review_voucher";

    private YudaoCallerId callers;
    private AuditService audit;
    private Ai1McpClientTool mcp;
    private Ai1McpGovernance aspect;
    private ProceedingJoinPoint call;
    private Ai1McpDO server;

    @BeforeEach
    void setUp() {
        callers = mock(YudaoCallerId.class);
        audit = mock(AuditService.class);
        mcp = mock(Ai1McpClientTool.class);
        aspect = new Ai1McpGovernance(callers, audit, mcp);
        ReflectionTestUtils.setField(aspect, "writePolicy", "refuse");

        call = mock(ProceedingJoinPoint.class);
        server = new Ai1McpDO();
        server.setName(SERVER);
        when(call.getArgs()).thenReturn(new Object[]{server, TOOL});
    }

    @Test
    void aCallWithNoIdentityIsRefusedAndAudited() throws Throwable {
        when(callers.current()).thenReturn(null);

        assertThrows(AccessDeniedException.class, () -> aspect.govern(call));

        verify(call, never()).proceed();
        verify(audit).append(eq("tool_call"), eq("0"), anyString(), eq(true), eq("refused"),
                eq(SERVER + ":" + TOOL), any());
    }

    @Test
    void theZeroIdentityIsTreatedAsNoIdentity() {
        when(callers.current()).thenReturn(0L);

        assertThrows(AccessDeniedException.class, () -> aspect.govern(call));
    }

    @Test
    void aDeclaredWriteIsRefusedUnderTheDefaultPolicy() throws Throwable {
        when(callers.current()).thenReturn(CALLER);
        when(mcp.listTools(server)).thenReturn(List.of(toolWith(
                McpSchema.ToolAnnotations.builder().destructiveHint(true).build())));

        assertThrows(AccessDeniedException.class, () -> aspect.govern(call));

        verify(call, never()).proceed();
        verify(audit).append(eq("tool_call"), eq("42"), anyString(), eq(true), eq("refused"),
                eq(SERVER + ":" + TOOL), any());
    }

    @Test
    void aReadOnlyToolProceedsAndIsAuditedAsExecuted() throws Throwable {
        when(callers.current()).thenReturn(CALLER);
        when(mcp.listTools(server)).thenReturn(List.of(toolWith(
                McpSchema.ToolAnnotations.builder().readOnlyHint(true).build())));
        when(call.proceed()).thenReturn("ok");

        assertEquals("ok", aspect.govern(call));

        verify(audit).append(eq("tool_call"), eq("42"), anyString(), eq(false), eq("executed"),
                eq(SERVER + ":" + TOOL), any());
    }

    @Test
    void aToolThatDeclaresNothingIsNotTreatedAsAWrite() throws Throwable {
        when(callers.current()).thenReturn(CALLER);
        when(mcp.listTools(server)).thenReturn(List.of(toolWith(null)));
        when(call.proceed()).thenReturn("ok");

        assertEquals("ok", aspect.govern(call));
    }

    @Test
    void anUnreadableToolListDoesNotRefuse() throws Throwable {
        when(callers.current()).thenReturn(CALLER);
        when(mcp.listTools(server)).thenThrow(new IllegalStateException("metadata hiccup"));
        when(call.proceed()).thenReturn("ok");

        assertEquals("ok", aspect.govern(call), "a hiccup in the metadata must not become a refusal");
    }

    @Test
    void aFailureIsAuditedThenRethrown() throws Throwable {
        when(callers.current()).thenReturn(CALLER);
        when(mcp.listTools(server)).thenReturn(List.of());
        when(call.proceed()).thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, () -> aspect.govern(call));

        verify(audit).append(eq("tool_call"), eq("42"), eq(SERVER + ":" + TOOL + " failed: IllegalStateException"),
                eq(true), eq("failed"), eq(SERVER + ":" + TOOL), any());
    }

    @Test
    void theAllowPolicyRestoresTheOldBehaviour() throws Throwable {
        ReflectionTestUtils.setField(aspect, "writePolicy", "allow");
        when(callers.current()).thenReturn(CALLER);
        when(mcp.listTools(server)).thenReturn(List.of(toolWith(
                McpSchema.ToolAnnotations.builder().destructiveHint(true).build())));
        when(call.proceed()).thenReturn("ok");

        assertEquals("ok", aspect.govern(call));
    }

    private static McpSchema.Tool toolWith(McpSchema.ToolAnnotations annotations) {
        return McpSchema.Tool.builder().name(TOOL).annotations(annotations).build();
    }
}
