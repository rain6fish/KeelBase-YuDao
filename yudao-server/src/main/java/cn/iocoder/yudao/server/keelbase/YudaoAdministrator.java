// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.iocoder.yudao.module.system.service.permission.PermissionService;
import cn.iocoder.yudao.module.system.service.permission.RoleService;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Whether the caller is an administrator here, answered in one place — because two layers asked it
 * separately, each by comparing the user id to the seed administrator's, and two places answering one
 * question is how they drift apart.
 *
 * <p>The authority is the host's own: a caller is an administrator when the roles it holds include
 * Yudao's super administrator role, which is Yudao's own definition and not a mapping this adapter
 * invents. Reading it is what the row scope next door already does for the range half; this is the same
 * kind of read for the same kind of reason.
 *
 * <p><b>The seed id stays as a fallback, and it is a fallback.</b> Both callers used to treat user 1 as
 * the administrator and nothing else; that stays true, because the governed tool path does not always
 * carry a tenant, and the reads below are tenant-scoped — the row scope on that same path already throws
 * on exactly this. So an unanswerable read does not make the seed administrator a stranger to its own
 * exemption.
 *
 * <p><b>An unanswerable read narrows.</b> When the roles cannot be read, the answer is "not an
 * administrator": a caller keeps being held to their own rows, and the identity seam falls back to the
 * contract's {@code user}. Getting it wrong in the other direction would hand out the exemption.
 *
 * 这个调用方在本宿主算不算管理员——**只在一个地方回答**，因为过去有两层分别问它，各自拿用户 id 与种子管理员比对；
 * 一个问题两个地方答，正是它们会分头漂走的方式。
 *
 * <p>**权威取自宿主自己**：调用方持有的角色里有芋道的超级管理员角色，就是管理员——那是**芋道自己的**定义，不是本适配器
 * 编出来的映射。隔壁那条行范围已经为**范围**那半做了同类读法；这是为同一个理由做的同类读法。
 *
 * <p>**种子 id 留下作回落，而且它是回落。** 两处调用方过去都把用户 1 当管理员、再无别的；这一条留着，因为受治理的
 * 工具路径**不总带租户**，而下面的读法是**按租户**的——同一条路上的行范围读法已经正是在这一点上抛。故**读不出来，
 * 不该让种子管理员在自己的豁免面前变成陌生人**。
 *
 * <p>**读不出来就收窄。** 角色读不了时，答案是「**不是**管理员」：调用方继续被约束到自己的行，身份那条缝回落成契约
 * 的 {@code user}。**反过来答错，就是把豁免发出去。**
 */
@Component
@Slf4j
public class YudaoAdministrator {

    /**
     * The declared fallback, kept from both callers. / 声明式回落，两处调用方原本都是这个。
     */
    private static final long SEED_ADMIN_ID = 1L;

    private final PermissionService permissions;
    private final RoleService roles;

    public YudaoAdministrator(PermissionService permissions, RoleService roles) {
        this.permissions = permissions;
        this.roles = roles;
    }

    /** Whether this caller holds Yudao's super administrator role, or is the seed administrator. */
    public boolean isAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        if (userId == SEED_ADMIN_ID) {
            return true;
        }
        try {
            Set<Long> roleIds = permissions.getUserRoleIdListByUserId(userId);
            return roleIds != null && !roleIds.isEmpty() && roles.hasAnySuperAdmin(roleIds);
        } catch (RuntimeException cannotRead) {
            log.warn("[isAdmin][调用方({}) 的角色读不出来，按非管理员处理]", userId, cannotRead);
            return false;
        }
    }
}
