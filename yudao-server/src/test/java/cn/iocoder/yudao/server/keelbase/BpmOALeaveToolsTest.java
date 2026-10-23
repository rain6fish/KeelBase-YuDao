// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.ToolResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.bpm.dal.dataobject.oa.BpmOALeaveDO;
import cn.iocoder.yudao.module.bpm.enums.task.BpmTaskStatusEnum;
import cn.iocoder.yudao.module.bpm.service.oa.BpmOALeaveService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * OA 请假工具面的两个出口：**列**（五列投影，含调用方自己的 id）与**批**（按 id 改状态，行范围由切面管）。
 *
 * <p>Two exits of the OA leave face: list (a five-column projection that includes the caller's own id)
 * and approve (a status change by id, with the row range owned by the aspect next door).
 */
class BpmOALeaveToolsTest {

    private static final String CALLER = "42";
    private static final long CALLER_ID = 42L;

    private BpmOALeaveService service;
    private BpmOALeaveTools.ListTool listTool;
    private BpmOALeaveTools.ApproveTool approveTool;

    @BeforeEach
    void setUp() {
        service = mock(BpmOALeaveService.class);
        listTool = new BpmOALeaveTools.ListTool(service);
        approveTool = new BpmOALeaveTools.ApproveTool(service);
    }

    @Test
    void theListProjectsFiveColumnsAndScopesToTheCaller() {
        BpmOALeaveDO row = new BpmOALeaveDO();
        row.setId(1L);
        row.setUserId(CALLER_ID);
        row.setType(1);
        row.setDay(2L);
        row.setStatus(10);
        when(service.getLeavePage(eq(CALLER_ID), any())).thenReturn(new PageResult<>(List.of(row), 1L));

        ToolResult result = listTool.execute(Map.of(), caller());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.data();
        assertEquals(List.of("id", "userId", "type", "day", "status"), List.copyOf(rows.get(0).keySet()));
        verify(service).getLeavePage(eq(CALLER_ID), any());
    }

    @Test
    void theApproveChangesTheStatusTheModuleExpects() {
        ToolResult result = approveTool.execute(Map.of("id", 9), caller());

        assertTrue(result.success());
        Integer approved = BpmTaskStatusEnum.APPROVE.getStatus();
        verify(service).updateLeaveStatus(9L, approved);

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) result.data();
        assertEquals(9L, out.get("id"));
        assertEquals(approved, out.get("status"));
    }

    @Test
    void theFacesDeclareTheirContract() {
        assertEquals("list_my_oa_leaves", listTool.name());
        assertEquals("R1", listTool.riskLevel());
        assertEquals("approve_oa_leave", approveTool.name());
        assertEquals("R3", approveTool.riskLevel());
        assertEquals("bpm_oa_leave", approveTool.resultType());
    }

    private static Principal caller() {
        return new Principal(CALLER, Principal.ROLE_USER);
    }
}
