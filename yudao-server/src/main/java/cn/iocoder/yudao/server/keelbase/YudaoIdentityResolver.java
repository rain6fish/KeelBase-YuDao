// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.identity.IdentityEvidence;
import cn.com.keelbase.runtime.identity.IdentityResolver;
import cn.com.keelbase.runtime.identity.Principal;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Answers "who is that person here" from Yudao's own admin user table.
 *
 * <p>The runtime asks only for the verified subject; this adapter turns it into the local identity the
 * trust loop is scoped to. Yudao's directory is the authority: the caller cannot state a role, and
 * none is taken from the request. Two simplifications are declared rather than papered over, both the
 * same shape as the RuoYi adapter's, because they are decisions about this host's vocabulary and not
 * mechanical translations:
 *
 * <ul>
 *   <li><b>Organization scope is absent.</b> {@code Principal} is built without an org: Yudao has
 *       departments and tenants, and manufacturing either into the contract's org scope would widen
 *       row visibility on a guess.</li>
 *   <li><b>Business roles collapse onto the contract's two.</b> Yudao's role keys are not the frozen
 *       role vocabulary, so the seed super administrator (id 1) maps to {@code admin} and every other
 *       user to {@code user}. A finer mapping is a decision about Yudao's roles, not a translation.</li>
 * </ul>
 *
 * 从芋道自己的管理端用户表回答「这个人在本地是谁」。
 *
 * <p>运行时只要那个已验证的主体；本适配器把它变成信任闭环所界定的本地身份。芋道的名录就是权威：调用方
 * 不能自述角色，也不从请求里取角色。两处简化是**声明出来的**、不是糊过去的，且都与 RuoYi 适配器同形——
 * 因为它们是关于**这个宿主的词表**的决定，不是机械翻译：
 *
 * <ul>
 *   <li><b>组织范围缺失。</b>{@code Principal} 不带组织：芋道有部门、也有租户，把任一个合成契约的组织范围
 *       等于凭猜测放宽行可见性。</li>
 *   <li><b>业务角色塌缩到契约的两个上。</b>芋道的 roleKey 不是冻结的角色词表，故种子超级管理员（id 1）映到
 *       {@code admin}、其余用户映到 {@code user}。更细的映射是对芋道角色的一个决定，不是翻译。</li>
 * </ul>
 */
@Component
public class YudaoIdentityResolver implements IdentityResolver {

    /** Yudao's seed super administrator; the counterpart of RuoYi's {@code user.isAdmin()}. */
    private static final long SEED_ADMIN_ID = 1L;

    /**
     * The tenant the directory lookup runs under. Yudao's tenant interceptor adds a {@code tenant_id}
     * predicate to every statement and refuses to run without a tenant in context, and a KeelBase
     * request arrives with none — the runtime's contract has no tenant concept. So the adapter must
     * establish one. <b>Which</b> tenant a KeelBase caller belongs to is a decision the runtime cannot
     * make and this host has never been asked to make; the spike fixes it at the seed tenant to get
     * past the wall, and records the decision as outstanding rather than answered.
     *
     * 目录查询所运行的租户。芋道的租户拦截器给每条语句加 {@code tenant_id} 谓词，且上下文中没有租户就拒跑；
     * 而 KeelBase 请求到达时没有租户——运行时契约里根本没有租户概念。故适配器必须自己建立。**哪个**租户，
     * 运行时决定不了，这个宿主也从没被问过；Spike 把它钉在种子租户上以越过这堵墙，并把该决定记为**未答**而非已答。
     */
    private static final long SPIKE_TENANT_ID = 1L;

    private final AdminUserApi adminUsers;

    public YudaoIdentityResolver(AdminUserApi adminUsers) {
        this.adminUsers = adminUsers;
    }

    @Override
    public Principal resolve(IdentityEvidence evidence) {
        String subject = evidence.attribute(IdentityEvidence.SUBJECT);
        Long userId = asId(subject);
        AdminUserRespDTO user = null;
        if (userId != null) {
            try {
                // Yudao's data layer needs a tenant in context; a KeelBase request brings none.
                // 芋道的数据层要求上下文里有租户；KeelBase 请求不带。
                user = TenantUtils.execute(SPIKE_TENANT_ID, () -> adminUsers.getUser(userId));
            } catch (RuntimeException lookupFailed) {
                user = null;
            } catch (Exception unexpected) {
                user = null;
            }
        }
        if (user == null) {
            // Authenticated by the host, unknown to its directory: refuse rather than admit an
            // identity the runtime cannot scope.
            // 宿主认证通过、但它的名录里没有这个人：拒绝，而不是放进一个运行时收束不了的身份。
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "the authenticated caller is not a user here");
        }
        String role = userId == SEED_ADMIN_ID ? Principal.ROLE_ADMIN : Principal.ROLE_USER;
        return new Principal(String.valueOf(user.getId()), role);
    }

    private static Long asId(String subject) {
        if (subject == null) {
            return null;
        }
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException notAnId) {
            return null;
        }
    }
}
