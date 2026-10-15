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

import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;

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
     * 构建 Agent 绑定的 MCP 工具集合；单个服务失败时跳过，不影响会话
     *
     * tools/list 结果已由 {@link Ai1McpClientTool} 按配置指纹缓存，这里只做 ToolCallback 包装
     *
     * @param agent Agent
     * @return 工具集合
     */
    public List<ToolCallback> buildTools(Ai1AgentDO agent) {
        return buildTools(agent, null);
    }

    /**
     * 同上，另带**这一轮属于哪条会话**。
     *
     * <p><b>这一处是 KeelBase 的改动，登记在 {@code keelbase-docs/KEELBASE-UPSTREAM.md}。</b>它与上面那个不带
     * 会话的重载**并存**、不替换：上游自己的调用方与测试照旧走那个，而部署会把会话传进来。会话号**在装配处就在
     * 作用域里**（正是知道会话的那次生成），而工具真正被调用时那条线程不知道它 —— 与「调用方」是同一条理由。
     *
     * <p>它在调用处被发布（见 {@link Ai1ToolCallSession}），于是**受治理的那次调用写下的行说得清自己属于哪一轮**；
     * 没有它，一行能被读到的最好程度只是「这个调用方最近的调用」。
     *
     * The same, carrying **which session this turn belongs to**.
     *
     * <p><b>This is KeelBase's change here, registered in {@code keelbase-docs/KEELBASE-UPSTREAM.md}.</b> It sits
     * beside the session-less overload rather than replacing it: upstream's own callers and tests keep using that
     * one, and a deployment passes the session in. The id is in scope where the tools are assembled — the
     * generation that knows the session — while the thread that calls the tool does not know it, the same reason
     * the caller travels the same way.
     *
     * <p>Published at the call site (see {@link Ai1ToolCallSession}), so a governed call's row can say which turn
     * it came from; without it the most a reader can do is "this caller's recent calls".
     *
     * @param agent     Agent
     * @param sessionId 这一轮的会话号；不属于任何会话时传 {@code null}
     * @return 工具集合
     */
    public List<ToolCallback> buildTools(Ai1AgentDO agent, Long sessionId) {
        List<ToolCallback> tools = new ArrayList<>();
        if (CollUtil.isEmpty(agent.getMcpIds())) {
            return tools;
        }
        for (Ai1McpDO mcp : mcpService.getMcpList(agent.getMcpIds())) {
            if (CommonStatusEnum.isDisable(mcp.getStatus())) {
                continue;
            }
            tools.addAll(convertList(listTools(agent, mcp), tool -> buildToolCallback(mcp, tool, sessionId)));
        }
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

    /**
     * 构建单个工具回调：名称去冲突 + 入参 Schema 透传 + MCP 客户端调用落地
     *
     * <p>The caller is captured <b>here</b> and not where the call runs, because those are not the same
     * thread: assembly happens inside the generation that knows who asked, while the model drives the
     * tool loop on a renderer thread that never had a caller. Without this the governed call asks for
     * an identity on a thread nothing ever put one on, and is refused — which used to happen or not
     * depending on whether that pool thread happened to be created inside the caller's window.
     *
     * 调用方在**这里**捕获，而不是在调用真正发生的地方——两者不是同一条线程：装配发生在**知道是谁在问**的那次
     * 生成里，而模型驱动工具循环用的是一条**从未有过调用方**的线程。没有这一段，那次受治理的调用就在一条没人
     * 放过身份的线程上问身份，因而被拒——而它拒或不拒，过去取决于那条池线程**是否碰巧**在调用方的窗口里被创建。
     */
    private ToolCallback buildToolCallback(Ai1McpDO mcp, McpSchema.Tool tool, Long sessionId) {
        Long caller = SecurityFrameworkUtils.getLoginUserId();
        // 会话与调用方**同一处、同一理由**地随工具走：装配时它在作用域里，而调用落在的那条线程不知道它。
        Function<Map<String, Object>, String> function = arguments ->
                withCaller(caller, () -> Ai1ToolCallSession.as(sessionId,
                        () -> mcpClientTool.callTool(mcp, tool.name(), arguments)));
        Map<String, Object> inputSchema = tool.inputSchema();
        if (MapUtil.isEmpty(inputSchema)) {
            inputSchema = new HashMap<>();
            inputSchema.put("type", "object");
            inputSchema.put("properties", new HashMap<>());
        }
        return FunctionToolCallback.builder(buildToolName(mcp, tool), function)
                .description(StrUtil.nullToEmpty(tool.description()))
                .inputType(Map.class)
                .inputSchema(JsonUtils.toJsonString(inputSchema))
                .build();
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
