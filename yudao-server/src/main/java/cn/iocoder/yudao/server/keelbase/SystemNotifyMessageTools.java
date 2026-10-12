// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.identity.ActingCaller;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.pipeline.ModuleRoute;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolParameter;
import cn.com.keelbase.runtime.tool.ToolResult;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.system.controller.admin.notify.vo.message.NotifyMessageMyPageReqVO;
import cn.iocoder.yudao.module.system.dal.dataobject.notify.NotifyMessageDO;
import cn.iocoder.yudao.module.system.service.notify.NotifyMessageService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The system module's tool face over the host's in-app messages: two reads and two writes, with their
 * routes — the third face in this host.
 *
 * <p><b>Four tools because the gate has to reach four advices.</b> The module's own paths already scope
 * on the caller's pair, so the advice is defensive on most of them and there is nothing to observe —
 * except on the two that are not: the by-id read, which upstream leaves unscoped, and the "mark
 * everything read" write, which reads its pair from {@code args[0..1]} rather than {@code args[1..2]}.
 * Those two are why this face has a fourth and a third tool respectively; without them the advices
 * covering them would be code no test had ever run.
 *
 * <p><b>What is different from the other two faces, said plainly.</b> CRM's and BPM's tools each had to
 * name the module's owner column in their projection; here the rows are the caller's by construction.
 * That is not the platform getting better — it is this module having the shape upstream already.
 *
 * <p><b>The create path has no tool, and here the reason is not the engine.</b>
 * {@code createNotifyMessage}'s {@code userId} is the <b>recipient</b>, so a tool over it would be a
 * tool for sending mail to someone else — a different capability, and one nobody asked this line to
 * govern. It is left out and named.
 *
 * 系统模块的工具面，跑在宿主的站内信上：两读两写，各配路由——本宿主的第三个面。
 *
 * <p><b>四个工具，因为门禁够得到四条通知。</b>模块自己的路径大多已按调用方那一对收束，故通知在它们身上是**防御性**的、
 * 没有可观测的东西——除了那两条不是的：**按 id 读**（上游留着不收束）与**「全部标记已读」那个写**（它取的是
 * `args[0..1]` 那一对、不是 `args[1..2]`）。这两条正是本面要用第四个工具与第三个工具分别够到它们的原因；没有它们，
 * 管住它们的那两条通知就是**没有测试跑过的代码**。
 *
 * <p><b>与另外两个面的不同，直说。</b>CRM 与 BPM 的工具都得在投影里点名模块的属主列；这里行**按构造**就是调用方的。
 * 这**不是**平台变好了——是**这个模块本来就有那个形状**。
 *
 * <p><b>创建那条路没有工具，而这里的原因不是引擎。</b>{@code createNotifyMessage} 的 {@code userId} 是**收件人**，
 * 故在它之上的工具会是一个「给别人发信」的工具——另一种能力，而且本线没被要求去治理它。它被排除并点名。
 */
@Configuration
public class SystemNotifyMessageTools {

    /** The caller is an administrator: this host's identity bridge resolves Yudao access tokens. */
    private static final Integer CALLER_USER_TYPE = UserTypeEnum.ADMIN.getValue();

    @Bean
    AiTool listMyNotifyMessages(NotifyMessageService service) {
        return new ListTool(service);
    }

    @Bean
    AiTool getNotifyMessage(NotifyMessageService service) {
        return new GetTool(service);
    }

    @Bean
    AiTool markNotifyMessageRead(NotifyMessageService service) {
        return new MarkReadTool(service);
    }

    @Bean
    AiTool markAllNotifyMessagesRead(NotifyMessageService service) {
        return new MarkAllReadTool(service);
    }

    @Bean
    ModuleRoute notifyMessageListRoute() {
        return new ListRoute();
    }

    @Bean
    ModuleRoute notifyMessageGetRoute() {
        return new GetRoute();
    }

    @Bean
    ModuleRoute notifyMessageMarkReadRoute() {
        return new MarkReadRoute();
    }

    @Bean
    ModuleRoute notifyMessageMarkAllReadRoute() {
        return new MarkAllReadRoute();
    }

    /**
     * The fields that leave this tool — a projection is where that decision is legible, and both reads
     * share it so they cannot drift apart.
     *
     * 离开本工具的那些字段——投影正是那个决定可读的地方；两个读**共用**它，故它们不会各自漂走。
     */
    private static Map<String, Object> project(NotifyMessageDO row) {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("id", row.getId());
        safe.put("templateCode", row.getTemplateCode());
        safe.put("templateType", row.getTemplateType());
        safe.put("readStatus", row.getReadStatus());
        safe.put("createTime", String.valueOf(row.getCreateTime()));
        return safe;
    }

    /** R1 — a read: no confirmation, nothing to revoke. / R1——读：无需确认、无可撤销。 */
    static final class ListTool implements AiTool {

        private final NotifyMessageService service;

        ListTool(NotifyMessageService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "list_my_notify_messages";
        }

        @Override
        public String description() {
            return "List the in-app messages this host's own system module holds for the caller"
                    + " (read-only). The module scopes the page to the caller's own user id and type.";
        }

        @Override
        public String riskLevel() {
            return "R1";
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Principal principal) {
            NotifyMessageMyPageReqVO query = new NotifyMessageMyPageReqVO();
            query.setPageSize(100);
            Long caller = asId(principal.userId());
            // The pair the module scopes on; the governance advice pins it to the same caller, so this
            // is the value the row scope is actually decided by.
            // 模块据以收束的那一对；治理通知把它钉到同一个调用方，故行范围实际由这个值决定。
            List<Map<String, Object>> rows = ActingCaller.as(principal.userId(),
                            () -> TenantUtils.execute(YudaoCallerTenant.current(),
                                    () -> service.getMyMyNotifyMessagePage(query, caller, CALLER_USER_TYPE)))
                    .getList().stream()
                    .map(SystemNotifyMessageTools::project)
                    .toList();
            return ToolResult.ok(rows);
        }
    }

    /** The phrases this module's list answers to. / 本模块的列表所回答的短语。 */
    static final class ListRoute implements ModuleRoute {

        @Override
        public String toolName() {
            return "list_my_notify_messages";
        }

        @Override
        public List<String> triggers() {
            return List.of("我的消息", "站内信", "未读消息", "我的通知", "看消息");
        }

        @Override
        public Map<String, Object> args(Map<String, Object> context) {
            return Map.of();
        }
    }

    /**
     * R1 — a read by id, and the one path in this module whose governance the gate can observe. The
     * module's own {@code getNotifyMessage} is a bare {@code selectById}, so the advice withholds
     * another subject's row by handing back nothing; this tool turns that nothing into a
     * <b>failure</b> rather than an empty success. That is both what it means — the caller cannot be
     * given this message — and the only form of it the platform puts anywhere a test can read: the chat
     * answer carries no data, and the module's own by-id endpoint is behind a permission no non-admin
     * here holds. It does not distinguish "not yours" from "not there", deliberately: saying which
     * would leak that the row exists.
     *
     * R1——按 id 的读，也是本模块里**门禁能观测其治理**的那一条路。模块自己的 {@code getNotifyMessage} 是一句裸
     * `selectById`，故通知以「什么都不交回」扣下别的主体的行；本工具把那个「什么都不」变成一个**失败**、而不是一个空的
     * 成功。那既是它的含义（这封信给不了调用方），也是平台**唯一**会放到测试读得到的地方的那个形式：聊天答复不带数据，
     * 而模块自己的按 id 端点在**非管理员拿不到**的权限后面。它**不区分**「不是你的」与「不存在」，这是刻意的：说出是哪
     * 一个，就泄漏了那一行存在。
     */
    static final class GetTool implements AiTool {

        private final NotifyMessageService service;

        GetTool(NotifyMessageService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "get_notify_message";
        }

        @Override
        public String description() {
            return "Read one in-app message by id for the caller (read-only). A message that is not the"
                    + " caller's reads as nothing, and is reported as a failure rather than as empty.";
        }

        @Override
        public String riskLevel() {
            return "R1";
        }

        @Override
        public List<ToolParameter> parameters() {
            return List.of(
                    ToolParameter.required("id", ToolParameter.NUMBER, "站内信编号"));
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Principal principal) {
            Long id = asLong(args.get("id"));
            NotifyMessageDO row = ActingCaller.as(principal.userId(),
                    () -> TenantUtils.execute(YudaoCallerTenant.current(),
                            () -> service.getNotifyMessage(id)));
            if (row == null) {
                return ToolResult.fail("no message readable for this caller");
            }
            return ToolResult.ok(project(row));
        }
    }

    /** The phrases this module's by-id read answers to. / 本模块按 id 读所回答的短语。 */
    static final class GetRoute implements ModuleRoute {

        @Override
        public String toolName() {
            return "get_notify_message";
        }

        @Override
        public List<String> triggers() {
            // Disjoint from the other three routes' phrases on purpose: the planner matches a substring,
            // so an overlapping phrase would make which tool runs a matter of order.
            // **刻意**与另外三条路由的短语不相交：planner 是**按子串**匹配的，故短语重叠会让「跑哪个工具」变成顺序问题。
            return List.of("打开消息", "读这条消息", "查看这条消息");
        }

        @Override
        public Map<String, Object> args(Map<String, Object> context) {
            Map<String, Object> args = new LinkedHashMap<>();
            // The row travels as `customerId` — the runtime's one structured slot, named for CRM.
            // 那一行以 `customerId` 传递——运行时唯一的结构化槽位、以 CRM 命名。
            if (context.get("customerId") != null) {
                args.put("id", context.get("customerId"));
            }
            return args;
        }
    }

    /**
     * R3 — a write by id: the engine holds it for a human. There is no refusal to earn here even
     * though there is a scope question: the module's own update already filters on the caller's pair,
     * so a message that is not the caller's simply matches nothing. What the gate asserts is that the
     * other subject's row is untouched — see {@link SystemNotifyMessageGovernance}.
     *
     * R3——按 id 的写：引擎扣下等人。**这里没有可挣到的拒绝**，哪怕有范围问题：模块自己的更新本已按调用方那一对过滤，
     * 故不是调用方的消息**根本匹配不上**。门禁断言的是**另一个主体的那一行没动**——见
     * {@link SystemNotifyMessageGovernance}。
     */
    static final class MarkReadTool implements AiTool {

        private final NotifyMessageService service;

        MarkReadTool(NotifyMessageService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "mark_notify_message_read";
        }

        @Override
        public String description() {
            return "Mark one in-app message as read for the caller. The engine holds the call for a"
                    + " human; a message that is not the caller's matches nothing and changes nothing.";
        }

        @Override
        public String riskLevel() {
            return "R3";
        }

        @Override
        public String resultType() {
            return "system_notify_message";
        }

        @Override
        public List<ToolParameter> parameters() {
            return List.of(
                    ToolParameter.required("id", ToolParameter.NUMBER, "站内信编号"));
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Principal principal) {
            Long id = asLong(args.get("id"));
            Long caller = asId(principal.userId());
            int changed = ActingCaller.as(principal.userId(),
                    () -> TenantUtils.execute(YudaoCallerTenant.current(),
                            () -> service.updateNotifyMessageRead(List.of(id), caller, CALLER_USER_TYPE)));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("changed", changed);
            return ToolResult.ok(out);
        }
    }

    static final class MarkReadRoute implements ModuleRoute {

        @Override
        public String toolName() {
            return "mark_notify_message_read";
        }

        @Override
        public List<String> triggers() {
            return List.of("标记已读", "消息已读", "把消息标为已读", "标记消息");
        }

        @Override
        public Map<String, Object> args(Map<String, Object> context) {
            Map<String, Object> args = new LinkedHashMap<>();
            if (context.get("customerId") != null) {
                args.put("id", context.get("customerId"));
            }
            return args;
        }
    }

    /**
     * R3 — the second arity's write, and the reason this face has a fourth tool: the advice that pins
     * the pair at {@code args[0..1]} covers the unread count, the unread list and this write, and none
     * of them was reachable from a tool before. This one is a write, so what it does is visible in the
     * table — which is what makes the advice observable at all.
     *
     * R3——**第二种实参形状**的写，也是本面要有第四个工具的原因：把那一对钉在 {@code args[0..1]} 的通知，管的是未读
     * 计数、未读列表与这一个写，而它们此前**没有一个**从工具够得到。这一个是个写，故它做的事在表里看得见——那正是让这条
     * 通知**可观测**的东西。
     */
    static final class MarkAllReadTool implements AiTool {

        private final NotifyMessageService service;

        MarkAllReadTool(NotifyMessageService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "mark_all_notify_messages_read";
        }

        @Override
        public String description() {
            return "Mark every unread in-app message as read for the caller. The engine holds the call"
                    + " for a human; the module scopes it to the caller's own user id and type.";
        }

        @Override
        public String riskLevel() {
            return "R3";
        }

        /**
         * The result type is the caller's **mailbox**, not the message — because a bulk write has no
         * single result row, and the runtime requires one: an effect is recorded per result, and
         * `side_effects.result_id` is NOT NULL. Returning the message type with a null id does not
         * merely report badly — it makes the run **fail after the write has already landed** (measured:
         * the rows were marked read, the ledger insert threw, and the caller got an error). So the
         * effect is modelled as "this caller's mailbox changed", its id is the caller, and no
         * compensator claims this type — which is the honest state, since nothing here can put the
         * unread flags back.
         *
         * <p><b>This is a workaround for a platform constraint, not a modelling choice.</b> Any later
         * module with a bulk write meets the same wall; the constraint is recorded on this line and in
         * the project's record rather than left for the next author to discover the hard way.
         *
         * 结果类型是调用方的**信箱**、不是消息——因为一次批量写**没有单行结果**，而运行时要求有一个：副作用按结果记，
         * 且 `side_effects.result_id` 是 NOT NULL。拿消息类型配一个 null id 不只是报得难看——它会让这次运行**在写已经落地
         * 之后失败**（实测：行已标为已读、账本插入抛了、调用方拿到错误）。故这份副作用被建模成「**这位调用方的信箱变了**」，
         * 它的 id 就是调用方，且**没有补偿器**认这个类型——那是诚实的状态，因为这里没有任何东西能把未读标志放回去。
         *
         * <p>**这是对一条平台约束的绕法、不是建模选择。**此后任何带批量写的模块都会撞同一面墙；这条约束记在本行与项目的
         * 记录里，而不是留给下一个作者去硬撞。
         */
        @Override
        public String resultType() {
            return "notify_mailbox";
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Principal principal) {
            Long caller = asId(principal.userId());
            int changed = ActingCaller.as(principal.userId(),
                    () -> TenantUtils.execute(YudaoCallerTenant.current(),
                            () -> service.updateAllNotifyMessageRead(caller, CALLER_USER_TYPE)));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", caller);
            out.put("changed", changed);
            return ToolResult.ok(out);
        }
    }

    static final class MarkAllReadRoute implements ModuleRoute {

        @Override
        public String toolName() {
            return "mark_all_notify_messages_read";
        }

        @Override
        public List<String> triggers() {
            return List.of("全部已读", "全部标记为已读", "清空未读", "一键已读");
        }

        @Override
        public Map<String, Object> args(Map<String, Object> context) {
            return Map.of();
        }
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.valueOf(text.trim());
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
        return null;
    }

    private static Long asId(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException notAnId) {
            return null;
        }
    }
}
