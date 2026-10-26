// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.authz.RuleDeniedException;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * Refuse a row that is not the caller's, naming what was refused.
     *
     * <p>The refusal is the runtime's own row-refusal shape rather than Spring Security's
     * {@code AccessDeniedException}: the runtime's advice recognises that type and renders
     * {@code errorCode}, {@code reason}, {@code impact} and {@code nextStep} into the frozen error body,
     * while the security type falls through to the host's generic handler and arrives as a bare 403
     * with none of them. {@code subject} and {@code action} are the part a caller can act on — "this
     * identity lacks {@code update} on {@code Customer}" rather than "forbidden" — so the call site
     * supplies both. Today they travel on the exception and not yet in the body: the runtime renders an
     * {@code explanation} only when it has a deny basis to claim, and a row denial has none. No basis is
     * invented here either — the frozen {@code permission-decision} defines none for a row denial.
     *
     * <p>该行不是调用方的就拒绝，并点名拒了什么。
     *
     * <p>用的是运行时自己的行拒绝形状，而不是 Spring Security 的 {@code AccessDeniedException}：运行时的
     * advice 认得前面那个，能把 {@code errorCode} / {@code reason} / {@code impact} / {@code nextStep}
     * 渲染进冻结的错误体；后者会落到宿主的通用处理器，只以一个光秃秃的 403 到达，上面那些一个都没有。
     * {@code subject} 与 {@code action} 才是调用方能据以行动的部分 ——「这个身份对 {@code Customer} 没有
     * {@code update} 权限」而不是「forbidden」—— 故由调用点提供两者。**今天它们只在那条异常上、还不在 body
     * 里**：运行时只在自己有一个可声称的依据时才渲染 {@code explanation}，而行拒绝没有依据。这里也**不编造
     * 依据** —— 冻结的 {@code permission-decision} 对行拒绝没有定义任何依据。
     */
    public void requireOwned(String owner, Long caller, String subject, String action) {
        if (owner != null && !owner.equals(String.valueOf(caller))) {
            throw new RuleDeniedException(null, "该行不在你的数据范围内", subject, action,
                    "该行不在你当前的数据范围内；请改用本人或被授权组织范围内的数据，或让管理员调整数据范围");
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
