// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.ToolResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.crm.controller.admin.customer.vo.customer.CrmCustomerPageReqVO;
import cn.iocoder.yudao.module.crm.controller.admin.customer.vo.customer.CrmCustomerSaveReqVO;
import cn.iocoder.yudao.module.crm.dal.dataobject.customer.CrmCustomerDO;
import cn.iocoder.yudao.module.crm.service.customer.CrmCustomerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * CRM 客户工具面的写侧：**先问权限码、再读快照、最后才写**，而快照本身也只带导出投影。
 *
 * <p>次序是这条测试的重点：被权限拦下的调用**连快照都不该去读** —— 它没有资格知道那一行原先是什么样。
 * 而读到的快照走的是读工具那份投影，故联系方式不会因为「这是快照」而外流。
 *
 * <p>The write half of the CRM customer tool face: ask the permission code first, read the snapshot
 * second, write last — and the snapshot itself carries only the outbound projection. The ordering is
 * the point: a call the permission refuses must not even read the snapshot, because it has no claim on
 * knowing what the row used to look like. And the snapshot goes through the read tool's projection, so
 * contact details do not leave merely because this is a snapshot.
 */
class CrmCustomerToolsTest {

    private static final String CALLER = "42";

    private CrmCustomerService service;
    private YudaoToolPermissions permissions;
    private CrmCustomerTools.UpdateTool updateTool;
    private CrmCustomerTools.CreateTool createTool;
    private CrmCustomerTools.ListTool listTool;

    @BeforeEach
    void setUp() {
        service = mock(CrmCustomerService.class);
        permissions = mock(YudaoToolPermissions.class);
        updateTool = new CrmCustomerTools.UpdateTool(service, permissions);
        createTool = new CrmCustomerTools.CreateTool(service, permissions);
        listTool = new CrmCustomerTools.ListTool(service);
    }

    @Test
    void aRefusedCallDoesNotEvenReadTheSnapshot() {
        when(permissions.refusal(CALLER, "crm:customer:update"))
                .thenReturn("not permitted: this caller lacks the 'crm:customer:update' permission");

        ToolResult result = updateTool.execute(Map.of("id", 9, "name", "新名字"), caller());

        assertFalse(result.success());
        assertTrue(result.error().contains("crm:customer:update"), "the reason names the code");
        verify(service, never()).getCustomer(any());
        verify(service, never()).updateCustomer(any());
    }

    @Test
    void anAllowedCallReadsTheSnapshotThenWrites() {
        when(permissions.refusal(CALLER, "crm:customer:update")).thenReturn(null);
        CrmCustomerDO existing = new CrmCustomerDO();
        existing.setId(9L);
        existing.setName("旧名字");
        existing.setMobile("13800000000");
        when(service.getCustomer(9L)).thenReturn(existing);

        ToolResult result = updateTool.execute(
                Map.of("id", 9, "name", "新名字", "level", 2, "source", 3), caller());

        assertTrue(result.success());
        assertEquals(9L, ((Map<?, ?>) result.data()).get("id"));

        CrmCustomerSaveReqVO written = written();
        assertEquals(9L, written.getId());
        assertEquals("新名字", written.getName());
        assertEquals(Integer.valueOf(2), written.getLevel());
        assertEquals(Integer.valueOf(3), written.getSource());

        assertTrue(result.beforeSnapshot().contains("旧名字"), "the trail answers what changed");
        assertFalse(result.beforeSnapshot().contains("13800000000"),
                "a snapshot is not an exception to the projection");
    }

    @Test
    void theFaceDeclaresItsContract() {
        assertEquals("update_crm_customer", updateTool.name());
        assertEquals("R3", updateTool.riskLevel());
        assertEquals("crm_customer", updateTool.resultType());
    }

    @Test
    void aRefusedCreateNeverReachesTheService() {
        when(permissions.refusal(CALLER, "crm:customer:create"))
                .thenReturn("not permitted: this caller lacks the 'crm:customer:create' permission");

        ToolResult result = createTool.execute(Map.of("name", "新客户"), caller());

        assertFalse(result.success());
        verify(service, never()).createCustomer(any(CrmCustomerSaveReqVO.class), any());
    }

    @Test
    void anAllowedCreatePassesTheCallerAndReturnsTheId() {
        when(permissions.refusal(CALLER, "crm:customer:create")).thenReturn(null);
        when(service.createCustomer(any(CrmCustomerSaveReqVO.class), eq(42L))).thenReturn(77L);

        ToolResult result = createTool.execute(Map.of("name", "新客户", "mobile", "13800000000"), caller());

        assertTrue(result.success());
        assertEquals(77L, ((Map<?, ?>) result.data()).get("id"));

        ArgumentCaptor<CrmCustomerSaveReqVO> captor =
                ArgumentCaptor.forClass(CrmCustomerSaveReqVO.class);
        verify(service).createCustomer(captor.capture(), eq(42L));
        assertEquals("新客户", captor.getValue().getName());
    }

    @Test
    void theListScopesThroughTheModulesOwnQueryAndDropsContactFields() {
        CrmCustomerDO row = new CrmCustomerDO();
        row.setId(1L);
        row.setName("临海制造");
        row.setMobile("13800000000");
        when(service.getCustomerPage(any(CrmCustomerPageReqVO.class), eq(42L)))
                .thenReturn(new PageResult<>(List.of(row), 1L));

        ToolResult result = listTool.execute(Map.of(), caller());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.data();
        assertEquals(List.of("id", "name", "ownerUserId", "dealStatus", "followUpStatus", "level", "source"),
                List.copyOf(rows.get(0).keySet()), "the identity travels as the query's own argument");
        assertFalse(result.data().toString().contains("13800000000"));
    }

    private CrmCustomerSaveReqVO written() {
        ArgumentCaptor<CrmCustomerSaveReqVO> captor =
                ArgumentCaptor.forClass(CrmCustomerSaveReqVO.class);
        verify(service).updateCustomer(captor.capture());
        return captor.getValue();
    }

    private static Principal caller() {
        return new Principal(CALLER, Principal.ROLE_USER);
    }
}
