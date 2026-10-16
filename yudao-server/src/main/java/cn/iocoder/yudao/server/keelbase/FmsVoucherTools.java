// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.identity.ActingCaller;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.pipeline.ModuleRoute;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolParameter;
import cn.com.keelbase.runtime.tool.ToolResult;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.fms.controller.admin.voucher.vo.FmsVoucherPageReqVO;
import cn.iocoder.yudao.module.fms.dal.dataobject.config.FmsAccountUserDO;
import cn.iocoder.yudao.module.fms.dal.dataobject.voucher.FmsVoucherDO;
import cn.iocoder.yudao.module.fms.dal.mysql.voucher.FmsVoucherMapper;
import cn.iocoder.yudao.module.fms.enums.voucher.FmsVoucherStatusEnum;
import cn.iocoder.yudao.module.fms.service.config.FmsAccountUserService;
import cn.iocoder.yudao.module.fms.service.voucher.FmsVoucherService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * FMS 的工具面：一读（记账凭证分页）一写（审核），各配路由。这是本宿主的**第四个**这样的面，也是**第一个非 CRM**
 * 的（`YD-28`）。它要说明的是：面的**形状**照样移植得过来，而这一次多出**两处**模块自己的东西要处理。
 *
 * <p><b>账套是这个模块的门槛，而它不是调用方的身份。</b>分页与审核都先要一个 {@code accountSetId} —— 模块按**账套**
 * 授权（{@code fms_account_user} 的 level）。故读那一半**先自己解析调用方的账套**（{@code getAccountUserList}，
 * 优先取默认那个），而不是把这个 id 变成工具的一个必填参数：让模型先知道一个账套编号，是把模块的簿记细节搬到
 * 对话里。写那一半只需要凭证 id，账套**从那一行读**。
 *
 * <p><b>提交与投影：两个用户列都不出去。</b>凭证行上有两个「人」——{@code creator}（录入人，也是本模块切面收窄
 * 用的那一列）与 {@code reviewerUserId}（审核人）。两者都在出口投影里去掉：调用方已经知道是谁在操作，而另一个
 * 是别人的身份。
 *
 * FMS's tool face: one read (the voucher page) and one write (the review), with their routes. The **fourth** such
 * face in this host and the **first that is not CRM** (`YD-28`). What it shows is that the face *shape* transfers
 * again, and that this module brings **two** things of its own to handle.
 *
 * <p><b>The account set is this module's gateway, and it is not the caller's identity.</b> Both the page and the
 * review want an {@code accountSetId} first — the module authorises by **account set** (the level in
 * {@code fms_account_user}). So the read **resolves the caller's own account set** ({@code getAccountUserList},
 * preferring the default) instead of turning that id into a required tool parameter: making a model know an account
 * set number first would be carrying the module's bookkeeping into the conversation. The write needs only the
 * voucher id and reads the account set **off that row**.
 *
 * <p><b>Identity out of the projection: neither user column leaves.</b> A voucher row names two people —
 * {@code creator} (who entered it, and the column this module's advice narrows on) and {@code reviewerUserId} (who
 * reviewed it). Both are dropped at the exit: the caller already knows who is acting, and the other is someone
 * else's identity.
 */
@Configuration
public class FmsVoucherTools {

    @Bean
    AiTool listMyFmsVouchers(FmsVoucherService service, FmsAccountUserService accountUsers) {
        return new ListTool(service, accountUsers);
    }

    @Bean
    AiTool reviewFmsVoucher(FmsVoucherService service, FmsVoucherMapper mapper) {
        return new ReviewTool(service, mapper);
    }

    @Bean
    ModuleRoute fmsVoucherListRoute() {
        return new ListRoute();
    }

    @Bean
    ModuleRoute fmsVoucherReviewRoute() {
        return new ReviewRoute();
    }

    /** R1 — a read: no confirmation, nothing to revoke. / R1——读：无需确认、无可撤销。 */
    static final class ListTool implements AiTool {

        private final FmsVoucherService service;
        private final FmsAccountUserService accountUsers;

        ListTool(FmsVoucherService service, FmsAccountUserService accountUsers) {
            this.service = service;
            this.accountUsers = accountUsers;
        }

        @Override
        public String name() {
            return "list_my_fms_vouchers";
        }

        @Override
        public String description() {
            return "List the accounting vouchers this host's own FMS module holds for the caller, in the"
                    + " caller's own account set (read-only). The caller's account set is resolved from their"
                    + " memberships; only vouchers the caller entered are returned.";
        }

        @Override
        public String riskLevel() {
            return "R1";
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Principal principal) {
            Long caller = asId(principal.userId());
            List<Map<String, Object>> rows = ActingCaller.as(principal.userId(),
                            () -> TenantUtils.execute(YudaoCallerTenant.current(),
                                    () -> service.getVoucherPage(queryFor(caller), caller))).getList().stream()
                    .map(ListTool::project)
                    .toList();
            return ToolResult.ok(rows);
        }

        /**
         * 本模块的门槛是**账套**，而这一半把调用方自己的那个解析出来：优先取默认账套，没有默认就取第一个；
         * 一个都没有时，查询里就没有账套——那一页会像「这个调用方没有凭证」那样空着，而不是答一串别人的账。
         *
         * The module's gateway is the **account set**, and this half resolves the caller's own: the default one if
         * there is one, else the first; with none, the query carries no account set — the page then comes back
         * empty the way "this caller has no vouchers" is empty, rather than answering with someone else's books.
         */
        private FmsVoucherPageReqVO queryFor(Long caller) {
            FmsVoucherPageReqVO query = new FmsVoucherPageReqVO();
            query.setPageSize(100);
            List<FmsAccountUserDO> mine = caller == null ? List.of() : accountUsers.getAccountUserList(caller);
            mine.stream()
                    .filter(member -> Boolean.TRUE.equals(member.getDefaultStatus()))
                    .findFirst()
                    .or(() -> mine.stream().findFirst())
                    .map(FmsAccountUserDO::getAccountSetId)
                    .ifPresent(query::setAccountSetId);
            return query;
        }

        /** The fields that leave this tool — a projection is where that decision is legible. */
        private static Map<String, Object> project(FmsVoucherDO row) {
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("id", row.getId());
            safe.put("voucherNumber", row.getVoucherNumber());
            safe.put("voucherTime", row.getVoucherTime());
            safe.put("debitAmount", row.getDebitAmount());
            safe.put("creditAmount", row.getCreditAmount());
            safe.put("status", row.getStatus());
            return safe;
        }
    }

    /** The phrases this module's read answers to. / 本模块读所回答的短语。 */
    static final class ListRoute implements ModuleRoute {

        @Override
        public String toolName() {
            return "list_my_fms_vouchers";
        }

        @Override
        public List<String> triggers() {
            return List.of("看我的凭证", "我的凭证", "凭证列表", "列凭证", "我录的凭证");
        }

        @Override
        public Map<String, Object> args(Map<String, Object> context) {
            return Map.of();
        }
    }

    /**
     * R3 — 按 id 的写：引擎扣下等人，而调用方范围之外的那一行**批准了也照样被拒**（收窄在切面上，见
     * {@link FmsVoucherGovernance}）。工具**刻意**比模块自己的 setter 更窄：模块收任意状态码与一串 id，而本面就是
     * 「审核通过」这一件事，故意图在工具名里、不在调用方给的一串数字里。
     *
     * R3 — a write by id: the engine holds it for a human, and a row outside the caller's scope is refused **even
     * after approval** (the narrowing is in the advice, see {@link FmsVoucherGovernance}). The tool is narrower than
     * the module's own setter on purpose: the module takes any status code and a list of ids, while this face means
     * exactly "approve one", so the intent is in the tool's name rather than in numbers a caller supplies.
     */
    static final class ReviewTool implements AiTool {

        private final FmsVoucherService service;
        private final FmsVoucherMapper mapper;

        ReviewTool(FmsVoucherService service, FmsVoucherMapper mapper) {
            this.service = service;
            this.mapper = mapper;
        }

        @Override
        public String name() {
            return "review_fms_voucher";
        }

        @Override
        public String description() {
            return "Approve one accounting voucher in the FMS module this host already ships. The engine holds"
                    + " the call for a human; a voucher the caller did not enter is refused even after approval.";
        }

        @Override
        public String riskLevel() {
            return "R3";
        }

        @Override
        public String resultType() {
            return "fms_voucher";
        }

        @Override
        public List<ToolParameter> parameters() {
            return List.of(
                    ToolParameter.required("id", ToolParameter.NUMBER, "凭证编号"));
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Principal principal) {
            Long id = asLong(args.get("id"));
            if (id == null) {
                return ToolResult.fail("id is required and must be a number");
            }
            // 账套从那一行读：模块的审核要求它，而调用方只该给凭证 id。**这一读必须在调用方的上下文里做** ——
            // 凭证表带 `tenant_id`，宿主的租户拦截器要求上下文里有租户编号；在包装器外面读它，会以
            // 「`TenantContextHolder` 不存在租户编号」整条失败（本门禁第一次跑就是这么红的，而它在审计里长得
            // 像一次拒绝 —— 见那道门禁场景 4 的断言为何要钉在**理由**上）。
            //
            // The account set is read off the row: the module's review wants it, and the caller should only have to
            // give the voucher id. **That read has to happen inside the caller's context** — the voucher table
            // carries a `tenant_id` and the host's tenant interceptor wants one in the context; reading it outside
            // the wrapper fails the whole call with "no tenant id in TenantContextHolder" (which is how this gate
            // went red the first time, and it looks like a refusal on the audit trail — see why that gate's
            // scenario 4 asserts on the **reason**).
            Long accountSetId = ActingCaller.as(principal.userId(),
                    () -> TenantUtils.execute(YudaoCallerTenant.current(), () -> {
                        FmsVoucherDO voucher = mapper.selectById(id);
                        return voucher == null ? null : voucher.getAccountSetId();
                    }));
            if (accountSetId == null) {
                return ToolResult.fail("no voucher " + id);
            }
            Integer approved = FmsVoucherStatusEnum.APPROVED.getStatus();
            YudaoGovernedCall.write(principal.userId(), () -> {
                service.updateVoucherReviewStatus(accountSetId, List.of(id), approved,
                        asId(principal.userId()));
                return null;
            });
            // 结果**必须带 id**：引擎每个结果记一条副作用，而 `side_effects.result_id` 是 NOT NULL。
            // The result **must carry an id**: the engine records one side effect per result, and
            // `side_effects.result_id` is NOT NULL.
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("status", approved);
            return ToolResult.ok(out);
        }
    }

    static final class ReviewRoute implements ModuleRoute {

        @Override
        public String toolName() {
            return "review_fms_voucher";
        }

        @Override
        public List<String> triggers() {
            return List.of("审核凭证", "通过凭证", "凭证审核", "审批凭证", "把凭证过审");
        }

        @Override
        public Map<String, Object> args(Map<String, Object> context) {
            Map<String, Object> args = new LinkedHashMap<>();
            // 要审的那一行以 `targetId` 传递：运行时的聊天请求唯一的结构化槽位，而它是**通用**的
            // （从前叫 `customerId`、以 CRM 命名）。
            //
            // The row to review travels as `targetId`: the one structured slot the runtime's chat request declares,
            // generic now (it used to be `customerId`, named for CRM).
            if (context.get("targetId") != null) {
                args.put("id", context.get("targetId"));
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
