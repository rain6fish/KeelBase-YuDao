// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.scope.DataScopeRules;
import cn.com.keelbase.runtime.scope.ScopeDescriptor;
import cn.com.keelbase.runtime.scope.ScopeLevel;
import cn.iocoder.yudao.module.system.dal.dataobject.permission.RoleDO;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.enums.permission.DataScopeEnum;
import cn.iocoder.yudao.module.system.service.dept.DeptService;
import cn.iocoder.yudao.module.system.service.permission.PermissionService;
import cn.iocoder.yudao.module.system.service.permission.RoleService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The row-range source, answered from Yudao's own data scope — the Yudao twin of the RuoYi host's
 * {@code RuoYiDataScopeRules}, and the bean that fills the runtime's {@code DataScopeRules} seam on this
 * host. Without it every host caller reached the runtime's row gate carrying the tier-A default, which
 * is keyed by the contract's two-value role vocabulary that this host collapses by user id.
 *
 * <p>It is a different question from {@link YudaoRowScope}'s, and both have to agree. That one answers
 * "does this caller range over every row", and the generated-module machine asks it; this one spells the
 * range itself, and the runtime's row gate asks it. Where they meet is the same caller: a caller held to
 * their own rows by the gate must not be ranged over every row by the query behind it, and the reverse
 * is worse. Both read the same table the same way — including the same answer when the read fails.
 *
 * <p><b>A department scope becomes an explicit set of departments, not a level that needs an
 * organization.</b> Yudao has departments and no such thing as an organization, while the runtime's
 * {@code own_dept} levels compare an organization and a department together. {@link ScopeLevel#CUSTOM_DEPT}
 * needs neither: it is a set of department ids, and a row is in range when its department is in the set.
 * The sets are built exactly the way the host builds them for its own data permission
 * ({@code PermissionServiceImpl#getDeptDataPermission}) — including that a custom scope always contains
 * the caller's own department — because the row gate and the host's own gate answering one question two
 * ways is the thing this seam exists to stop.
 *
 * 行范围的来源，答案取自芋道自己的数据范围——RuoYi 宿主那份 {@code RuoYiDataScopeRules} 的**芋道孪生**，
 * 也是在本宿主上填上运行时 `DataScopeRules` 那条缝的那颗 bean。没有它，每个宿主调用者到达运行时的行闸时带的
 * 都是**档 A 默认**，而那个默认按契约的两值角色词表取——本宿主恰恰是按用户 id 塌缩出那个角色的。
 *
 * <p>它与 {@link YudaoRowScope} 问的**不是同一个问题**，而两个答案必须一致。那个回答「**这位调用方是不是
 * 覆盖全部行**」，生成模块那台机器问它；这个把**范围本身**说出来，运行时的行闸问它。两者相遇在同一调用方身上：
 * 被行闸约束到本人行的人，不能被它身后的查询当作覆盖全部行——反过来更糟。两者读**同一张表、同一种读法**，
 * **包括读不出来时给出同一个答案**。
 *
 * <p><b>部门范围落成一个显式的部门集合，而不是一个需要「组织」的档位。</b> 芋道有部门、没有「组织」，
 * 而运行时的 `own_dept` 系列把**组织与部门合在一起比**。{@link ScopeLevel#CUSTOM_DEPT} 两者都不要：它是
 * 一个部门 id 的集合，行的部门落在集合里即在范围内。集合的构造方式与**宿主自己**的数据权限
 * （`PermissionServiceImpl#getDeptDataPermission`）**完全一致**——包括它那条「自定义范围总是包含调用方本人的
 * 部门」——因为**行闸与宿主自己的闸对同一个问题给出两个答案**，正是这条缝存在要制止的事。
 *
 * <p><b>Expression is one range, not a union; anything unrecognised narrows.</b> Yudao ORs its roles
 * into a union ({@code all}, {@code self}, a set of departments) and this seam spells one range, so the
 * widest is expressed and the narrower ones are dropped — that direction never shows more than the host
 * says. A caller with no roles, an unparseable id, a scope value this seam does not know, and a
 * department scope on a caller with no department all end at the caller's own rows.
 *
 * <p><b>表达一个范围、不是诸范围的并集；认不出的都收紧。</b> 芋道把诸角色 OR 成一个并集（`all`、`self`、
 * 一组部门），而这条缝只能表达**一个**范围，故表达最宽的、丢掉较窄的——那个方向**绝不**比宿主说的给得多。
 * 没有角色的调用方、解析不出的 id、本缝不认识的档位值、以及**没有部门**的调用方遇到部门档，全都落在本人行上。
 *
 * <p><b>An unanswerable read narrows; it never widens — and it does not force a tenant to make itself
 * answerable.</b> Yudao's reads are tenant-scoped and the governed tool path does not always carry a
 * tenant, so those reads can fail outright. Pinning a tenant here would make them succeed by answering
 * **under a tenant this seam guessed**, and a guessed read errs in only one direction: somebody with no
 * role in the tenant the caller actually belongs to, but a role under the guessed one, would be widened
 * past — which is the one thing a row gate must never do. So a fallback stays a fallback rather than
 * becoming an answer, which is the same discipline this host states where it resolves the tenant itself.
 * Pinning would also make this seam disagree with {@link YudaoRowScope}, which reads the same table under
 * whatever context it is given — two answers to one question on exactly the path where they meet. The
 * failure is therefore answered the way that one answers it: narrow to the caller's own rows, and say so
 * in the log rather than swallow it. Failing open would turn "we could not tell" into "sees everything".
 *
 * <p><b>读不出来只会收窄，永不放宽 —— 而且它不会去钉一个租户让自己读得出来。</b> 芋道的读法是**按租户**的，
 * 而受治理的工具路径**不总带租户**，故那些读**会整条失败**。在这里钉一个租户会让它们成功，代价是**按这条缝
 * 猜出来的一个租户**作答 —— 而**猜出来的读法只会朝一个方向错**：一个**在调用方真正所属的那个租户里没有角色、
 * 却恰好在猜的那个租户下有角色**的人，会被**凭猜放过** —— 而那正是行闸绝不能做的事。所以**回落是回落、不是
 * 答案**，与宿主自己解析租户的地方是同一条纪律。钉租户**也会**让这条缝与 {@link YudaoRowScope} 给出不同答案
 * ——后者在它拿到的上下文下读同一张表，而这正是两者相遇的那条路上「一个问题两个答案」。所以失败按**它**的方式
 * 回答：**收窄到调用方本人的行**，并且**记进日志**而不是静静吞掉。**失败即放行**会把「我们说不出来」变成
 * 「看得见全部」。
 */
@Component
@Slf4j
public class YudaoDataScopeRules extends DataScopeRules {

    private final PermissionService permissions;
    private final RoleService roles;
    private final AdminUserService users;
    private final DeptService depts;

    public YudaoDataScopeRules(PermissionService permissions, RoleService roles, AdminUserService users,
            DeptService depts) {
        super(Map.of());
        this.permissions = permissions;
        this.roles = roles;
        this.users = users;
        this.depts = depts;
    }

    /**
     * The range this caller ranges at, in the runtime's terms.
     *
     * 这位调用方的范围，用运行时的说法。
     */
    @Override
    public ScopeDescriptor forRole(Principal principal) {
        Long userId = asId(principal.userId());
        if (userId == null) {
            return ScopeDescriptor.of(ScopeLevel.OWN);
        }
        try {
            return rangeOf(userId);
        } catch (RuntimeException cannotRead) {
            log.warn("[forRole][调用方({}) 的角色范围读不出来，按最窄处理]", userId, cannotRead);
            return ScopeDescriptor.of(ScopeLevel.OWN);
        }
    }

    /**
     * A role string carries no caller, so it cannot be resolved here: nothing in this seam can say which
     * host role a contract role stands for — this host collapses several of them onto the contract's two
     * by user id. The tighter answer is the only safe one.
     *
     * 角色字符串**不携带调用方**，故这里解析不了：这条缝里没有东西能说契约角色对应哪个宿主角色——本宿主恰恰
     * 是按用户 id 把若干角色塌缩到契约那两个上的。**收紧**是唯一安全的答案。
     */
    @Override
    public ScopeDescriptor forRole(String role) {
        return ScopeDescriptor.of(ScopeLevel.OWN);
    }

    private ScopeDescriptor rangeOf(Long userId) {
        Set<Long> roleIds = permissions.getUserRoleIdListByUserId(userId);
        if (roleIds == null || roleIds.isEmpty()) {
            return ScopeDescriptor.of(ScopeLevel.OWN);
        }
        List<RoleDO> held = roles.getRoleList(roleIds);
        if (held == null || held.isEmpty()) {
            return ScopeDescriptor.of(ScopeLevel.OWN);
        }
        Set<Long> deptIds = new LinkedHashSet<>();
        Long deptId = null;
        boolean deptRead = false;
        for (RoleDO role : held) {
            Integer scope = role.getDataScope();
            if (scope == null) {
                continue;
            }
            if (DataScopeEnum.ALL.getScope().equals(scope)) {
                return ScopeDescriptor.of(ScopeLevel.ALL);
            }
            // The caller's own department is read once, and only if some role actually asks for it: the
            // common case is a caller whose roles say nothing about departments at all.
            // 调用方本人的部门**只读一次**，且只在真有角色需要它时才读：常见情形是诸角色根本不提部门。
            if (needsDept(scope) && !deptRead) {
                AdminUserDO user = users.getUser(userId);
                deptId = user == null ? null : user.getDeptId();
                deptRead = true;
            }
            if (DataScopeEnum.DEPT_CUSTOM.getScope().equals(scope)) {
                addAll(deptIds, role.getDataScopeDeptIds());
                addIfNotNull(deptIds, deptId);
            } else if (DataScopeEnum.DEPT_ONLY.getScope().equals(scope)) {
                addIfNotNull(deptIds, deptId);
            } else if (DataScopeEnum.DEPT_AND_CHILD.getScope().equals(scope)) {
                if (deptId != null) {
                    Set<Long> subtree = depts.getChildDeptIdListFromCache(deptId);
                    if (subtree != null) {
                        deptIds.addAll(subtree);
                    }
                    deptIds.add(deptId);
                }
            } else if (DataScopeEnum.SELF.getScope().equals(scope)) {
                // The caller's own rows — already the fallback below, so there is nothing to add.
                // 调用方本人的行——那本就是下面的回落，故无需追加。
                continue;
            } else {
                // Loud rather than silent: an unrecognised scope is a fact about this host, and the only
                // safe reading of it is the tight one, which the fallback below gives.
                // **出声、不静默**：认不出的档位是关于本宿主的一件事实，而它唯一安全的读法是收紧的那个，回落给的就是。
                log.warn("[rangeOf][角色({}) 的范围值({}) 不认识，按最窄处理]", role.getCode(), scope);
            }
        }
        return deptIds.isEmpty() ? ScopeDescriptor.of(ScopeLevel.OWN) : ScopeDescriptor.customDept(deptIds);
    }

    private static boolean needsDept(Integer scope) {
        return DataScopeEnum.DEPT_CUSTOM.getScope().equals(scope)
                || DataScopeEnum.DEPT_ONLY.getScope().equals(scope)
                || DataScopeEnum.DEPT_AND_CHILD.getScope().equals(scope);
    }

    private static void addAll(Set<Long> into, Set<Long> from) {
        if (from != null) {
            into.addAll(from);
        }
    }

    private static void addIfNotNull(Set<Long> into, Long value) {
        if (value != null) {
            into.add(value);
        }
    }

    private static Long asId(String userId) {
        try {
            return Long.valueOf(userId);
        } catch (NumberFormatException notAnId) {
            return null;
        }
    }
}
