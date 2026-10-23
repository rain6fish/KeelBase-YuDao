// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * 「要不要收束这个人」的四种答案、以及收束时的三个原语。所有模块切面共用这一份，故它错一次会同时放宽好几个模块。
 *
 * <p>收束（非 null）只在**该收束**时出现：无调用方、种子管理员、范围是全部行，三者都不收束；其余一律收束。
 * 关键方向是第四条——**缺范围落在收束那一侧**：一个读不出范围的调用方，得到的是「只看见自己的行」，
 * 而不是「什么都能看」。
 *
 * <p>The four answers to "hold this caller?", and the three primitives used when holding. Every module
 * aspect shares this class, so one mistake here widens several modules at once. Holding (a non-null
 * answer) appears only when it should: no caller, the seed administrator, and a scope of every row are
 * left alone; everyone else is held. The direction that matters is the fourth — <b>a missing scope
 * lands on the holding side</b>: a caller whose range cannot be read gets "own rows only", never "sees
 * everything".
 */
class YudaoModuleScopeTest {

    private static final long CALLER = 42L;

    private YudaoCallerId callers;
    private YudaoRowScope scopes;
    private YudaoAdministrator administrators;
    private YudaoModuleScope scope;

    @BeforeEach
    void setUp() {
        callers = mock(YudaoCallerId.class);
        scopes = mock(YudaoRowScope.class);
        administrators = mock(YudaoAdministrator.class);
        scope = new YudaoModuleScope(callers, scopes, administrators);
        when(callers.current()).thenReturn(CALLER);
    }

    @Test
    void withNoCallerNothingIsHeld() {
        when(callers.current()).thenReturn(null);

        assertNull(scope.callerToEnforce());
    }

    @Test
    void theSeedAdministratorIsLeftAlone() {
        when(administrators.isAdmin(CALLER)).thenReturn(true);

        assertNull(scope.callerToEnforce());
    }

    @Test
    void aScopeOfEveryRowIsLeftAlone() {
        when(scopes.widestFor(CALLER)).thenReturn(YudaoRowScope.EVERY_ROW);

        assertNull(scope.callerToEnforce(), "the host's own table says every row — nothing to hold");
    }

    @Test
    void anOrdinaryCallerIsHeld() {
        when(scopes.widestFor(CALLER)).thenReturn("5");

        assertEquals(CALLER, scope.callerToEnforce());
    }

    @Test
    void aScopeThatCannotBeReadIsHeld() {
        when(scopes.widestFor(CALLER)).thenReturn(null);

        assertEquals(CALLER, scope.callerToEnforce(), "a missing scope narrows, it never widens");
    }

    @Test
    void aCreateIsStampedWithTheCallerEvenForAnAdministrator() {
        when(administrators.isAdmin(CALLER)).thenReturn(true);

        // 创建没有管理员豁免：属主列写的是**谁建的**，不是谁被放过。
        // Creating has no administrator exemption: the owner column records who created it.
        assertEquals(CALLER, scope.creatingCaller());
    }

    @Test
    void ownershipComparesTheOwnerAsText() {
        assertTrue(scope.belongsToCaller("42", CALLER));
        assertFalse(scope.belongsToCaller("43", CALLER));
        assertFalse(scope.belongsToCaller(null, CALLER), "nothing there is not yours");
        assertFalse(scope.belongsToCaller("42", null));
    }

    @Test
    void anothersRowIsRefused() {
        assertThrows(AccessDeniedException.class, () -> scope.requireOwned("43", CALLER));
    }

    @Test
    void theCallersOwnRowAndAMissingRowBothPass() {
        assertDoesNotThrow(() -> scope.requireOwned("42", CALLER));
        // 一行不存在时交给模块自己答 —— 切面分不出「没有」与「不是你的」，也不该去分。
        // A row that is not there is left to the module; the aspect cannot tell it from "not yours".
        assertDoesNotThrow(() -> scope.requireOwned(null, CALLER));
    }

    @Test
    void idsAreCollectedFromTheThreeShapesTheHostUses() {
        assertEquals(List.of(1L, 2L), scope.asIds(new Long[]{1L, null, 2L}));
        assertEquals(List.of(7L), scope.asIds(7L));
        assertEquals(List.of(1L, 2L), scope.asIds(Arrays.asList(1L, "x", 2L)));
        assertEquals(List.of(), scope.asIds("not ids"));
        assertEquals(List.of(), scope.asIds(null));
    }
}
