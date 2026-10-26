package cn.iocoder.yudao.module.ai1.harness.mcp;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.ai1.dal.dataobject.agent.Ai1AgentDO;
import cn.iocoder.yudao.module.ai1.dal.dataobject.mcp.Ai1McpDO;
import cn.iocoder.yudao.module.ai1.harness.governance.Ai1GovernedOutcome;
import cn.iocoder.yudao.module.ai1.harness.governance.Ai1GovernedTool;
import cn.iocoder.yudao.module.ai1.harness.governance.Ai1ToolGovernor;
import cn.iocoder.yudao.module.ai1.harness.governance.Ai1ToolKind;
import cn.iocoder.yudao.module.ai1.service.mcp.Ai1McpService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link Ai1McpToolFactory} 的单元测试。
 *
 * <p>工具名与入参透传仍归本类管；调用本身现在**经治理器**——测试里的治理器只做一件事：执行注册时交来的那段
 * 执行体，并记下它收到的调用方与会话。于是这些测试同时钉住两件事：模型看到的工具名不变，以及调用确实**带着
 * 调用方与会话**落到了治理那一层。
 *
 * Unit tests for {@link Ai1McpToolFactory}.
 *
 * <p>Names and schema pass-through are still this class's business; the call itself now goes
 * **through the governor** — the test's governor only runs the body it was registered with and records
 * the caller and session it received. So these tests pin two things at once: the model-facing names do
 * not change, and the call really reaches governance **carrying the caller and the session**.
 */
public class Ai1McpToolFactoryTest extends BaseMockitoUnitTest {

    @InjectMocks
    private Ai1McpToolFactory mcpToolFactory;

    @Mock
    private Ai1McpService mcpService;
    @Mock
    private Ai1McpClientTool mcpClientTool;

    private final List<Ai1GovernedTool> registered = new ArrayList<>();
    private final AtomicReference<Long> callerSeenByGovernor = new AtomicReference<>();
    private final AtomicReference<Long> callerSeenInBody = new AtomicReference<>();
    private final AtomicReference<Long> sessionSeenByGovernor = new AtomicReference<>();

    private final Ai1ToolGovernor governor = new Ai1ToolGovernor() {
        @Override
        public void registerAll(List<Ai1GovernedTool> tools) {
            registered.addAll(tools);
        }

        @Override
        public Ai1GovernedOutcome execute(String name, Map<String, Object> args, Long caller, Long sessionId) {
            callerSeenByGovernor.set(caller);
            sessionSeenByGovernor.set(sessionId);
            callerSeenInBody.set(SecurityFrameworkUtils.getLoginUserId());
            Ai1GovernedTool spec = registered.stream()
                    .filter(tool -> tool.name().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("not registered: " + name));
            return new Ai1GovernedOutcome(
                    new ExecutionOutcome("executed", spec.body().apply(args), null, null, null));
        }
    };

    @Test
    public void testBuildTools_chineseNames() {
        // mock 数据
        Ai1McpDO first = buildMcp(1L, "高德地图");
        Ai1McpDO second = buildMcp(2L, "百度地图");

        // 调用
        List<ToolCallback> callbacks = buildTools(List.of(first, second), List.of("search"));
        // 断言
        assertEquals(List.of("mcp_1__search", "mcp_2__search"), callbacks.stream()
                .map(callback -> callback.getToolDefinition().name()).toList());
    }

    @Test
    public void testBuildTools_emptyNames() {
        // mock 数据
        List<Ai1McpDO> mcps = List.of(buildMcp(1L, "   "), buildMcp(2L, "___"), buildMcp(3L, "!!!"));

        // 调用
        List<ToolCallback> callbacks = buildTools(mcps, List.of("search"));
        // 断言
        assertEquals(List.of("mcp_1__search", "mcp_2__search", "mcp_3__search"), callbacks.stream()
                .map(callback -> callback.getToolDefinition().name()).toList());
    }

    @Test
    public void testBuildTools_englishName() {
        // mock 数据
        Ai1McpDO mcp = buildMcp(1L, "GitHub");

        // 调用
        List<ToolCallback> callbacks = buildTools(List.of(mcp), List.of("search"));
        // 断言
        assertEquals("github__search", callbacks.getFirst().getToolDefinition().name());
    }

    @Test
    public void testBuildTools_nameAtLimit() {
        // mock 数据
        Ai1McpDO mcp = buildMcp(1L, "a".repeat(56));

        // 调用
        List<ToolCallback> callbacks = buildTools(List.of(mcp), List.of("search"));
        // 断言
        assertEquals("a".repeat(56) + "__search", callbacks.getFirst().getToolDefinition().name());
        assertEquals(64, callbacks.getFirst().getToolDefinition().name().length());
    }

    @Test
    public void testBuildTools_longToolNames() {
        // mock 数据
        Ai1McpDO mcp = buildMcp(1L, "GitHub");
        String prefix = "search_" + "a".repeat(80);

        // 调用
        List<ToolCallback> callbacks = buildTools(List.of(mcp), List.of(prefix + "_first", prefix + "_second"));
        // 断言
        String firstName = callbacks.get(0).getToolDefinition().name();
        String secondName = callbacks.get(1).getToolDefinition().name();
        assertEquals(64, firstName.length());
        assertEquals(64, secondName.length());
        assertTrue(firstName.matches("[a-zA-Z0-9_-]+"));
        assertNotEquals(firstName, secondName);
    }

    @Test
    public void testBuildTools_longMcpNames() {
        // mock 数据
        String prefix = "a".repeat(80);
        Ai1McpDO first = buildMcp(1L, prefix + "_first");
        Ai1McpDO second = buildMcp(2L, prefix + "_second");

        // 调用
        List<ToolCallback> callbacks = buildTools(List.of(first, second), List.of("search"));
        // 断言
        String firstName = callbacks.get(0).getToolDefinition().name();
        String secondName = callbacks.get(1).getToolDefinition().name();
        assertEquals(64, firstName.length());
        assertEquals(64, secondName.length());
        assertNotEquals(firstName, secondName);
    }

    @Test
    public void testBuildTools_callOriginalTool() {
        // mock 数据
        Ai1McpDO mcp = buildMcp(1L, "高德地图");
        String toolName = "search_" + "a".repeat(80);
        Map<String, Object> arguments = Map.of("query", "北京");
        // mock mcpClientTool 的方法
        when(mcpClientTool.callTool(mcp, toolName, arguments)).thenReturn("搜索结果");
        List<ToolCallback> callbacks = buildTools(List.of(mcp), List.of(toolName));

        // 调用
        String result = callbacks.getFirst().call("{\"query\":\"北京\"}");
        // 断言
        assertEquals("搜索结果", JsonUtils.parseObject(result, String.class));
        verify(mcpClientTool).callTool(mcp, toolName, arguments);
    }

    /**
     * 只读工具与其余工具在治理上是两回事：`readOnlyHint == true` 的登记为读，其余（破坏性、或没表态）登记为写，
     * 由宿主映射到 R1/R2。
     *
     * A read-only tool and the rest are different in governance: `readOnlyHint == true` registers as a
     * read, everything else (destructive, or unstated) as a write, for the host to map to R1/R2.
     */
    @Test
    public void testBuildTools_readOnlyHintDecidesTheGovernedKind() {
        Ai1McpDO mcp = buildMcp(1L, "GitHub");
        List<McpSchema.Tool> tools = List.of(
                McpSchema.Tool.builder("search").description("read")
                        .inputSchema(Map.of("type", "object"))
                        .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).build())
                        .build(),
                McpSchema.Tool.builder("delete").description("write")
                        .inputSchema(Map.of("type", "object"))
                        .annotations(McpSchema.ToolAnnotations.builder().destructiveHint(true).build())
                        .build());
        stubAgentAndTools(List.of(mcp), tools);

        mcpToolFactory.buildTools(agentOf(List.of(mcp)), null, governor);

        assertEquals(List.of(Ai1ToolKind.MCP_READ, Ai1ToolKind.MCP_WRITE),
                registered.stream().map(Ai1GovernedTool::kind).toList());
    }

    /**
     * 调用方随工具一起走：它在**装配**时就在上下文里，而**调用**落在一条**从未有过调用方**的线程上——模型侧那条
     * 线程正是这样来的。治理器与执行体都在那个上下文里跑。
     *
     * The caller travels with the tool: it is in the context where the tools are assembled, while the
     * call runs on a thread that never had one — which is exactly what the model-side thread is. Both the
     * governor and the body run inside that context.
     */
    @Test
    public void testBuildTools_callerTravelsToTheCallThread() throws Exception {
        Ai1McpDO mcp = buildMcp(1L, "GitHub");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> { }).get();

            LoginUser user = new LoginUser();
            user.setId(7L);
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
            List<ToolCallback> callbacks = buildTools(List.of(mcp), List.of("search"));
            when(mcpClientTool.callTool(any(), anyString(), anyMap())).thenReturn("ok");

            pool.submit(() -> callbacks.getFirst().call("{}")).get();

            assertEquals(7L, callerSeenByGovernor.get());
            assertEquals(7L, callerSeenInBody.get());
        } finally {
            SecurityContextHolder.clearContext();
            pool.shutdownNow();
        }
    }

    /**
     * 会话与调用方**同一条理由、同一处**地随工具走：它在**装配**时就在作用域里，而调用落在一条**从未有过会话**
     * 的线程上——模型侧那条线程正是这样来的。没有它，一次受治理的调用写下的审计行只能被读成「这个调用方最近的
     * 调用」，而那是**另一个、也更弱**的问题（`YD-33`）。
     *
     * The session travels with the tool for the same reason and from the same place as the caller: it
     * is in scope at assembly, while the call runs on a thread that never had one. Without it, the audit
     * row a governed call writes can only be read as "this caller's recent calls", a different and weaker
     * question.
     */
    @Test
    public void testBuildTools_sessionTravelsToTheGovernor() throws Exception {
        Ai1McpDO mcp = buildMcp(1L, "GitHub");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> { }).get();
            List<ToolCallback> callbacks = buildTools(List.of(mcp), List.of("search"), 42L);
            when(mcpClientTool.callTool(any(), anyString(), anyMap())).thenReturn("ok");

            pool.submit(() -> callbacks.getFirst().call("{}")).get();

            assertEquals(42L, sessionSeenByGovernor.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private List<ToolCallback> buildTools(List<Ai1McpDO> mcps, List<String> toolNames) {
        return buildTools(mcps, toolNames, null);
    }

    private List<ToolCallback> buildTools(List<Ai1McpDO> mcps, List<String> toolNames, Long sessionId) {
        List<McpSchema.Tool> tools = toolNames.stream().map(name -> McpSchema.Tool.builder(name)
                .description("测试工具").inputSchema(Map.of("type", "object")).build()).toList();
        stubAgentAndTools(mcps, tools);
        return mcpToolFactory.buildTools(agentOf(mcps), sessionId, governor);
    }

    private void stubAgentAndTools(List<Ai1McpDO> mcps, List<McpSchema.Tool> tools) {
        when(mcpService.getMcpList(agentOf(mcps).getMcpIds())).thenReturn(mcps);
        for (Ai1McpDO mcp : mcps) {
            when(mcpClientTool.listTools(mcp)).thenReturn(tools);
        }
    }

    private static Ai1AgentDO agentOf(List<Ai1McpDO> mcps) {
        return new Ai1AgentDO().setId(1L).setMcpIds(mcps.stream().map(Ai1McpDO::getId).toList());
    }

    private static Ai1McpDO buildMcp(Long id, String name) {
        return new Ai1McpDO().setId(id).setName(name).setStatus(CommonStatusEnum.ENABLE.getStatus());
    }

}
