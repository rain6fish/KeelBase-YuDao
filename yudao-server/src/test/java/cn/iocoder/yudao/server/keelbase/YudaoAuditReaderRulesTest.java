// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.com.keelbase.runtime.authz.AuthorizationRules.Rule;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.identity.Principal;
import cn.iocoder.yudao.module.system.dal.dataobject.permission.RoleDO;
import cn.iocoder.yudao.module.system.service.permission.PermissionService;
import cn.iocoder.yudao.module.system.service.permission.RoleService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 审计读者这一档的两个出口：**给不给那六条只读**，以及**读不出来时算不算**。
 *
 * <p>后者是这条规则最要紧的一半：角色读不出来时答案是「不是审计读者」，也就是回到档 A 的默认，
 * 而不是把六个治理读面交出去。启用状态同样如此——一个被禁用的 `audit_reader` 角色不授予任何东西。
 *
 * <p>Both exits of the audit-reader tier: whether the six read-only rules are granted, and what a read
 * that fails counts as. The second is the load-bearing half: an unreadable role read answers "not an
 * audit reader" — tier A's defaults — rather than handing over six governance read faces. The enabled
 * status works the same way: a disabled `audit_reader` role grants nothing.
 */
class YudaoAuditReaderRulesTest {

    private PermissionService permissions;
    private RoleService roles;
    private YudaoAuditReaderRules rules;

    @BeforeEach
    void setUp() {
        permissions = mock(PermissionService.class);
        roles = mock(RoleService.class);
        rules = new YudaoAuditReaderRules(permissions, roles);
    }

    @Test
    void noPrincipalGetsNoRules() {
        assertTrue(rules.rulesFor((Principal) null).isEmpty());
    }

    @Test
    void anAuditReaderGetsSixReadOnlyRules() {
        holdAuditReader("42");

        List<Rule> granted = rules.rulesFor(new Principal("42", Principal.ROLE_USER));

        assertEquals(6, granted.size());
        assertTrue(granted.stream().allMatch(rule -> PermissionAuthorizer.ACTION_READ.equals(rule.action())),
                "the audit reader reads; it does not write or decide");
        assertTrue(granted.stream().allMatch(rule -> rule.ownerField() == null),
                "no ownership column means global read, which is the point of the role");
    }

    @Test
    void anAdministratorDoesNotTakeTheAuditReaderTier() {
        holdAuditReader("42");
        List<Rule> managerRules = rules.rulesFor(new Principal("99", Principal.ROLE_ADMIN));

        List<Rule> granted = rules.rulesFor(new Principal("42", Principal.ROLE_ADMIN));

        assertEquals(managerRules, granted, "an administrator stays on tier A's defaults");
    }

    @Test
    void aCallerWithoutTheRoleFallsBackToTheDefaults() {
        when(permissions.getUserRoleIdListByUserId(42L)).thenReturn(Set.of(1L));
        when(roles.getRoleList(Set.of(1L))).thenReturn(List.of(role("crm_owner", 0)));

        List<Rule> granted = rules.rulesFor(new Principal("42", Principal.ROLE_USER));

        assertEquals(rules.rulesFor(new Principal("7", Principal.ROLE_USER)), granted,
                "without the role, the answer is the same as anyone else's");
    }

    @Test
    void aDisabledAuditReaderRoleGrantsNothing() {
        when(permissions.getUserRoleIdListByUserId(42L)).thenReturn(Set.of(1L));
        when(roles.getRoleList(Set.of(1L)))
                .thenReturn(List.of(role(YudaoAuditReaderRules.AUDIT_READER_ROLE_CODE, 1)));

        assertFalse(rules.isAuditReader("42"), "a disabled role is not a role");
    }

    @Test
    void anEnabledAuditReaderRoleIsRecognised() {
        holdAuditReader("42");

        assertTrue(rules.isAuditReader("42"));
    }

    @Test
    void aSubjectThatIsNotAnIdIsNotAnAuditReader() {
        assertFalse(rules.isAuditReader("alex"));
        assertFalse(rules.isAuditReader(null));
    }

    @Test
    void aCallerWithNoRolesIsNotAnAuditReader() {
        when(permissions.getUserRoleIdListByUserId(42L)).thenReturn(Set.of());

        assertFalse(rules.isAuditReader("42"));
    }

    @Test
    void aRoleReadThatFailsNarrowsToNotAnAuditReader() {
        when(permissions.getUserRoleIdListByUserId(42L))
                .thenThrow(new IllegalStateException("no tenant in context"));

        assertFalse(rules.isAuditReader("42"), "cannot tell may only become 'no', never 'yes'");
    }

    @Test
    void theRoleCodeIsTheHostsOwn() {
        assertEquals("audit_reader", YudaoAuditReaderRules.AUDIT_READER_ROLE_CODE);
    }

    private void holdAuditReader(String subject) {
        long userId = Long.parseLong(subject);
        when(permissions.getUserRoleIdListByUserId(userId)).thenReturn(Set.of(9L));
        when(roles.getRoleList(Set.of(9L)))
                .thenReturn(List.of(role(YudaoAuditReaderRules.AUDIT_READER_ROLE_CODE, 0)));
    }

    private static RoleDO role(String code, int status) {
        RoleDO role = new RoleDO();
        role.setCode(code);
        role.setStatus(status);
        return role;
    }
}
