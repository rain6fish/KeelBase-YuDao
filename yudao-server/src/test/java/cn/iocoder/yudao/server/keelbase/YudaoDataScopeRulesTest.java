// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.scope.ScopeDescriptor;
import cn.com.keelbase.runtime.scope.ScopeLevel;
import cn.iocoder.yudao.module.system.dal.dataobject.permission.RoleDO;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.enums.permission.DataScopeEnum;
import cn.iocoder.yudao.module.system.service.dept.DeptService;
import cn.iocoder.yudao.module.system.service.permission.PermissionService;
import cn.iocoder.yudao.module.system.service.permission.RoleService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 行范围的每一个出口：宽的那些只在宿主明说时出现，其余一律收窄到调用方本人的行。
 *
 * <p>这类读法只朝一个方向错才有害 —— **放宽**。故这里的用例大多在证「不会放宽」：解析不出的 id、
 * 没有角色、角色表读空、认不出的档位、没有部门的部门档、以及**读整条失败**，全部落在本人行上；而
 * {@code ALL}、部门档、部门及以下档各有一条正向用例，证明宽的范围确实按宿主的值表达出来。
 *
 * <p>Every exit of the row range: the wide ones appear only when the host says so, everything else
 * narrows to the caller's own rows. Only one direction of error is harmful here — widening — so most
 * of these cases prove it does not widen: an unparseable id, no roles, an empty role list, an
 * unrecognised scope, a department scope without a department, and a read that fails outright all end
 * at the caller's own rows. The positive cases (all rows, a department, a department subtree) prove
 * the wide ranges are expressed when the host does say so.
 */
class YudaoDataScopeRulesTest {

    private static final String CALLER = "1";
    private static final long CALLER_ID = 1L;

    private PermissionService permissions;
    private RoleService roles;
    private AdminUserService users;
    private DeptService depts;
    private YudaoDataScopeRules rules;

    @BeforeEach
    void setUp() {
        permissions = mock(PermissionService.class);
        roles = mock(RoleService.class);
        users = mock(AdminUserService.class);
        depts = mock(DeptService.class);
        rules = new YudaoDataScopeRules(permissions, roles, users, depts);
        when(permissions.getUserRoleIdListByUserId(CALLER_ID)).thenReturn(Set.of(100L));
    }

    @Test
    void anUnparseableIdNarrowsToTheCallersOwnRows() {
        ScopeDescriptor range = rules.forRole(new Principal("alex", Principal.ROLE_USER));

        assertEquals(ScopeLevel.OWN, range.level());
    }

    @Test
    void aCallerWithNoRolesStaysOnOwnRows() {
        when(permissions.getUserRoleIdListByUserId(CALLER_ID)).thenReturn(Set.of());

        assertEquals(ScopeLevel.OWN, rules.forRole(principal()).level());
    }

    @Test
    void aRoleListThatReadsEmptyStaysOnOwnRows() {
        when(roles.getRoleList(Set.of(100L))).thenReturn(List.of());

        assertEquals(ScopeLevel.OWN, rules.forRole(principal()).level());
    }

    @Test
    void allRowsAreExpressedOnlyWhenARoleSaysAll() {
        when(roles.getRoleList(Set.of(100L))).thenReturn(List.of(role(DataScopeEnum.ALL)));

        assertEquals(ScopeLevel.ALL, rules.forRole(principal()).level());
    }

    @Test
    void aDepartmentScopeBecomesThatOneDepartment() {
        when(roles.getRoleList(Set.of(100L))).thenReturn(List.of(role(DataScopeEnum.DEPT_ONLY)));
        when(users.getUser(CALLER_ID)).thenReturn(userInDept(10L));

        ScopeDescriptor range = rules.forRole(principal());

        assertEquals(ScopeLevel.CUSTOM_DEPT, range.level());
        assertEquals(Set.of(10L), range.deptIds());
    }

    @Test
    void aCustomScopeCarriesTheRolesDepartmentsAndTheCallersOwn() {
        RoleDO custom = role(DataScopeEnum.DEPT_CUSTOM);
        custom.setDataScopeDeptIds(Set.of(7L, 8L));
        when(roles.getRoleList(Set.of(100L))).thenReturn(List.of(custom));
        when(users.getUser(CALLER_ID)).thenReturn(userInDept(10L));

        ScopeDescriptor range = rules.forRole(principal());

        // 自定义范围总是包含调用方本人的部门 —— 与宿主自己 getDeptDataPermission 的构造一致。
        // A custom scope always contains the caller's own department, matching the host's own builder.
        assertEquals(Set.of(7L, 8L, 10L), range.deptIds());
    }

    @Test
    void aDepartmentAndChildScopeExpandsToTheSubtreePlusItself() {
        when(roles.getRoleList(Set.of(100L))).thenReturn(List.of(role(DataScopeEnum.DEPT_AND_CHILD)));
        when(users.getUser(CALLER_ID)).thenReturn(userInDept(10L));
        when(depts.getChildDeptIdListFromCache(10L)).thenReturn(Set.of(11L, 12L));

        ScopeDescriptor range = rules.forRole(principal());

        assertEquals(Set.of(10L, 11L, 12L), range.deptIds());
    }

    @Test
    void selfOnlyStaysOnOwnRows() {
        when(roles.getRoleList(Set.of(100L))).thenReturn(List.of(role(DataScopeEnum.SELF)));

        assertEquals(ScopeLevel.OWN, rules.forRole(principal()).level());
    }

    @Test
    void anUnrecognisedScopeNarrows() {
        when(roles.getRoleList(Set.of(100L))).thenReturn(List.of(role(99)));

        assertEquals(ScopeLevel.OWN, rules.forRole(principal()).level());
    }

    @Test
    void aDepartmentScopeWithoutADepartmentStaysOnOwnRows() {
        when(roles.getRoleList(Set.of(100L))).thenReturn(List.of(role(DataScopeEnum.DEPT_ONLY)));
        when(users.getUser(CALLER_ID)).thenReturn(userInDept(null));

        assertEquals(ScopeLevel.OWN, rules.forRole(principal()).level());
    }

    @Test
    void aReadThatFailsNarrowsRatherThanWidening() {
        when(permissions.getUserRoleIdListByUserId(CALLER_ID))
                .thenThrow(new IllegalStateException("no tenant in context"));

        ScopeDescriptor range = rules.forRole(principal());

        // 「读不出来」只能变成「看本人的行」；失败即放行会把它变成「看得见全部」。
        // "Cannot tell" may only become "own rows"; failing open would turn it into "sees everything".
        assertEquals(ScopeLevel.OWN, range.level());
    }

    @Test
    void theWidestRoleWinsRegardlessOfOrder() {
        RoleDO narrow = role(DataScopeEnum.SELF);
        RoleDO wide = role(DataScopeEnum.ALL);
        when(roles.getRoleList(Set.of(100L))).thenReturn(List.of(narrow, wide));

        assertEquals(ScopeLevel.ALL, rules.forRole(principal()).level());
    }

    @Test
    void aContractRoleStringCannotWidenAnything() {
        ScopeDescriptor range = rules.forRole(Principal.ROLE_ADMIN);

        assertEquals(ScopeLevel.OWN, range.level());
        assertTrue(range.deptIds().isEmpty());
    }

    private static Principal principal() {
        return new Principal(CALLER, Principal.ROLE_USER);
    }

    private static RoleDO role(DataScopeEnum scope) {
        return role(scope.getScope());
    }

    private static RoleDO role(int scope) {
        RoleDO role = new RoleDO();
        role.setDataScope(scope);
        role.setCode("role-" + scope);
        return role;
    }

    private static AdminUserDO userInDept(Long deptId) {
        AdminUserDO user = new AdminUserDO();
        user.setDeptId(deptId);
        return user;
    }
}
