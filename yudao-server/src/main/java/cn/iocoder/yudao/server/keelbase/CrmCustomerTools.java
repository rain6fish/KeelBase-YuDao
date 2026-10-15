// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.identity.ActingCaller;
import cn.com.keelbase.runtime.pipeline.ModuleRoute;

import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolParameter;
import cn.com.keelbase.runtime.tool.ToolResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.crm.controller.admin.customer.vo.customer.CrmCustomerPageReqVO;
import cn.iocoder.yudao.module.crm.controller.admin.customer.vo.customer.CrmCustomerSaveReqVO;
import cn.iocoder.yudao.module.crm.dal.dataobject.customer.CrmCustomerDO;
import cn.iocoder.yudao.module.crm.service.customer.CrmCustomerService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CRM's tool face over the module <em>upstream already ships</em>: one read and one write, with their
 * routes. Nothing here is generated — this is YD-2's point.
 *
 * <p>Two things make this different from the module the adapter generated, and both are the reason
 * the second goal was worth stating separately:
 *
 * <ul>
 *   <li><b>The service is upstream's.</b> We call {@code CrmCustomerService} as it is written; no
 *       line of CRM is rewritten, and none is regenerated. What YD-2 adds is governance over it.</li>
 *   <li><b>The caller is carried, not merely available.</b> Yudao's own page query takes the user id
 *       as an argument, so identity has to travel: {@link ActingCaller} carries it for the window of
 *       the call, and {@link CrmCustomerGovernance} reads it back — which is how the module's own
 *       REST path and this tool end up held by the same advices.</li>
 * </ul>
 *
 * <p>The projection drops the contact fields. That decision is legible here rather than at an edge,
 * and it follows the admin console's red line that identity fields are masked on the way out.
 *
 * CRM 的工具面，跑在**上游已经发布**的模块上：一读一写，各配路由。这里没有一行是生成的——这正是 `YD-2` 的要点。
 *
 * <p>有两点让它与适配器生成的那个模块不同，而两点都是「第二个目标值得单列」的理由：
 *
 * <ul>
 *   <li><b>service 是上游的。</b>我们照 `CrmCustomerService` 本来的写法调它；CRM 里没有一行被改写、也没有一行被
 *       重新生成。`YD-2` 加的是**它之上的治理**。</li>
 *   <li><b>调用方是被**带着**的，不只是「能拿到」。</b>芋道自己的分页查询把用户 id 作为**入参**，于是身份必须**传递**：
 *       {@link ActingCaller} 在调用窗口里带着它，{@link CrmCustomerGovernance} 再读回来——模块自己的 REST 路径与
 *       本工具因此被**同一批通知**管住。</li>
 * </ul>
 *
 * <p>投影去掉了联系方式那几个字段。这个决定在这里可读，而不是在某个边界上；它服从管理端那条红线：身份字段**输出时掩码**。
 */
@Configuration
public class CrmCustomerTools {

    @Bean
    AiTool listCrmCustomers(CrmCustomerService service) {
        return new ListTool(service);
    }

    @Bean
    AiTool createCrmCustomer(CrmCustomerService service) {
        return new CreateTool(service);
    }

    @Bean
    ModuleRoute crmCustomerListRoute() {
        return new ListRoute();
    }

    @Bean
    AiTool updateCrmCustomer(CrmCustomerService service) {
        return new UpdateTool(service);
    }

    @Bean
    ModuleRoute crmCustomerCreateRoute() {
        return new CreateRoute();
    }

    @Bean
    ModuleRoute crmCustomerUpdateRoute() {
        return new UpdateRoute();
    }

    /** R1 — a read: no confirmation, nothing to revoke. / R1——读：无需确认、无可撤销。 */
    static final class ListTool implements AiTool {

        private final CrmCustomerService service;

        ListTool(CrmCustomerService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "list_crm_customers";
        }

        @Override
        public String description() {
            return "List the customers this host's own CRM module holds (read-only). Returns only the"
                    + " rows the caller is party to; contact fields are not projected.";
        }

        @Override
        public String riskLevel() {
            return "R1";
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Principal principal) {
            CrmCustomerPageReqVO query = new CrmCustomerPageReqVO();
            query.setPageSize(100);
            Long caller = asId(principal.userId());
            // The identity travels as the argument upstream's own query takes, so the module scopes
            // the page itself rather than being post-filtered here.
            // 身份以**上游自己的查询所取的那个入参**传递，于是分页由模块自己收束，而不是在这里事后过滤。
            List<Map<String, Object>> rows = ActingCaller.as(principal.userId(),
                            () -> TenantUtils.execute(YudaoCallerTenant.current(),
                                    () -> service.getCustomerPage(query, caller))).getList().stream()
                    .map(ListTool::project)
                    .toList();
            return ToolResult.ok(rows);
        }

        /** The fields that leave this tool — a projection is where that decision is legible. */
        private static Map<String, Object> project(CrmCustomerDO row) {
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("id", row.getId());
            safe.put("name", row.getName());
            safe.put("ownerUserId", row.getOwnerUserId());
            safe.put("dealStatus", row.getDealStatus());
            safe.put("followUpStatus", row.getFollowUpStatus());
            safe.put("level", row.getLevel());
            safe.put("source", row.getSource());
            return safe;
        }
    }

    /** The phrases this module's read answers to. / 本模块读所回答的短语。 */
    static final class ListRoute implements ModuleRoute {

        @Override
        public String toolName() {
            return "list_crm_customers";
        }

        @Override
        public List<String> triggers() {
            return List.of("列出客户", "客户列表", "CRM 客户", "看客户", "有哪些客户");
        }

        @Override
        public Map<String, Object> args(Map<String, Object> context) {
            return Map.of();
        }
    }

    /** R3 — a write: the engine holds it for a human before it runs. / R3——写：引擎扣下等人。 */
    static final class CreateTool implements AiTool {

        private final CrmCustomerService service;

        CreateTool(CrmCustomerService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "create_crm_customer";
        }

        @Override
        public String description() {
            return "Create a customer in the CRM module this host already ships. The engine holds the"
                    + " call for a human before it runs.";
        }

        @Override
        public String riskLevel() {
            return "R3";
        }

        @Override
        public String resultType() {
            return "crm_customer";
        }

        @Override
        public List<ToolParameter> parameters() {
            return List.of(
                    ToolParameter.required("name", ToolParameter.STRING, "客户名称"),
                    ToolParameter.optional("mobile", ToolParameter.STRING, "手机"),
                    ToolParameter.optional("level", ToolParameter.NUMBER, "客户等级（字典 crm_customer_level）"),
                    ToolParameter.optional("source", ToolParameter.NUMBER, "客户来源（字典 crm_customer_source）"));
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Principal principal) {
            CrmCustomerSaveReqVO row = new CrmCustomerSaveReqVO();
            row.setName(str(args.get("name")));
            row.setMobile(str(args.get("mobile")));
            row.setLevel(asInt(args.get("level")));
            row.setSource(asInt(args.get("source")));
            // The owner is not set here on purpose: CrmCustomerGovernance stamps it from the caller,
            // the same way it does for the module's own REST path. A value named here would be
            // overwritten by it anyway — which is the property being asserted.
            // 属主**刻意不在这里设**：由 CrmCustomerGovernance 从调用方盖上去，与它对模块自己的 REST 路径一样。
            // 这里就算写了，也会被它覆盖——而那正是被断言的性质。
            Long caller = asId(principal.userId());
            Long id = ActingCaller.as(principal.userId(),
                    () -> TenantUtils.execute(YudaoCallerTenant.current(), () -> service.createCustomer(row, caller)));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            return ToolResult.ok(out);
        }
    }

    static final class CreateRoute implements ModuleRoute {

        @Override
        public String toolName() {
            return "create_crm_customer";
        }

        @Override
        public List<String> triggers() {
            return List.of("新建客户", "创建客户", "添加客户", "开一个客户");
        }

        @Override
        public Map<String, Object> args(Map<String, Object> context) {
            Map<String, Object> args = new LinkedHashMap<>();
            for (String key : List.of("name", "mobile", "level", "source")) {
                if (context.get(key) != null) {
                    args.put(key, context.get(key));
                }
            }
            // A placeholder for argument extraction, as the host's routes are: when nothing structured
            // arrived, the typed text names the row. / 参数抽取的占位，与宿主的那些路由一样。
            if (args.get("name") == null && context.get("message") instanceof String typed && !typed.isBlank()) {
                args.put("name", typed);
            }
            return args;
        }
    }

    /**
     * R3 — a write by id, and the one that shows what a human approval does <em>not</em> buy. The
     * engine holds it for a person; approving it settles the risk question and nothing else, because
     * the row-scope question is answered lower down, at the module's own seam. A customer that is not
     * the caller's is refused there — after approval — and that refusal is what the audit keeps.
     *
     * R3——按 id 的写，也是用来说明**「有人批准了」买不到什么**的那一个。引擎把它扣下等人；批准它了结的是
     * **风险**问题、仅此而已，因为行范围的问题在更下面、在模块自己的接缝上回答。不属于调用方的客户就**在那里**被拒
     * ——在批准**之后**——而那次拒绝正是审计留下的东西。
     */
    static final class UpdateTool implements AiTool {

        private final CrmCustomerService service;

        UpdateTool(CrmCustomerService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "update_crm_customer";
        }

        @Override
        public String description() {
            return "Update a customer in the CRM module this host already ships. The engine holds the"
                    + " call for a human; a row outside the caller's scope is refused even after approval.";
        }

        @Override
        public String riskLevel() {
            return "R3";
        }

        @Override
        public String resultType() {
            return "crm_customer";
        }

        @Override
        public List<ToolParameter> parameters() {
            return List.of(
                    ToolParameter.required("id", ToolParameter.NUMBER, "客户编号"),
                    ToolParameter.optional("name", ToolParameter.STRING, "客户名称"),
                    ToolParameter.optional("level", ToolParameter.NUMBER, "客户等级（字典 crm_customer_level）"),
                    ToolParameter.optional("source", ToolParameter.NUMBER, "客户来源（字典 crm_customer_source）"));
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Principal principal) {
            Long id = asLong(args.get("id"));
            CrmCustomerSaveReqVO row = new CrmCustomerSaveReqVO();
            row.setId(id);
            row.setName(str(args.get("name")));
            row.setLevel(asInt(args.get("level")));
            row.setSource(asInt(args.get("source")));
            // 那一行**原先**的样子，在与这次写**同一个身份**下读出，于是轨迹回答的是「这次改了什么」、而不只是
            // 「它现在是什么样」。它带的是**读工具那份同样的投影**，理由相同：联系方式字段一律不外流，而快照不是例外。
            //
            // The row as it was, read under the identity the write runs under, so the trail answers
            // "what did this change" and not only "what does it look like now". It carries the same
            // projection the read tool makes, for the same reason: the contact fields leave nothing,
            // and a snapshot is not an exception to that.
            String before = ActingCaller.as(principal.userId(),
                    () -> TenantUtils.execute(YudaoCallerTenant.current(), () -> {
                        CrmCustomerDO existing = service.getCustomer(id);
                        return existing == null ? null
                                : JsonUtils.toJsonString(ListTool.project(existing));
                    }));
            ActingCaller.as(principal.userId(),
                    () -> TenantUtils.execute(YudaoCallerTenant.current(), () -> {
                        service.updateCustomer(row);
                        return null;
                    }));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", row.getId());
            return ToolResult.ok(out, before);
        }
    }

    static final class UpdateRoute implements ModuleRoute {

        @Override
        public String toolName() {
            return "update_crm_customer";
        }

        @Override
        public List<String> triggers() {
            return List.of("修改客户", "更新客户", "改客户");
        }

        @Override
        public Map<String, Object> args(Map<String, Object> context) {
            Map<String, Object> args = new LinkedHashMap<>();
            // The row to change is the one the turn named; the runtime resolves it and hands it over as
            // `customerId`, which is the only structured slot the request declares.
            // 要改的那一行是本轮点到的那一行；运行时解析它、以 `customerId` 交过来——那是请求唯一声明的结构化槽位。
            if (context.get("customerId") != null) {
                args.put("id", context.get("customerId"));
            }
            // A placeholder for argument extraction, as the host's routes are: the typed text names the row.
            // 参数抽取的占位，与宿主的那些路由一样：用户所写的文字就是新名字。
            if (context.get("message") instanceof String typed && !typed.isBlank()) {
                args.put("name", typed);
            }
            return args;
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
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

    private static Integer asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.valueOf(text.trim());
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
