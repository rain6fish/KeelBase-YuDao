// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.ToolResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.system.dal.dataobject.notify.NotifyMessageDO;
import cn.iocoder.yudao.module.system.service.notify.NotifyMessageService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 站内信工具面的三件事：**列**（只列调用方自己的那一对 id + 类型）、**读一条**（别人的读成失败而不是空）、
 * **标已读**（改了几行如实带出来）。
 *
 * <p>The three things the in-app message face does: list (scoped to the caller's own pair of id and
 * type), read one (another's reads as a failure rather than as empty), and mark read (the number of
 * rows that actually changed is carried back).
 */
class SystemNotifyMessageToolsTest {

    private static final String CALLER = "42";
    private static final long CALLER_ID = 42L;

    private NotifyMessageService service;
    private SystemNotifyMessageTools.ListTool listTool;
    private SystemNotifyMessageTools.GetTool getTool;
    private SystemNotifyMessageTools.MarkReadTool markReadTool;

    @BeforeEach
    void setUp() {
        service = mock(NotifyMessageService.class);
        listTool = new SystemNotifyMessageTools.ListTool(service);
        getTool = new SystemNotifyMessageTools.GetTool(service);
        markReadTool = new SystemNotifyMessageTools.MarkReadTool(service);
    }

    @Test
    void theListIsScopedToTheCallerAndProjectsFiveFields() {
        NotifyMessageDO row = message(1L);
        when(service.getMyMyNotifyMessagePage(any(), eq(CALLER_ID), any()))
                .thenReturn(new PageResult<>(List.of(row), 1L));

        ToolResult result = listTool.execute(Map.of(), caller());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.data();
        assertEquals(1, rows.size());
        assertEquals(List.of("id", "templateCode", "templateType", "readStatus", "createTime"),
                List.copyOf(rows.get(0).keySet()), "the content of a message never leaves");
    }

    @Test
    void aMessageThatIsNotTheCallersReadsAsAFailure() {
        when(service.getNotifyMessage(9L)).thenReturn(null);

        ToolResult result = getTool.execute(Map.of("id", 9), caller());

        assertFalse(result.success());
        assertEquals("no message readable for this caller", result.error(),
                "not yours and not there answer the same, and it is not an empty success");
    }

    @Test
    void aMessageTheCallerCanReadIsProjected() {
        when(service.getNotifyMessage(9L)).thenReturn(message(9L));

        ToolResult result = getTool.execute(Map.of("id", 9), caller());

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) result.data();
        assertEquals(9L, out.get("id"));
        assertTrue(out.containsKey("readStatus"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void markingReadCarriesBackHowManyRowsChanged() {
        when(service.updateNotifyMessageRead(any(), eq(CALLER_ID), any())).thenReturn(1);

        ToolResult result = markReadTool.execute(Map.of("id", 9), caller());

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) result.data();
        assertEquals(9L, out.get("id"));
        assertEquals(1, out.get("changed"));

        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(service).updateNotifyMessageRead(ids.capture(), eq(CALLER_ID), any());
        assertEquals(List.of(9L), ids.getValue());
    }

    @Test
    void theFacesDeclareTheirContract() {
        assertEquals("list_my_notify_messages", listTool.name());
        assertEquals("R1", listTool.riskLevel());
        assertEquals("get_notify_message", getTool.name());
        assertEquals("R1", getTool.riskLevel());
        assertEquals("mark_notify_message_read", markReadTool.name());
        assertEquals("R3", markReadTool.riskLevel());
        assertEquals("system_notify_message", markReadTool.resultType());
    }

    private static NotifyMessageDO message(long id) {
        NotifyMessageDO row = new NotifyMessageDO();
        row.setId(id);
        row.setTemplateCode("tpl");
        row.setTemplateType(1);
        row.setReadStatus(false);
        row.setCreateTime(LocalDateTime.of(2026, 10, 22, 9, 0));
        return row;
    }

    private static Principal caller() {
        return new Principal(CALLER, Principal.ROLE_USER);
    }
}
