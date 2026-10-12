// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.pipeline.ModuleRoute;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolParameter;
import cn.com.keelbase.runtime.tool.ToolResult;
import cn.com.keelbase.runtime.web.ChatTurnService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Where this console's pages are, as the runtime's tool face sees them: one read tool whose result
 * names a route, and the route that reaches it without a model.
 *
 * <p>Navigation is not a second pipeline. The tool declares no result type, so it takes no write
 * claim, records no side effect and asks for no confirmation — and it goes through the same engine,
 * so the turn is audited like any other. What it needs from the platform is the way a route leaves the
 * runtime, and that is {@link ChatTurnService#NAVIGATE_TO}: the key the frozen {@code chat-response}
 * and the frozen {@code navigate} event both carry.
 *
 * <p><b>The list is curated, and that is a decision rather than a shortcut.</b> This deployment's menu
 * table holds hundreds of rows, most of them buttons, and an assistant that offers all of them offers
 * noise. What is listed here is the console's areas and their main pages. Two consequences are
 * deliberate:
 *
 * <ul>
 *   <li><b>Offered is not the same set as recognised.</b> Offering a page is this list's job;
 *       whether a given caller may open it is the console's own menu permissions, which this tool does
 *       not second-guess. The console's endpoint is administrator-gated, so the caller here is a
 *       console administrator; narrowing by the caller's own menu grants is a follow-up with its own
 *       reasoning, not something to approximate here.</li>
 *   <li><b>A curated list is a second place to forget</b>, which is why
 *       {@code scripts/check-console-pages.mjs} asserts every route below is one the menus we ship can
 *       actually resolve. A page that moves upstream turns that check red rather than leaving a route
 *       here that goes nowhere.</li>
 * </ul>
 *
 * <p>本控制台有哪些页面，按运行时的工具面看：一个读工具、其结里点名一条路径，以及一条**无模型也能到**的路由。
 *
 * <p>导航**不是**第二条流水线。该工具不声明结果类型，故不占写声明、不记副作用、不要确认 —— 而它走**同一个引擎**，
 * 于是这一轮与别的轮一样被审计。它向平台要的，是**一条路径怎么离开运行时**，那正是
 * {@link ChatTurnService#NAVIGATE_TO}：冻结的 {@code chat-response} 与冻结的 {@code navigate} 事件**都带**的那个键。
 *
 * <p><b>这份清单是策展的，而这是一个决定、不是图省事。</b>本部署的菜单表有几百行、且多半是按钮，而一个把它们**全都**
 * 摆出来的助手摆出来的是噪声。这里列的是控制台的**各个区**与它们的主要页面。两个后果是**刻意**的：
 *
 * <ul>
 *   <li><b>「提供的清单」与「被认出的集合」不是同一个。</b>提供哪些页面是本清单的事；某个调用方**能不能打开**某一页，
 *       是控制台自己的菜单权限，本工具**不替它猜**。控制台那条端点本身有管理员门，故这里的调用方是控制台管理员；
 *       按调用方自己的菜单授权再收窄，是一件**有它自己理由**的后续，不是在这里近似一下的事。</li>
 *   <li><b>策展清单就是第二个可以忘的地方</b>，所以 {@code scripts/check-console-pages.mjs} 断言下面每一条路径，
 *       都是**我们发布的菜单**解得出来的那一条。上游把某个页面挪走时，那道检查**变红**，而不是在留在这里的一条
 *       哪儿也去不了的路径。</li>
 * </ul>
 */
@Configuration
public class ConsolePages {

    /** The tool's name, and the route's, kept together because they must agree. */
    static final String TOOL_NAME = "navigate_console_page";

    /**
     * One console page: the key a caller passes, the name a phrase uses, and the route the console
     * navigates to.
     *
     * <p>The route is what the menu tree composes — parent path and child path joined — because that
     * is what the console's router receives, and the check script resolves the same way.
     *
     * 控制台的一页：调用方传的键、口令里用的名字、以及控制台要跳去的那条路径。
     *
     * <p>路径是菜单树**拼出来的**那条（父路径与子路径相接），因为那正是控制台路由收到的那个，而检查脚本
     * 也按同一方式解。
     */
    record Page(String key, String name, String route, String description) {
    }

    /**
     * The pages this deployment offers, and the only routes the tool can produce.
     *
     * <p>Every entry is a page this deployment's menus actually carry today; the check script is what
     * keeps that sentence true rather than a comment.
     *
     * 本部署提供的页面，也是本工具**唯一**能产出的路径。
     *
     * <p>每一条都是本部署的菜单今天**真的带着**的页面；让这句话不只是一句注释的，是那道检查脚本。
     */
    static final List<Page> PAGES = List.of(
            new Page("system", "系统管理", "/system", "用户、角色、菜单、部门、岗位、字典、租户、地区"),
            new Page("users", "用户管理", "/system/user", "查看与维护用户账号"),
            new Page("roles", "角色管理", "/system/role", "角色的菜单与数据权限"),
            new Page("menus", "菜单管理", "/system/menu", "菜单与按钮权限的定义"),
            new Page("depts", "部门管理", "/system/dept", "组织架构"),
            new Page("dicts", "字典管理", "/system/dict", "数据字典"),
            new Page("tenants", "租户管理", "/system/tenant", "多租户与租户套餐"),
            new Page("messages", "消息中心", "/system/messages", "站内消息"),
            new Page("audit-log", "审计日志", "/system/log", "操作日志"),
            new Page("infra", "基础设施", "/infra", "代码生成、定时任务、文件、配置"),
            new Page("jobs", "定时任务", "/infra/job", "定时任务的维护与日志"),
            new Page("files", "文件管理", "/infra/file", "已上传的文件"),
            new Page("codegen", "代码生成", "/infra/codegen", "由数据库表生成代码"),
            new Page("api-log", "API 日志", "/infra/log", "接口访问日志"),
            new Page("report", "报表管理", "/report", "积木报表与大屏设计器"),
            new Page("bpm", "工作流程", "/bpm", "流程定义与审批"),
            new Page("approval-tasks", "审批中心", "/bpm/task", "待我审批的流程任务"),
            new Page("crm", "CRM 系统", "/crm", "线索、客户、商机、合同"),
            new Page("crm-clues", "线索管理", "/crm/clue", "销售线索"),
            new Page("crm-customers", "客户管理", "/crm/customer", "客户档案"),
            new Page("crm-business", "商机管理", "/crm/business", "销售商机"),
            new Page("crm-contracts", "合同管理", "/crm/contract", "销售合同"),
            new Page("oa", "OA 办公协同", "/oa", "工作台、笔记、通讯录、云盘"),
            new Page("oa-notes", "笔记管理", "/oa/note", "个人与共享笔记"),
            new Page("oa-contacts", "通讯录", "/oa/contact", "内部通讯录"),
            new Page("hrm", "HRM 人力资源", "/hrm", "组织、员工、考勤与薪酬"),
            new Page("hrm-dept", "组织管理", "/hrm/dept", "人力资源视角的组织架构"),
            new Page("fms", "FMS 财务管理", "/fms", "财务基础资料与账务"),
            new Page("ai", "AI 大模型", "/ai", "对话、绘画、写作、知识库"),
            new Page("ai-chat", "AI 对话", "/ai/chat", "与模型对话"),
            new Page("ai-knowledge", "AI 知识库", "/ai/knowledge", "知识库与文档检索"));

    @Bean
    AiTool navigateConsolePage() {
        return new NavigateTool();
    }

    @Bean
    ModuleRoute consolePageRoute() {
        return new NavigateRoute();
    }

    /**
     * R1 — a read. It holds nothing, writes nothing and is never claimed; what it produces is a route.
     *
     * <p>An unknown key is a failure rather than a guess: a tool that invented a route would send the
     * console somewhere nobody chose.
     *
     * R1 —— 读。它什么都不持有、什么都不写、也从不占位；它产出的是**一条路径**。
     *
     * <p>不认识的键是**失败**、不是猜：一个凭空造路径的工具，会把控制台送到**没有人选过**的地方去。
     */
    static final class NavigateTool implements AiTool {

        @Override
        public String name() {
            return TOOL_NAME;
        }

        @Override
        public String description() {
            StringBuilder sb = new StringBuilder();
            sb.append("Open a page of the administration console and let the console navigate to it. ")
                    .append("Pass one `page` key from the list below, then say which page you opened ")
                    .append("instead of only claiming that you did. Pages: ");
            for (int i = 0; i < PAGES.size(); i++) {
                Page page = PAGES.get(i);
                if (i > 0) {
                    sb.append("; ");
                }
                sb.append(page.key()).append('=').append(page.name())
                        .append('(').append(page.route()).append(')');
            }
            return sb.toString();
        }

        @Override
        public String riskLevel() {
            return "R1";
        }

        @Override
        public List<ToolParameter> parameters() {
            return List.of(new ToolParameter("page", "string",
                    "One of the page keys this tool lists", true));
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Principal principal) {
            Object requested = args == null ? null : args.get("page");
            String key = requested == null ? null : String.valueOf(requested);
            return PAGES.stream()
                    .filter(page -> page.key().equals(key))
                    .findFirst()
                    .map(NavigateTool::result)
                    .orElseGet(() -> ToolResult.fail(
                            "unknown page: " + key + " (use one of the keys this tool lists)"));
        }

        private static ToolResult result(Page page) {
            // The key comes from the platform's own constant rather than a literal here: the two
            // endpoints read this name, and a second spelling would produce a route nobody reports.
            //
            // 这个键取自**平台自己的**常量、不是这里的一个字面量：两条端点读的都是这个名字，而第二种拼法会
            // 产出一条**没有人报出来**的路径。
            Map<String, Object> data = new LinkedHashMap<>();
            data.put(ChatTurnService.NAVIGATE_TO, page.route());
            data.put("pageName", page.key());
            data.put("description", page.description());
            return ToolResult.ok(data);
        }
    }

    /**
     * The same destination without a model: phrases that name a page, matched before the runtime's own
     * rules.
     *
     * <p>It exists for the deployments that run with no model configured, where the planner is the
     * rule-based one and a phrase nobody wrote a rule for reaches nothing. The phrases are generated
     * from {@link #PAGES} so the two cannot drift into offering different pages.
     *
     * 没有模型时的同一个去处：点名某一页的短语，先于运行时自己的规则被匹配。
     *
     * <p>它是为**没配模型**的那些部署存在的 —— 那里规划器是规则版，而没人写过规则的短语什么都到不了。
     * 短语由 {@link #PAGES} 生成，故两者不可能漂移成「提供不一样的页面」。
     */
    static final class NavigateRoute implements ModuleRoute {

        @Override
        public String toolName() {
            return TOOL_NAME;
        }

        @Override
        public List<String> triggers() {
            return PAGES.stream().map(NavigateRoute::triggerOf).toList();
        }

        @Override
        public Map<String, Object> args(Map<String, Object> context) {
            Object said = context == null ? null : context.get("message");
            String message = said == null ? "" : String.valueOf(said);
            // No match means an empty argument map, which the gate refuses as `invalid_arguments`
            // before reaching anyone — the honest end, and not reachable in practice, since the
            // planner only asks a route whose trigger the message already carried.
            //
            // 匹配不上就是一张空参数表，闸门会以 `invalid_arguments` 在**到人之前**拒掉它 —— 那是诚实的结局，
            // 而实践中到不了：规划器只问那条**消息已经带了它触发词**的路由。
            return PAGES.stream()
                    .filter(page -> message.contains(triggerOf(page)))
                    .findFirst()
                    .map(page -> Map.<String, Object>of("page", page.key()))
                    .orElseGet(Map::of);
        }

        private static String triggerOf(Page page) {
            return "打开" + page.name();
        }
    }
}
