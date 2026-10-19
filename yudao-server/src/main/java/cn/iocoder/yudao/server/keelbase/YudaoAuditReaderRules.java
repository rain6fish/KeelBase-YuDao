// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.authz.AuthorizationRules;
import cn.com.keelbase.runtime.authz.GovernanceReadSubjects;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.identity.Principal;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.system.dal.dataobject.permission.RoleDO;
import cn.iocoder.yudao.module.system.service.permission.PermissionService;
import cn.iocoder.yudao.module.system.service.permission.RoleService;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 「只读审计读者」在宿主侧的规则源——把芋道的角色表接到运行时的档 B 缝上。
 *
 * <p>本类只回答一件事：一个调用方**是否持有**宿主自己的 `audit_reader` 角色。持有则返回六条
 * `read` 规则（审计链、操作审计、工具清单、治理策略、副作用台账、确认记录），**一条写规则都没有**；
 * 不持有则原样交回档 A（管理员拿通配、普通用户拿 Customer / FollowUp）。这份清单就是该读者的全部射程，
 * 其余一律不授予——「不能写、不能审批、不能执行」是**按构造**成立的，不是靠另一道守卫。
 *
 * <p>审计读者**不合并**普通用户的规则：它读的是治理记录，不是应用的用户。故一个同时持 `common` 与
 * `audit_reader` 的账号在本规则源下仍只有读面——这正是把两个身份分开的意义。若要一个既能办案又能审计的
 * 账号，那是两个账号，不是一条更宽的规则。
 *
 * <p>宿主自己的角色表是唯一的权威：规则不按请求头、不按主体自述，只按 `system_user_role` →
 * `system_role.code` 读。读不出来时**按非审计读者处理**（失败即收窄），并记一条日志。
 *
 * <p>英文：
 *
 * <p>The host-side rule source for the read-only audit reader — it connects Yudao's role table to the
 * runtime's tier-B seam.
 *
 * <p>This class answers one question: does the caller hold the host's own `audit_reader` role? If so it
 * returns six `read` rules (the audit chain, the operations audit, the tool inventory, the governance
 * policy, the side-effect ledger and the confirmation records) and **not one write rule**. If not, it
 * hands the call back to tier A unchanged (administrators get the wildcard, ordinary users get Customer /
 * FollowUp). That list is the whole reach of the reader; nothing else is granted, so "cannot write,
 * cannot approve, cannot execute" holds **by construction** rather than by a second guard.
 *
 * <p>The audit reader does **not** merge the ordinary user's rules: it reads the governance record, it is
 * not a user of the application. An account holding both `common` and `audit_reader` therefore still has
 * only the read faces under this source — which is the point of separating the two identities. An account
 * that both operates and audits is two accounts, not a wider rule.
 *
 * <p>The host's own role table is the only authority: the rules are read from `system_user_role` →
 * `system_role.code`, never from a request header or the subject's own claim. When the read fails the
 * caller is **not** treated as an audit reader (fail closed), and the failure is logged.
 */
@Component
@Slf4j
public class YudaoAuditReaderRules extends AuthorizationRules {

    /** 宿主审计读者的角色编码（`system_role.code`）。/ The host's audit-reader role code (`system_role.code`). */
    public static final String AUDIT_READER_ROLE_CODE = "audit_reader";

    /**
     * 审计读者的全部授予：六个治理读面，每条只含 `read`、都不带属主列（即全局只读）。
     * 主体名取自运行时的 `GovernanceReadSubjects`，与控制器实际要求的字符串同源。
     *
     * <p>Every grant the audit reader holds: six governance read faces, each `read` only and with no
     * ownership column (that is, global read). The subjects come from the runtime's
     * `GovernanceReadSubjects`, the same source the controllers ask about.
     */
    private static final List<Rule> AUDITOR_RULES = List.of(
            new Rule(GovernanceReadSubjects.AUDIT_LOG, PermissionAuthorizer.ACTION_READ, null),
            new Rule(GovernanceReadSubjects.OPERATION_AUDIT, PermissionAuthorizer.ACTION_READ, null),
            new Rule(GovernanceReadSubjects.TOOL_INVENTORY, PermissionAuthorizer.ACTION_READ, null),
            new Rule(GovernanceReadSubjects.GOVERNANCE_POLICY, PermissionAuthorizer.ACTION_READ, null),
            new Rule(GovernanceReadSubjects.TOOL_EFFECT, PermissionAuthorizer.ACTION_READ, null),
            new Rule(GovernanceReadSubjects.CONFIRMATION, PermissionAuthorizer.ACTION_READ, null));

    /** 芋道的角色状态 0 = 启用；禁用角色的授予不得生效。 / Yudao role status 0 means enabled. */
    private static final Integer ROLE_ENABLED = 0;

    private final PermissionService permissions;
    private final RoleService roles;

    public YudaoAuditReaderRules(PermissionService permissions, RoleService roles) {
        this.permissions = permissions;
        this.roles = roles;
    }

    /**
     * 持有 `audit_reader` 的非管理员拿到只有读的六条；其余人走档 A 的默认规则。
     *
     * <p>A non-administrator holding `audit_reader` gets the six read-only rules; everyone else gets
     * tier A's defaults.
     */
    @Override
    public List<Rule> rulesFor(Principal principal) {
        if (principal == null) {
            return List.of();
        }
        if (!principal.isManager() && holdsAuditReaderRole(principal.userId())) {
            return AUDITOR_RULES;
        }
        return super.rulesFor(principal);
    }

    /**
     * 调用方是否持有启用的 `audit_reader` 角色——运行时的执行入口守卫也问这一问。
     *
     * <p>英文：
     *
     * <p>Whether the caller holds an enabled `audit_reader` role — the runtime execution-entry guard
     * asks the same question.
     */
    public boolean isAuditReader(String subject) {
        return holdsAuditReaderRole(subject);
    }

    /**
     * 调用方是否持有启用的 `audit_reader` 角色。读不出来即「不持有」——缺一次角色读只会收窄。
     *
     * <p>Whether the caller holds an enabled `audit_reader` role. An unreadable answer is "does not
     * hold": a failed role read narrows, never widens.
     */
    private boolean holdsAuditReaderRole(String subject) {
        Long userId;
        try {
            userId = Long.valueOf(subject);
        } catch (RuntimeException notAnId) {
            return false;
        }
        try {
            // 芋道的数据层要求上下文里有租户；KeelBase 的路径不总带租户，故与身份接缝用同一种读法：
            // 有就按宿主的读数、没有才回落到种子租户。读出来的仍是**该租户**的角色，不会跨租户。
            // Yudao's data layer needs a tenant in context; a KeelBase path does not always carry one, so
            // this reads the way the identity seam does: the host's tenant when there is one, the seed
            // tenant as the declared fallback. What it reads is still **that tenant's** roles, never
            // another tenant's.
            return TenantUtils.execute(YudaoCallerTenant.current(), () -> {
                Set<Long> roleIds = permissions.getUserRoleIdListByUserId(userId);
                if (roleIds == null || roleIds.isEmpty()) {
                    return false;
                }
                for (RoleDO role : roles.getRoleList(roleIds)) {
                    if (role != null
                            && AUDIT_READER_ROLE_CODE.equals(role.getCode())
                            && ROLE_ENABLED.equals(role.getStatus())) {
                        return true;
                    }
                }
                return false;
            });
        } catch (RuntimeException cannotRead) {
            log.warn("[rulesFor][调用方({}) 的角色读不出来，按非审计读者处理]", userId, cannotRead);
            return false;
        }
    }
}
