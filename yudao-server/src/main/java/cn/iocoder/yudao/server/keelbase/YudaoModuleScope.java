// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import java.util.ArrayList;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * The machine a generated module's aspects share — the Yudao twin of the RuoYi host's
 * {@code GeneratedModuleScope}, with the row scope now read from this host's own roles.
 *
 * <p>That read used to be a <b>declared</b> narrowing: the RuoYi machine asked its role table whether a
 * caller ranges over every row, and this one had no such read, so a caller was left alone only when it
 * was the seed administrator. The read exists now ({@link YudaoRowScope}) and both hosts answer that
 * question the same way. The seed-administrator exemption stays, as the RuoYi side also keeps it: it
 * does not depend on the role table being answerable.
 *
 * <p>Who counts as an administrator is {@link YudaoAdministrator}'s answer, the same one the identity seam
 * takes: the host's own roles decide it, so the module face and the identity face cannot end up with two
 * different ideas of who is exempt.
 *
 * 生成模块的切面共用的机器——RuoYi 宿主那份 {@code GeneratedModuleScope} 的芋道孪生；**行范围现在读自本宿主
 * 自己的角色**。
 *
 * <p>那个读法过去是一层**声明的收窄**：RuoYi 那台问自己的角色表「调用方是否覆盖全部行」，而这里没有这种读法，
 * 故只有**种子管理员**被放过。**它现在有了**（{@link YudaoRowScope}），于是两个宿主对同一个问题给出**同一种**回答。
 *
 * <p>**谁算管理员**由 {@link YudaoAdministrator} 回答，与身份那条缝取的是**同一个**答案：**宿主自己的角色**说了算，
 * 于是模块面与身份面**不会各自长出**一套「谁被豁免」。
 */
@Component
public class YudaoModuleScope {

    private final YudaoCallerId callers;
    private final YudaoRowScope scopes;
    private final YudaoAdministrator administrators;

    public YudaoModuleScope(YudaoCallerId callers, YudaoRowScope scopes, YudaoAdministrator administrators) {
        this.callers = callers;
        this.scopes = scopes;
        this.administrators = administrators;
    }

    /**
     * The caller a governed access must be held to, or {@code null} when nothing needs holding: no
     * caller named, the seed administrator, or a scope of every row — the same three that mean "leave
     * it alone" on both the read and the write side.
     *
     * <p>The third is the one that was missing: it is read from this host's own role table, so a caller
     * whose roles declare every row is left alone the way the RuoYi host leaves it alone, and everyone
     * else is still held to their own rows. A caller whose roles declare nothing lands on the enforcing
     * side — a missing scope narrows, it never widens.
     *
     * 一次受治理的访问必须被约束到的调用者；三种情况下为 {@code null}（即「不动」）：指不出调用者、种子管理员、
     * 或范围是**全部行**——读侧与写侧是同样三种。
     *
     * <p>第三条正是原先缺的那条：它读自**本宿主自己的角色表**，故诸角色声明「全部行」的调用方被放过，**与 RuoYi
     * 宿主放过它的方式相同**；其余人仍被约束到自己的行。诸角色**什么都没声明**的调用方落在**要收束**的那一侧——
     * **缺范围只会收窄，永不放宽**。
     */
    public Long callerToEnforce() {
        Long caller = callers.current();
        if (caller == null || administrators.isAdmin(caller)) {
            return null;
        }
        return YudaoRowScope.EVERY_ROW.equals(scopes.widestFor(caller)) ? null : caller;
    }

    /** The caller a create is stamped with: the caller itself, no scope test, no administrator exemption. */
    public Long creatingCaller() {
        return callers.current();
    }

    public boolean belongsToCaller(String owner, Long caller) {
        return owner != null && caller != null && owner.equals(String.valueOf(caller));
    }

    public void requireOwned(String owner, Long caller) {
        if (owner != null && !owner.equals(String.valueOf(caller))) {
            throw new AccessDeniedException("该行不在你的数据范围内");
        }
    }

    public List<Long> asIds(Object argument) {
        List<Long> ids = new ArrayList<>();
        if (argument instanceof Long[] array) {
            for (Long id : array) {
                if (id != null) {
                    ids.add(id);
                }
            }
        } else if (argument instanceof Long id) {
            ids.add(id);
        } else if (argument instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Long id) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

}
