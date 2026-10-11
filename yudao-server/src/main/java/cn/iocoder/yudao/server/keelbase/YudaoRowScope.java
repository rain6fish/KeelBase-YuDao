// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.iocoder.yudao.module.system.dal.dataobject.permission.RoleDO;
import cn.iocoder.yudao.module.system.service.permission.PermissionService;
import cn.iocoder.yudao.module.system.service.permission.RoleService;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The caller's effective row scope, read from Yudao's own roles — the twin of the other host's
 * {@code RuoYiRowScope}, and written for the same reason: more than one layer asks, and the answers have
 * to agree.
 *
 * <p>The decision side asks whether a rule needs an ownership column; the data side asks whether a
 * generated module's query needs narrowing at all. A caller who may see every row through the gate must
 * not be shown a single row by the query — and the reverse is worse — so the reading is written once and
 * both call it. A second copy would look harmless and would be the thing that drifts.
 *
 * <p>The direction is Yudao's own, and it happens to be the same one: the host builds a condition per
 * role and ORs them, so the widest of the caller's roles is what applies, and its {@code DataScopeEnum}
 * numbers {@code ALL} as 1 and the narrower scopes above it — so the widest is the <b>smallest</b> value
 * among the roles. A caller whose roles carry no scope at all yields {@code null}, which the callers read
 * as "narrow to the caller" rather than "every row": a missing configuration must never be the reason
 * someone suddenly sees more.
 *
 * 调用方的有效行范围，读自芋道自己的角色——另一宿主那份 {@code RuoYiRowScope} 的孪生，**写的理由也相同**：
 * 不止一层在问，而两个答案必须一致。
 *
 * <p>**判决侧**问它，是为了判断一条规则要不要属主列；**数据侧**问它，是为了判断一个生成模块的查询要不要收束。
 * 一个经闸可以看全部行的调用者，不能被查询只给一行看——反过来更糟——所以这个读数**只写一次**，两边都调它。
 * 抄第二份看着无害，而那正是会漂移的那件事。
 *
 * <p>方向取芋道自己的，恰好与那台相同：宿主**每条角色造一个条件再 OR 起来**，所以起作用的是诸角色中**最宽**的
 * 那个；而它的 `DataScopeEnum` 把 `ALL` 编成 1、越窄的数越大——故最宽就是诸值中**最小**的那个。诸角色都不带
 * 范围的调用方得到 `null`，调用方把它读作「**收束到本人**」而不是「全部行」：**缺配置绝不能成为某人忽然看得
 * 更多的理由**。
 */
@Component
@Slf4j
public class YudaoRowScope {

    /** {@code system_role.data_scope} 1, i.e. {@code DataScopeEnum.ALL}: every row. / 全部数据权限。 */
    public static final String EVERY_ROW = "1";

    private final PermissionService permissions;
    private final RoleService roles;

    public YudaoRowScope(PermissionService permissions, RoleService roles) {
        this.permissions = permissions;
        this.roles = roles;
    }

    /**
     * The widest scope among the caller's roles, or {@code null} when none of them carries one.
     *
     * <p><b>An unanswerable read narrows; it never widens.</b> Yudao's own reads are tenant-scoped, and
     * the governed tool path does not always carry a tenant — that is the other decision's unfinished
     * half — so those reads can fail outright. When they do, this answers {@code null}, which the
     * callers read as "hold this caller to their own rows": exactly what <em>every</em> caller was held
     * to before this read existed, and the direction the paragraph above requires. Failing open would
     * turn "we could not tell" into "sees everything".
     *
     * <p>The failure is logged rather than swallowed silently, because a narrowing that happens for an
     * environmental reason is worth being able to see.
     *
     * 调用方诸角色中最宽的那个范围；没有任何角色带范围时为 {@code null}。
     *
     * <p>**读不出来只会收窄，永不放宽。** 芋道自己的读法是**按租户**的，而受治理的工具路径**不总带着租户**
     * ——那是另一条决定尚未落的半——故那些读**会整条失败**。失败时这里答 {@code null}，调用方把它读作
     * 「**把这个调用方约束到自己的行**」：正是这个读数存在之前**每一个**调用方所受的约束，也正是上一段要求的方向。
     * **失败即放行**会把「我们说不出来」变成「看得见全部」。
     *
     * <p>失败**记日志**而不是静静吞掉：因环境原因发生的收窄，值得能被看见。
     */
    public String widestFor(Long userId) {
        Set<Long> roleIds;
        try {
            roleIds = permissions.getUserRoleIdListByUserId(userId);
            if (roleIds == null || roleIds.isEmpty()) {
                return null;
            }
            String widest = null;
            for (RoleDO role : roles.getRoleList(roleIds)) {
                Integer scope = role.getDataScope();
                if (scope == null) {
                    continue;
                }
                String value = String.valueOf(scope);
                if (widest == null || value.compareTo(widest) < 0) {
                    widest = value;
                }
            }
            return widest;
        } catch (RuntimeException cannotRead) {
            log.warn("[widestFor][调用方({}) 的角色范围读不出来，按最窄处理]", userId, cannotRead);
            return null;
        }
    }
}
