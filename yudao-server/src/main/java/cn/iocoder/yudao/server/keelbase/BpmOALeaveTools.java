// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.identity.ActingCaller;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.pipeline.ModuleRoute;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolParameter;
import cn.com.keelbase.runtime.tool.ToolResult;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.bpm.controller.admin.oa.vo.BpmOALeavePageReqVO;
import cn.iocoder.yudao.module.bpm.dal.dataobject.oa.BpmOALeaveDO;
import cn.iocoder.yudao.module.bpm.enums.task.BpmTaskStatusEnum;
import cn.iocoder.yudao.module.bpm.service.oa.BpmOALeaveService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * BPM's tool face over the module upstream ships: one read and one write, with their routes. It is the
 * second such face in this host, and what it demonstrates is that the face <em>shape</em> transfers
 * while the code does not: nothing here is shared with {@link CrmCustomerTools} beyond the runtime's
 * own types.
 *
 * <p>Three things differ from CRM, and the third is <b>a leak in the runtime, not a choice here</b>:
 *
 * <ul>
 *   <li><b>The caller travels for the read, and the module takes it as an argument.</b> {@code
 *       getLeavePage} takes the user id, so identity is carried rather than merely available — the
 *       difference lands on the advice side, not here.</li>
 *   <li><b>The create path has no tool.</b> This module's create calls the process engine and this host
 *       has nothing runnable, so the face declares the read and the approve write and leaves create to
 *       the module's own REST path. Declaring a tool that could only fail would be worse than not.</li>
 *   <li><b>The row to act on travels as {@code targetId}.</b> The runtime's chat request
 *       ({@code cn.com.keelbase.runtime.web.ChatRequest}) declares one generic structured slot, and
 *       this route states the leave id in it. It used to be called {@code customerId} — named for CRM —
 *       and this bullet said a second module had to borrow it. **That is no longer true**: the slot was
 *       renamed generically, so the field written here is one this route may use as its own.</li>
 * </ul>
 *
 * BPM 的工具面，跑在**上游已经发布**的模块上：一读一写，各配路由。这是本宿主的第二个这样的面，而它要说明的是：
 * 面的**形状**可以移植，**代码**不能——这里除运行时自己的类型外，与 {@link CrmCustomerTools} 没有任何共用。
 *
 * <p>有三处与 CRM 不同，而第三处**是运行时的泄漏、不是这里的选择**：
 *
 * <ul>
 *   <li><b>读的那半，调用方是被带着的，而模块把它当入参取。</b>{@code getLeavePage} 取用户 id，故身份是被**带着**的、
 *       不只是「能拿到」——差别落在**通知那一侧**，不在这里。</li>
 *   <li><b>创建那条路没有工具。</b>本模块的创建调**流程引擎**，而本宿主没有可跑的东西，故本面只声明读与审批写、把创建
 *       留给模块自己的 REST 路径。声明一个只会失败的工具，比不声明更糟。</li>
 *   <li><b>要作用的那一行以 {@code targetId} 传递。</b>运行时的聊天请求
 *       （{@code cn.com.keelbase.runtime.web.ChatRequest}）声明一个**通用**的结构化槽位，而本路由把请假单 id 放进去。
 *       它从前叫 {@code customerId} —— **以 CRM 命名** —— 而这一条当时写着「第二个模块只能借它」。**那句现在不成立了**：
 *       槽位已改成通用名，故本路由写的这个字段是**它自己可以用**的。</li>
 * </ul>
 */
@Configuration
public class BpmOALeaveTools {

    @Bean
    AiTool listMyOaLeaves(BpmOALeaveService service) {
        return new ListTool(service);
    }

    @Bean
    AiTool approveOaLeave(BpmOALeaveService service) {
        return new ApproveTool(service);
    }

    @Bean
    ModuleRoute oaLeaveListRoute() {
        return new ListRoute();
    }

    @Bean
    ModuleRoute oaLeaveApproveRoute() {
        return new ApproveRoute();
    }

    /** R1 — a read: no confirmation, nothing to revoke. / R1——读：无需确认、无可撤销。 */
    static final class ListTool implements AiTool {

        private final BpmOALeaveService service;

        ListTool(BpmOALeaveService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "list_my_oa_leaves";
        }

        @Override
        public String description() {
            return "List the leave requests this host's own BPM module holds for the caller (read-only)."
                    + " Returns only the rows the caller applied for; the module scopes the page itself.";
        }

        @Override
        public String riskLevel() {
            return "R1";
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Principal principal) {
            BpmOALeavePageReqVO query = new BpmOALeavePageReqVO();
            query.setPageSize(100);
            Long caller = asId(principal.userId());
            // The identity travels as the argument upstream's own query takes, so the module scopes the
            // page itself rather than being post-filtered here.
            // 身份以**上游自己的查询所取的那个入参**传递，于是分页由模块自己收束，而不是在这里事后过滤。
            List<Map<String, Object>> rows = ActingCaller.as(principal.userId(),
                            () -> TenantUtils.execute(YudaoCallerTenant.current(),
                                    () -> service.getLeavePage(caller, query))).getList().stream()
                    .map(ListTool::project)
                    .toList();
            return ToolResult.ok(rows);
        }

        /** The fields that leave this tool — a projection is where that decision is legible. */
        private static Map<String, Object> project(BpmOALeaveDO row) {
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("id", row.getId());
            safe.put("userId", row.getUserId());
            safe.put("type", row.getType());
            safe.put("day", row.getDay());
            safe.put("status", row.getStatus());
            return safe;
        }
    }

    /** The phrases this module's read answers to. / 本模块读所回答的短语。 */
    static final class ListRoute implements ModuleRoute {

        @Override
        public String toolName() {
            return "list_my_oa_leaves";
        }

        @Override
        public List<String> triggers() {
            return List.of("看我的请假", "我的请假", "请假记录", "请假列表", "列请假");
        }

        @Override
        public Map<String, Object> args(Map<String, Object> context) {
            return Map.of();
        }
    }

    /**
     * R3 — a write by id: the engine holds it for a human, and a row outside the caller's scope is
     * refused even after approval. The tool is narrower than the module's own setter on purpose: the
     * module takes any task-status code, this face means "approve", so the intent is in the tool's name
     * rather than in a number a caller supplies.
     *
     * R3——按 id 的写：引擎扣下等人，而调用方范围之外的行**批准了也照样被拒**。本工具**刻意**比模块自己的 setter 更窄：
     * 模块收任意任务状态码，而本面就是「批准」，故意图在**工具名**里、不在调用方给的一个数字里。
     */
    static final class ApproveTool implements AiTool {

        private final BpmOALeaveService service;

        ApproveTool(BpmOALeaveService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "approve_oa_leave";
        }

        @Override
        public String description() {
            return "Approve a leave request in the BPM module this host already ships. The engine holds"
                    + " the call for a human; a row outside the caller's scope is refused even after"
                    + " approval.";
        }

        @Override
        public String riskLevel() {
            return "R3";
        }

        @Override
        public String resultType() {
            return "bpm_oa_leave";
        }

        @Override
        public List<ToolParameter> parameters() {
            return List.of(
                    ToolParameter.required("id", ToolParameter.NUMBER, "请假单编号"));
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Principal principal) {
            Long id = asLong(args.get("id"));
            Integer approved = BpmTaskStatusEnum.APPROVE.getStatus();
            YudaoGovernedCall.write(principal.userId(), () -> {
                service.updateLeaveStatus(id, approved);
                return null;
            });
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("status", approved);
            return ToolResult.ok(out);
        }
    }

    static final class ApproveRoute implements ModuleRoute {

        @Override
        public String toolName() {
            return "approve_oa_leave";
        }

        @Override
        public List<String> triggers() {
            return List.of("审批请假", "请假通过", "通过请假", "批准请假", "处理请假");
        }

        @Override
        public Map<String, Object> args(Map<String, Object> context) {
            Map<String, Object> args = new LinkedHashMap<>();
            // 要审批的那一行以 `targetId` 传递：那是运行时的聊天请求唯一的结构化槽位，而它现在是**通用**的
            // （从前叫 `customerId`、以 CRM 命名）。见类注释。
            //
            // The row to approve travels as `targetId`: the one structured slot the runtime's chat request
            // declares, generic now (it used to be `customerId`, named for CRM). See the class comment.
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
