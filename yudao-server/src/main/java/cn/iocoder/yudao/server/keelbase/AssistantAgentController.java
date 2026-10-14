// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which agent this deployment's console assistant talks to — answered here because nowhere else can.
 *
 * <p>The agent module's own reads are permission-gated (`ai1:agent:query` and friends), and that is
 * right for a page that <em>administers</em> agents: the catalogue is the deployment's, and a caller
 * who may not edit it has no business listing it. But a conversation cannot be started without an
 * agent id, so a caller-scoped assistant page has a hole exactly where it needs a fact — and widening
 * the admin read would hand every caller the whole catalogue to answer a question whose answer is one
 * agent.
 *
 * <p><b>So the deployment answers, and the answer is configuration rather than a row.</b> Which agent
 * the console's assistant is, is a decision about this deployment — the same kind of decision
 * {@code keelbase.ai.enabled} and the business-module labels already are — so it is read from the
 * deployment's own file. Nothing per-caller is returned: everyone gets the same id and the same name,
 * which is what makes this safe to answer to any authenticated caller.
 *
 * <p><b>Unconfigured is a supported state, and it says so.</b> With no id set the answer is
 * {@code configured: false} rather than a guess or a null-id the page would render as an empty picker.
 * The deployment that has not stood an assistant up yet has a page that says so.
 *
 * 本部署的控制台助手跟哪个 agent 说话——在这里回答，因为没有别的地方回答得了。
 *
 * <p>agent 模块自己的那些读是**带权限的**（`ai1:agent:query` 之类），而这对一个**管理** agent 的页面是
 * 对的：那份目录是**部署方的**，一个无权改它的人本来也不该列它。但**建会话必须有 agent id**，故一个按调用方
 * 限范围的助手页，恰好在它最需要那个事实的地方有个洞 —— 而**放宽**那条管理读，等于为了回答一个「就是某一个
 * agent」的问题，把整份目录递给每一个调用方。
 *
 * <p><b>于是由部署方回答，而且答案是配置、不是一行数据。</b>控制台的助手是哪个 agent，是关于**本部署**的一个
 * 决定 —— 与 `keelbase.ai.enabled`、与那些业务模块标签同一类 —— 故它从**部署自己的文件**里读。返回的**没有
 * 任何按调用方的东西**：每个人拿到同一个 id、同一个名字，这正是它可以答给任何**已认证**调用方的原因。
 *
 * <p><b>没配是一种受支持的状态，而且它说出来。</b>没有 id 时答案是 `configured: false`，而不是猜一个、也不是
 * 给一个会被页面画成空选择器的 null id。还没把助手立起来的部署，得到的是一页**如实这么说**的界面。
 */
@RestController
public class AssistantAgentController {

    /**
     * The agent this deployment's console assistant uses, from the deployment's own configuration.
     * Empty means unconfigured — see the class comment; it is not an error.
     *
     * 本部署的控制台助手所用的 agent，取自部署自己的配置。空即未配置——见类注释；那不是错误。
     */
    private final String agentId;

    public AssistantAgentController(@Value("${keelbase.assistant.agent-id:}") String agentId) {
        this.agentId = agentId;
    }

    /**
     * The assistant's agent, in the shape a page can act on: whether one is configured, and which.
     *
     * <p>The route sits under the host's admin namespace rather than the runtime's, so the host's own
     * filter chain authenticates it; it carries no permission annotation, so any logged-in caller may
     * read it — which is the whole point of answering it here.
     *
     * 助手的 agent，按一个页面能据以行动的形状：是否配了、以及是哪一个。
     *
     * <p>这条路由落在**宿主的管理端命名空间**下、而不是运行时的，故由**宿主自己的**过滤链认证它；它不带权限
     * 注解，故任何**已登录**调用方都能读 —— 而这正是把它答在这里的全部意义。
     */
    @GetMapping("/admin-api/keelbase/assistant/agent")
    public CommonResult<Map<String, Object>> agent() {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("configured", !agentId.isBlank());
        answer.put("agentId", agentId.isBlank() ? null : agentId);
        //  ANSWERED IN THE HOST'S ENVELOPE, not as a bare object. Every other `/admin-api` route answers
        // `{code, data, msg}`, and the console's own client unwraps exactly that shape — a bare object
        // would reach the page as `data` already unwrapped and then be unwrapped again, which is how a
        // correct answer turns into a page that renders nothing.
        //
        // 按**宿主的信封**回答，而不是一个裸对象。其余每一条 `/admin-api` 路由答的都是 `{code, data, msg}`，
        // 而控制台自己的客户端正是按那个形状解包的 —— 一个裸对象到了页面手里已经是解过包的 `data`，还会被再解一次，
        // 那正是一个**正确的答案**变成**什么都画不出来的页面**的方式。
        return CommonResult.success(answer);
    }
}
