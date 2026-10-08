// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import java.util.ArrayList;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * The machine a generated module's aspects share — the Yudao twin of the RuoYi host's
 * {@code GeneratedModuleScope}, with one substitution and one declared shortcut.
 *
 * <p>The substitution: the RuoYi machine asked {@code RuoYiRowScope} (which read {@code sys_role} data
 * scopes) whether a caller ranges over every row. There is no such read here yet, so a caller is left
 * alone only when it is the seed administrator. That is a <b>declared</b> narrowing of the same
 * question the RuoYi host answered from its role table — not an answer, a placeholder for one.
 *
 * <p>The shortcut is the same one the identity resolver already declares: Yudao's role keys are not the
 * contract's vocabulary, so "administrator" means user 1 until a real mapping is decided.
 *
 * 生成模块的切面共用的机器——RuoYi 宿主那份 {@code GeneratedModuleScope} 的芋道孪生，带一处替换、一处声明的简化。
 *
 * <p>**替换**：RuoYi 那台机器问 {@code RuoYiRowScope}（读 {@code sys_role} 的数据范围）调用方是否覆盖全部行。
 * 这里还没有这种读法，故只有**种子管理员**被放过。这是对 RuoYi 宿主从角色表回答的**同一个问题**的回答的**声明式
 * 收窄**——不是答案，是答案的占位。
 *
 * <p>**简化**与身份解析器已声明的那条相同：芋道的 roleKey 不是契约的词表，故在真正定下映射之前，「管理员」指用户 1。
 */
@Component
public class YudaoModuleScope {

    /** Yudao's seed super administrator, until a role mapping is decided. / 芋道种子超管，映射定下前。 */
    private static final long SEED_ADMIN_ID = 1L;

    private final YudaoCallerId callers;

    public YudaoModuleScope(YudaoCallerId callers) {
        this.callers = callers;
    }

    /** The caller a governed access must be held to, or {@code null} when nothing needs holding. */
    public Long callerToEnforce() {
        Long caller = callers.current();
        if (caller == null || isSeedAdmin(caller)) {
            return null;
        }
        return caller;
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

    private static boolean isSeedAdmin(Long caller) {
        return caller == SEED_ADMIN_ID;
    }
}
