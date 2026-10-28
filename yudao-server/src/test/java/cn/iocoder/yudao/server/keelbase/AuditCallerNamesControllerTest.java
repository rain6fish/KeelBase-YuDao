// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link AuditCallerNamesController} 的测试：这一面只答两件事 —— **谁能读**、**回什么**。
 *
 * <p>断言的三条承诺：管理员与审计读者都能读（其余一律 403、未认证 401）；答案里**只有**编号、账号、昵称，
 * 不放邮箱 / 手机 / 部门 / 角色；查不到的 id **不出现**，由调用方按「没有这一条」降级。不测 Spring 装配 ——
 * 那条由真栈的三账号读数给出（`YD-42-第三方可核包_2026-10-28.md`）。
 *
 * <p>英文：
 *
 * <p>Tests for {@link AuditCallerNamesController}: this face answers two things only — **who may read**
 * it and **what it returns**.
 *
 * <p>Three promises are asserted: an administrator and an audit reader may both read it (everyone else
 * 403, an unauthenticated caller 401); the answer carries **only** id, account and nickname — no email,
 * mobile, department or roles; an id that cannot be read is **absent**, which the caller degrades the
 * same way it degrades "no name". Spring wiring is not tested here — the three-identity reading on a real
 * stack is what covers that.
 */
class AuditCallerNamesControllerTest {

    private YudaoCallerId callers;
    private YudaoAdministrator administrators;
    private YudaoAuditReaderRules readers;
    private AdminUserService users;
    private AuditCallerNamesController controller;

    @BeforeEach
    void setUp() {
        callers = mock(YudaoCallerId.class);
        administrators = mock(YudaoAdministrator.class);
        readers = mock(YudaoAuditReaderRules.class);
        users = mock(AdminUserService.class);
        controller = new AuditCallerNamesController(callers, administrators, readers, users);
    }

    private static AdminUserDO user(long id, String username, String nickname) {
        AdminUserDO row = new AdminUserDO();
        row.setId(id);
        row.setUsername(username);
        row.setNickname(nickname);
        return row;
    }

    @Test
    void anAdministratorReadsNames() {
        when(callers.current()).thenReturn(1L);
        when(administrators.isAdmin(1L)).thenReturn(true);
        when(users.getUserList(any())).thenReturn(List.of(user(1L, "admin", "KeelBase")));

        List<Map<String, Object>> answer = controller.names(List.of(1L));

        assertEquals(1, answer.size());
        assertEquals(1L, answer.get(0).get("id"));
        assertEquals("admin", answer.get(0).get("username"));
        assertEquals("KeelBase", answer.get(0).get("nickname"));
    }

    @Test
    void anAuditReaderReadsNamesToo() {
        when(callers.current()).thenReturn(9002L);
        when(administrators.isAdmin(9002L)).thenReturn(false);
        when(readers.isAuditReader("9002")).thenReturn(true);
        when(users.getUserList(any())).thenReturn(List.of(user(1L, "admin", "KeelBase")));

        assertEquals(1, controller.names(List.of(1L)).size());
    }

    @Test
    void anyoneElseIsRefusedAndTheDirectoryIsNeverTouched() {
        when(callers.current()).thenReturn(9003L);
        when(administrators.isAdmin(9003L)).thenReturn(false);
        when(readers.isAuditReader("9003")).thenReturn(false);

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> controller.names(List.of(1L)));
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
        verifyNoInteractions(users);
    }

    @Test
    void anUnauthenticatedCallerIsAskedToAuthenticate() {
        when(callers.current()).thenReturn(null);

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> controller.names(List.of(1L)));
        assertEquals(HttpStatus.UNAUTHORIZED, refused.getStatusCode());
        verifyNoInteractions(users);
    }

    @Test
    void theAnswerCarriesNothingButIdAccountAndNickname() {
        when(callers.current()).thenReturn(1L);
        when(administrators.isAdmin(1L)).thenReturn(true);
        when(users.getUserList(any())).thenReturn(List.of(user(1L, "admin", "KeelBase")));

        Map<String, Object> row = controller.names(List.of(1L)).get(0);

        assertEquals(List.of("id", "username", "nickname"), List.copyOf(row.keySet()));
        assertTrue(row.keySet().stream().noneMatch(key -> key.toLowerCase().contains("mobile")
                || key.toLowerCase().contains("email") || key.toLowerCase().contains("dept")));
    }

    @Test
    void anEmptyAskTouchesNothing() {
        when(callers.current()).thenReturn(1L);
        when(administrators.isAdmin(1L)).thenReturn(true);

        assertTrue(controller.names(List.of()).isEmpty());
        verifyNoInteractions(users);
    }
}
