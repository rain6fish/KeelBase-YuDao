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
 * The system module's tool face over the host's in-app messages: one read and one write, with their
 * routes — the third face in this host, and the smallest, because the module it sits on already does
 * half the work (see {@link SystemNotifyMessageGovernance}).
 *
 * <p><b>What is different from the other two faces, said plainly.</b> CRM's and BPM's tools each had to
 * name the module's owner column in their projection; here the rows are the caller's by construction,
 * so the face is a page and a status write and nothing else. That is not the platform getting better —
 * it is this module having the shape upstream already.
 *
 * <p><b>The create path has no tool, and here the reason is not the engine.</b>
 * {@code createNotifyMessage}'s {@code userId} is the <b>recipient</b>, so a tool over it would be a
 * tool for sending mail to someone else — a different capability, and one nobody asked this line to
 * govern. It is left out and named.
 *
 * 系统模块的工具面，跑在宿主的站内信上：一读一写，各配路由——本宿主的第三个面，也是最小的一个，因为它所坐的模块
 * **已经做了半件事**（见 {@link SystemNotifyMessageGovernance}）。
 *
 * <p><b>与另外两个面的不同，直说。</b>CRM 与 BPM 的工具都得在投影里点名模块的属主列；这里行**按构造**就是调用方的，
 * 故本面只是一个分页加一次状态写、别无其他。这**不是**平台变好了——是**这个模块本来就有那个形状**。
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
    AiTool markNotifyMessageRead(NotifyMessageService service) {
        return new MarkReadTool(service);
    }

    @Bean
    ModuleRoute notifyMessageListRoute() {
        return new ListRoute();
    }

    @Bean
    ModuleRoute notifyMessageMarkReadRoute() {
        return new MarkReadRoute();
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
                    .map(ListTool::project)
                    .toList();
            return ToolResult.ok(rows);
        }

        /** The fields that leave this tool — a projection is where that decision is legible. */
        private static Map<String, Object> project(NotifyMessageDO row) {
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("id", row.getId());
            safe.put("templateCode", row.getTemplateCode());
            safe.put("templateType", row.getTemplateType());
            safe.put("readStatus", row.getReadStatus());
            safe.put("createTime", String.valueOf(row.getCreateTime()));
            return safe;
        }
    }

    /** The phrases this module's read answers to. / 本模块读所回答的短语。 */
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
            // The row to mark travels as `customerId` — the one structured slot the runtime's chat
            // request declares, named for CRM. Same workaround as the BPM face, and for the same
            // recorded reason.
            // 要标记的那一行以 `customerId` 传递——运行时的聊天请求唯一声明的结构化槽位、以 CRM 命名。与 BPM 那个面
            // 同样的绕法、同样的记录理由。
            if (context.get("customerId") != null) {
                args.put("id", context.get("customerId"));
            }
            return args;
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
