// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolParameter;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.ai1.dal.dataobject.agent.Ai1AgentDO;
import cn.iocoder.yudao.module.ai1.harness.local.Ai1LocalToolProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

/**
 * The governed tool face, handed to an agent as the tools it can call — so the assistant reaches the
 * same tools, through the same engine, as everyone else here.
 *
 * <p><b>What this replaces, and why that is the point.</b> The agent module's model-callable tools
 * used to come from two places, an MCP server and a SKILL, and neither had an identity, a gate or an
 * audit on it. This is the third place, and it is the deployment's own: every tool the runtime holds,
 * executed through {@link GovernedExecutionEngine}, which is the one path that decides risk, asks a
 * human where a human is needed, records the side effect and writes the audit row. Nothing here
 * decides any of that — it builds callbacks and lets the engine answer.
 *
 * <p><b>Not a {@code @Bean} of tools, and that is deliberate.</b> A bean whose declared type hands
 * tools to Spring AI would make them reachable by a model through Spring AI's own resolver, with no
 * engine behind them — the shape {@code D42}'s check refuses. This is a factory instead, the same
 * shape as {@code Ai1McpToolFactory}: it is called while a session is assembled, and what it returns
 * is handed to one turn.
 *
 * <p><b>The caller is captured while the session is assembled, not where the call runs.</b> Assembly
 * happens inside the generation that knows who asked; the model drives the tool loop on a thread that
 * may never have had a caller. The tool's own identity arrives as the {@link Principal} passed to the
 * engine, and every host-facing tool here publishes it for its host calls (see {@code ActingCaller}),
 * so the row scope downstream answers for the person who asked rather than for nobody.
 *
 * <p><b>A write does not run from here.</b> An R3 tool comes back as {@code pending_confirmation} with
 * a token — the engine's answer, not this class's — and the model is told so. Somebody approves it in
 * the console, which is where a person is looking; the model only carries the news.
 *
 * 受治理的工具面，作为一个 agent 能调的工具交给它——于是助手到达的是**与这里所有人同一个**工具、经**同一个引擎**。
 *
 * <p><b>它替换掉的是什么，以及那正是要点。</b>agent 模块「模型可调的工具」过去来自两个地方——一台 MCP 服务与
 * 一个 SKILL——而两者之上都没有身份、没有闸、没有审计。这里是**第三个**地方，而且它是**部署方自己的**：运行时持有的
 * 每一个工具，经 {@link GovernedExecutionEngine} 执行——那是唯一一条会判风险、在该请人时请人、登记副作用、写下审计行
 * 的路。这里**什么都不判**：它造回调，让引擎回答。
 *
 * <p><b>不是一个交工具的 `@Bean`，而这是刻意的。</b>声明类型就把工具交给 Spring AI 的 bean，会让它们经 Spring AI
 * **自己的解析器**被模型够到、背后没有引擎——正是 `D42` 那道检查所拒的形状。这里是一个**工厂**，与
 * `Ai1McpToolFactory` 同一形状：它在**装配一次会话时**被调用，交回的东西交给**某一轮**。
 *
 * <p><b>调用方在装配会话时被捕获，而不是在调用发生的地方。</b>装配发生在**知道是谁在问**的那次生成里；而模型驱动的
 * 工具循环跑在一条**可能从未有过调用方**的线程上。工具自己的身份以交给引擎的 {@link Principal} 到达，而这里每一个面向
 * 宿主的工具都会为它的宿主调用把它发布出去（见 `ActingCaller`），故下游的行范围回答的是**提问的那个人**、而不是没人。
 *
 * <p><b>写操作不从这里跑。</b>一个 R3 工具会带着 token 以 `pending_confirmation` 回来——那是**引擎的**回答、不是本类的
 * ——而模型会被告知这一点。有人会在控制台里批准它，那正是人在看的地方；模型只负责把这个消息带回去。
 */
@Component
public class YudaoGovernedAgentTools implements Ai1LocalToolProvider {

    /**
     * What this deployment writes for "there is no identity", the same sentinel the runtime's own
     * rules pin their refusal to. A turn that reached the tool loop without a caller therefore gets a
     * refusal it can read rather than a call made on nobody's behalf.
     *
     * 本部署为「没有身份」写下的那个值，与运行时自己的规则把「身份缺失即拒」钉在上面的**同一个哨兵**。一次没有
     * 调用方就到了工具循环的会话，因此得到一条**读得懂的拒绝**，而不是一次谁也没代表的调用。
     */
    private static final String NO_IDENTITY = "0";

    private final ToolRegistry tools;
    private final GovernedExecutionEngine engine;
    private final YudaoAdministrator administrators;

    public YudaoGovernedAgentTools(ToolRegistry tools, GovernedExecutionEngine engine,
                                   YudaoAdministrator administrators) {
        this.tools = tools;
        this.engine = engine;
        this.administrators = administrators;
    }

    /**
     * Every tool this runtime holds, for every agent.
     *
     * <p>The agent is not consulted, and that is this deployment's answer rather than an omission:
     * what a caller may do is decided per tool per caller by the engine, so narrowing the list per
     * agent would be a second, weaker copy of a question that is already answered where it is decided.
     * The parameter stays because the seam is the module's, and a deployment that does narrow would
     * read it here.
     *
     * 运行时持有的每一个工具，给每一个 agent。
     *
     * <p>不看 agent 是**本部署的回答**、不是遗漏：某个调用方能做什么，是引擎**逐工具、逐调用方**判的，故按 agent
     * 收窄这份清单，只会成为同一个问题**更弱的第二份答案**，而那个问题已经在**它被决定的地方**被回答了。参数留着，
     * 因为这条缝是**模块的**，而真去收窄的部署会在这里读它。
     */
    @Override
    public List<ToolCallback> buildTools(Ai1AgentDO agent) {
        Long caller = SecurityFrameworkUtils.getLoginUserId();
        return tools.all().stream().map(tool -> callback(tool, caller)).toList();
    }

    /**
     * One tool, as the model sees it: the name and the description the tool declares, a schema built
     * from the arguments it declares, and a call that goes to the engine.
     *
     * <p>The name is the tool's own, unprefixed, so a name in a conversation, in the console's tool
     * inventory and in an audit row are all the same string. That is worth a collision with an MCP
     * tool of the same name being possible in principle: two vocabularies for one tool would be worse,
     * and a deployment that binds both would see the duplicate the moment it looks at either list.
     *
     * 一个工具，按模型看到的样子：工具自己声明的名字与描述、由它声明的入参搭出来的 schema，以及一次**落到引擎**
     * 的调用。
     *
     * <p>名字就是工具自己的、不加前缀，于是一次对话里、控制台的工具清单里、审计行里，都是**同一个字符串**。为这个
     * 值得接受「原则上可能与同名 MCP 工具相撞」：一个工具两套词表更糟，而同时绑了两者的部署，看一眼任一份清单就会
     * 看见那个重名。
     */
    private ToolCallback callback(AiTool tool, Long caller) {
        Function<Map<String, Object>, String> call = arguments -> JsonUtils.toJsonString(
                engine.execute(tool.name(), arguments == null ? Map.of() : arguments, principal(caller)));
        return FunctionToolCallback.builder(tool.name(), call)
                .description(tool.description())
                .inputType(Map.class)
                .inputSchema(schema(tool))
                .build();
    }

    /**
     * Who the engine should treat as acting. Built the way this host's identity seam builds one — the
     * host's directory decides whether the caller is an administrator, never the caller — so a tool
     * reached from a conversation and one reached from the request path see the same role.
     *
     * 引擎该把谁当成行动者。按本宿主身份缝的建法建——**是不是管理员由宿主的名录回答**、从不由调用方自述——故一次
     * 从对话到达的工具与一次从请求路径到达的工具，看到的是同一个角色。
     */
    private Principal principal(Long caller) {
        if (caller == null) {
            return new Principal(NO_IDENTITY, Principal.ROLE_USER);
        }
        return new Principal(String.valueOf(caller),
                administrators.isAdmin(caller) ? Principal.ROLE_ADMIN : Principal.ROLE_USER);
    }

    /**
     * The arguments as JSON Schema, from the tool's own declaration. A tool that declares nothing gets
     * an empty one, which is exactly what it declared — the runtime checks no arguments then, and
     * saying so here keeps the model from being told about arguments nobody will check.
     *
     * 入参的 JSON Schema，取自工具自己的声明。什么都没声明的工具得到一份空的——那正是它声明的东西：运行时**不校验**
     * 任何入参，而在这里这么说，Model 就不会被告知一些**没人会校验**的入参。
     */
    private static String schema(AiTool tool) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ToolParameter parameter : tool.parameters()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("type", parameter.type());
            one.put("description", parameter.description());
            properties.put(parameter.name(), one);
            if (parameter.required()) {
                required.add(parameter.name());
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return JsonUtils.toJsonString(schema);
    }
}
