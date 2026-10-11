// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.identity.ActingCaller;

import cn.com.keelbase.runtime.authz.OwnershipGuard;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolParameter;
import cn.com.keelbase.runtime.tool.ToolResult;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * A tool over <em>this host's</em> data: the admin users Yudao keeps, read through Yudao's own service.
 *
 * <p>This is the part that does not port for free. The runtime's sample CRM tools cross over untouched
 * because they read their own tables; a tool over the host's data must call a host service, and on
 * Yudao that means two things the RuoYi version never needed: the call runs under a tenant (Yudao's
 * data layer refuses without one — the spike's Y4), and the caller is carried on {@code ActingCaller}
 * for whatever row scope a deployment attaches.
 *
 * <p>The projection leaves out mobile and email. A projection is where that decision is legible, and
 * the admin console's red lines say identity fields are masked on the way out, not at the edge.
 *
 * 一个跑在**本宿主自己的**数据上的工具：芋道维护的管理端用户，经芋道自己的服务读。
 *
 * <p>**这一块不是免费的**。运行时的样例 CRM 工具原样过来，是因为它们读自己的表；跑在宿主数据上的工具必须调
 * 宿主服务，而在芋道上这意味着 RuoYi 版从不需要的两件事：调用要在**租户**下运行（芋道数据层没有租户就拒跑——
 * Spike 的 **Y4**），以及调用方经 {@code ActingCaller} 带上，供部署方挂接的任何行范围用。
 *
 * <p>投影**去掉了 mobile 与 email**。投影正是这个决定变得可读的地方，而管理端的红线说身份字段是**输出时掩码**、
 * 不是到边界才算。
 */
@Component
public class ListYudaoUsersTool implements AiTool {

    private final AdminUserApi adminUsers;
    private final OwnershipGuard guard;

    public ListYudaoUsersTool(AdminUserApi adminUsers, OwnershipGuard guard) {
        this.adminUsers = adminUsers;
        this.guard = guard;
    }

    @Override
    public String name() {
        return "list_yudao_users";
    }

    @Override
    public String description() {
        return "List the admin users this Yudao host keeps (read-only). Returns id, nickname and status"
                + " only; contact fields are not projected.";
    }

    @Override
    public String riskLevel() {
        return "R1";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(ToolParameter.optional("nickname", ToolParameter.STRING, "昵称模糊匹配"));
    }

    @Override
    public ToolResult execute(Map<String, Object> args, Principal principal) {
        guard.requireAction(principal, "YudaoUser", PermissionAuthorizer.ACTION_READ);
        Object raw = args.get("nickname");
        String nickname = raw == null ? "" : String.valueOf(raw);
        // Yudao's data layer needs a tenant in context; this reads the host's own answer and falls back
        // only where there is none.
        // 芋道的数据层要求上下文里有租户；这里读的是宿主自己的答案，只在没有的时候才回落。
        List<Map<String, Object>> rows = ActingCaller.as(principal.userId(),
                () -> TenantUtils.execute(YudaoCallerTenant.current(), () -> adminUsers.getUserListByNickname(nickname))).stream()
                .map(ListYudaoUsersTool::project)
                .toList();
        return ToolResult.ok(rows);
    }

    private static Map<String, Object> project(AdminUserRespDTO user) {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("id", user.getId());
        safe.put("nickname", user.getNickname());
        safe.put("status", user.getStatus());
        return safe;
    }
}
