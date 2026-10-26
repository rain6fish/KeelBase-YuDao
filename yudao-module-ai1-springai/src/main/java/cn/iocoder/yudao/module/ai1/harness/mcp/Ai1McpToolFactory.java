package cn.iocoder.yudao.module.ai1.harness.mcp;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.ai1.dal.dataobject.agent.Ai1AgentDO;
import cn.iocoder.yudao.module.ai1.dal.dataobject.mcp.Ai1McpDO;
import cn.iocoder.yudao.module.ai1.harness.governance.Ai1GovernedTool;
import cn.iocoder.yudao.module.ai1.harness.governance.Ai1ToolGovernor;
import cn.iocoder.yudao.module.ai1.harness.governance.Ai1ToolKind;
import cn.iocoder.yudao.module.ai1.service.mcp.Ai1McpService;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * AI1 MCP 工具工厂：把 Agent 绑定的 MCP 服务暴露的工具，桥接为 Spring AI ToolCallback
 *
 * 1. 工具名：MCP 名称净化 + "__" + 原始工具名；无有效名称时使用 MCP 编号，超长时保留摘要
 * 2. 入参：透传 MCP 工具的 JSON Schema，调用落地仍走 {@link Ai1McpClientTool}
 *
 * 工具挂到 ChatClient 后，由 Spring AI 的工具调用机制统一驱动；与 {@link Ai1McpClientTool} 同包
 *
 * @author 芋道源码
 */
@Component
@Slf4j
public class Ai1McpToolFactory {

    /**
     * MCP 名称与工具名称的分隔符
     */
    private static final String NAME_SEPARATOR = "__";

    @Resource
    private Ai1McpService mcpService;

    @Resource
    private Ai1McpClientTool mcpClientTool;

    /**
     * 构建 Agent 绑定的 MCP 工具集合，并让它们**经治理引擎**执行。
     *
     * <p>模型看到的仍是 MCP 服务自己的工具名、描述与入参 schema；调用先经过宿主注册的治理工具：`readOnlyHint == true`
     * 的走 R1，其余（破坏性、或没表态的）走 R2 且**默认禁用**，管理员在策略中心放开。审计行由引擎写，
     * 带着调用方与这一轮的会话号。
     *
     * <p>没有治理器时**明确失败**：悄悄退回一条不经引擎的路，正是这次改动要消灭的形态。
     *
     * Build the MCP tools this agent binds, and run them **through the governance engine**.
     *
     * <p>The model still sees the MCP server's own name, description and input schema; the call first
     * reaches the host's governed tool — `readOnlyHint == true` at R1, everything else (destructive, or
     * unstated) at R2 and **disabled by default** until an administrator enables it in the Policy
     * Center. The engine writes the audit row, carrying the caller and this turn's session id.
     *
     * <p>No governor is a **loud failure**: quietly falling back to a path that bypasses the engine is
     * the shape this change removes.
     *
     * @param agent     Agent
     * @param sessionId 这一轮的会话号；不属于任何会话时传 {@code null}
     * @param governor  宿主交给 ai1 的治理缝
     * @return 工具集合
     */
    public List<ToolCallback> buildTools(Ai1AgentDO agent, Long sessionId, Ai1ToolGovernor governor) {
        List<ToolCallback> tools = new ArrayList<>();
        if (CollUtil.isEmpty(agent.getMcpIds())) {
            return tools;
        }
        if (governor == null) {
            throw new IllegalStateException("no Ai1ToolGovernor — MCP tools must run through governance");
        }
        Long caller = SecurityFrameworkUtils.getLoginUserId();
        List<Ai1GovernedTool> specs = new ArrayList<>();
        for (Ai1McpDO mcp : mcpService.getMcpList(agent.getMcpIds())) {
            if (CommonStatusEnum.isDisable(mcp.getStatus())) {
                continue;
            }
            for (McpSchema.Tool tool : listTools(agent, mcp)) {
                String name = buildToolName(mcp, tool);
                String description = StrUtil.nullToEmpty(tool.description());
                String schemaJson = JsonUtils.toJsonString(schemaOf(tool));
                boolean readOnly = tool.annotations() != null
                        && Boolean.TRUE.equals(tool.annotations().readOnlyHint());
                specs.add(new Ai1GovernedTool(name, description,
                        readOnly ? Ai1ToolKind.MCP_READ : Ai1ToolKind.MCP_WRITE, schemaJson,
                        arguments -> mcpClientTool.callTool(mcp, tool.name(), arguments)));
                tools.add(FunctionToolCallback.builder(name,
                                (Function<Map<String, Object>, String>) arguments -> withCaller(caller,
                                        () -> governor.execute(name, arguments, caller, sessionId).forModel()))
                        .description(description)
                        .inputType(Map.class)
                        .inputSchema(schemaJson)
                        .build());
            }
        }
        governor.registerAll(specs);
        return tools;
    }

    /**
     * 列举 MCP 服务暴露的工具；失败时记录日志并返回空列表
     *
     * @param agent Agent
     * @param mcp   MCP 服务
     * @return 工具列表
     */
    private List<McpSchema.Tool> listTools(Ai1AgentDO agent, Ai1McpDO mcp) {
        try {
            return mcpClientTool.listTools(mcp);
        } catch (Exception e) {
            log.warn("[listTools][Agent({}) 加载 MCP({}) 工具失败]", agent.getId(), mcp.getName(), e);
            return Collections.emptyList();
        }
    }

    /** The tool's input schema, normalised to an empty object schema when it declares none. */
    /** 工具声明的入参 schema；没声明时归一成一个空对象 schema。 */
    private static Map<String, Object> schemaOf(McpSchema.Tool tool) {
        Map<String, Object> inputSchema = tool.inputSchema();
        if (MapUtil.isEmpty(inputSchema)) {
            inputSchema = new HashMap<>();
            inputSchema.put("type", "object");
            inputSchema.put("properties", new HashMap<>());
        }
        return inputSchema;
    }

    /**
     * Runs the body with the submitting caller in the security context, so that anything below this
     * point asking "who is acting" gets the answer the generation thread would have given — and gets it
     * every time, rather than only when a pool thread happened to inherit it once.
     *
     * <p>The context is a thread local and this is not the thread that set it: it is set for the body
     * and cleared afterwards, so one caller cannot leak into the next call this thread runs.
     *
     * 让 body 在**投递它的那个调用方**的安全上下文里运行，好让这以下任何问「谁在行动」的东西得到**生成线程**
     * 会给的那个答案——而且是**每次都得到**，而不是只在某条池线程碰巧继承过一次的时候。
     *
     * <p>上下文是线程局部的，而这不是设它的那条线程：它在 body 期间被设置、之后被清掉，故一个调用方**不会**
     * 漏进这条线程接下来跑的下一次调用。
     */
    private static String withCaller(Long userId, Supplier<String> body) {
        if (userId == null) {
            return body.get();
        }
        LoginUser caller = new LoginUser();
        caller.setId(userId);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(caller, null, List.of()));
        try {
            return body.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 构建工具名：无有效 MCP 名称时使用编号；超过 64 字符时截短并追加摘要，避免截断后重名
     *
     * @param mcp  MCP 服务
     * @param tool MCP 工具
     * @return 工具名
     */
    private static String buildToolName(Ai1McpDO mcp, McpSchema.Tool tool) {
        String prefix = slugify(mcp.getName());
        if (StrUtil.isEmpty(prefix)) {
            prefix = "mcp_" + mcp.getId();
        }
        String name = prefix + NAME_SEPARATOR + tool.name();
        // OpenAI 兼容接口的工具名最多 64 字符，保留 47 字符前缀 + "_" + 16 位摘要。
        // 摘要由 MCP 编号和原始工具名生成，避免不同服务或工具截断后重名。
        if (name.length() > 64) {
            String suffix = DigestUtil.sha256Hex(mcp.getId() + ":" + tool.name()).substring(0, 16);
            name = name.substring(0, 47) + "_" + suffix;
        }
        return name;
    }

    /**
     * 名称净化：只保留小写字母、数字、下划线（工具名需匹配 ^[a-zA-Z0-9_-]+$）
     */
    static String slugify(String name) {
        if (StrUtil.isBlank(name)) {
            return "";
        }
        String slug = ReUtil.replaceAll(name.toLowerCase(Locale.ROOT), "[^a-z0-9_]+", "_");
        slug = ReUtil.replaceAll(slug, "_+", "_");
        return ReUtil.replaceAll(slug, "^_+|_+$", "");
    }

}
