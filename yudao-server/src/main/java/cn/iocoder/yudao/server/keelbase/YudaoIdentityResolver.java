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
 *       role vocabulary, so a caller maps to {@code admin} or {@code user} and nothing finer. <b>Which
 *       one</b> is no longer decided here by comparing ids: it is {@link YudaoAdministrator}'s answer, so
 *       the identity seam and the module face give the same one. A finer mapping than two would still be a
 *       decision about Yudao's roles, not a translation.</li>
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
 *   <li><b>业务角色塌缩到契约的两个上。</b>芋道的 roleKey 不是冻结的角色词表，故调用方只映到 {@code admin}
 *       或 {@code user}、没有更细的。**映哪一个**不再由这里比对 id 来决定：那是 {@link YudaoAdministrator}
 *       的答案，于是**身份这条缝与模块面给出同一个**。比两个更细的映射，仍是对芋道角色的一个决定、不是翻译。</li>
 * </ul>
 */
@Component
public class YudaoIdentityResolver implements IdentityResolver {

    /**
     * Which tenant a host-service call runs under is {@link YudaoCallerTenant}'s to answer now — the
     * tenant the host authenticated the caller under, read from the framework's own context, with the
     * seed tenant as a <b>declared fallback</b> for the paths that carry none. This adapter used to fix
     * it at the seed tenant itself and record the decision as outstanding; the decision has since been
     * made, and it was made the same way the caller id next door is read.
     *
     * 宿主服务调用运行在哪个租户下，现在由 {@link YudaoCallerTenant} 回答——宿主据以认证调用方的那个租户，
     * 读自框架自己的上下文；而**种子租户**是不带上文的那几条路的**声明式回落**。本适配器过去自己把它钉在种子
     * 租户上、并把该决定记为未答；那个决定后来做了，而且做的方式与隔壁读 caller id 的方式相同。
     */
    private final AdminUserApi adminUsers;
    private final YudaoAdministrator administrators;

    public YudaoIdentityResolver(AdminUserApi adminUsers, YudaoAdministrator administrators) {
        this.adminUsers = adminUsers;
        this.administrators = administrators;
    }

    @Override
    public Principal resolve(IdentityEvidence evidence) {
        String subject = evidence.attribute(IdentityEvidence.SUBJECT);
        Long userId = asId(subject);
        AdminUserRespDTO user = null;
        if (userId != null) {
            try {
                // Yudao's data layer needs a tenant in context; this reads the host's own answer and
                // falls back only where there is none.
                // 芋道的数据层要求上下文里有租户；这里读的是**宿主自己的答案**，只在没有的时候才回落。
                user = TenantUtils.execute(YudaoCallerTenant.current(), () -> adminUsers.getUser(userId));
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
        String role = administrators.isAdmin(userId) ? Principal.ROLE_ADMIN : Principal.ROLE_USER;
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
