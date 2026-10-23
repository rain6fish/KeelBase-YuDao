// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.ToolResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.fms.controller.admin.voucher.vo.FmsVoucherPageReqVO;
import cn.iocoder.yudao.module.fms.dal.dataobject.config.FmsAccountUserDO;
import cn.iocoder.yudao.module.fms.dal.dataobject.voucher.FmsVoucherDO;
import cn.iocoder.yudao.module.fms.dal.mysql.voucher.FmsVoucherMapper;
import cn.iocoder.yudao.module.fms.enums.voucher.FmsVoucherStatusEnum;
import cn.iocoder.yudao.module.fms.service.config.FmsAccountUserService;
import cn.iocoder.yudao.module.fms.service.voucher.FmsVoucherService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * FMS 工具面的两个出口：读那一半**自己解析调用方的账套**、写那一半**从行读账套并按 id 审核**。
 *
 * <p>两处都是「模型只给 id、账套由宿主自己定」这条规则的落点：读侧不许要求模型先知道账套编号，
 * 写侧不许把账套当参数收；与此同时出口投影**不带两个用户列**（录入人与审核人都是别人的身份）。
 *
 * <p>Both exits of the FMS tool face: the read resolves the caller's own account set, and the write
 * reads the account set off the row and approves by id. Both are where "the model gives an id and the
 * host decides the account set" lands: the read must not make a model know an account-set number, the
 * write must not accept one as an argument — and the projection carries neither user column, because
 * the entry clerk and the reviewer are someone else's identity.
 */
class FmsVoucherToolsTest {

    private static final String CALLER = "42";
    private static final long CALLER_ID = 42L;

    private FmsVoucherService service;
    private FmsAccountUserService accountUsers;
    private FmsVoucherMapper mapper;
    private FmsVoucherTools.ListTool listTool;
    private FmsVoucherTools.ReviewTool reviewTool;

    @BeforeEach
    void setUp() {
        service = mock(FmsVoucherService.class);
        accountUsers = mock(FmsAccountUserService.class);
        mapper = mock(FmsVoucherMapper.class);
        listTool = new FmsVoucherTools.ListTool(service, accountUsers);
        reviewTool = new FmsVoucherTools.ReviewTool(service, mapper);
    }

    @Test
    void theReadPrefersTheDefaultAccountSet() {
        when(accountUsers.getAccountUserList(CALLER_ID)).thenReturn(List.of(
                membership(7L, false), membership(8L, true)));
        when(service.getVoucherPage(any(), eq(CALLER_ID))).thenReturn(new PageResult<>(List.of(), 0L));

        listTool.execute(Map.of(), caller());

        assertEquals(8L, queryOf().getAccountSetId(), "the default membership is the caller's account set");
    }

    @Test
    void theReadFallsBackToTheFirstMembershipWhenNoneIsDefault() {
        when(accountUsers.getAccountUserList(CALLER_ID)).thenReturn(List.of(
                membership(7L, false), membership(8L, false)));
        when(service.getVoucherPage(any(), eq(CALLER_ID))).thenReturn(new PageResult<>(List.of(), 0L));

        listTool.execute(Map.of(), caller());

        assertEquals(7L, queryOf().getAccountSetId(), "with no default, the first membership is used");
    }

    @Test
    void aCallerWithNoMembershipQueriesNoAccountSet() {
        when(accountUsers.getAccountUserList(CALLER_ID)).thenReturn(List.of());
        when(service.getVoucherPage(any(), eq(CALLER_ID))).thenReturn(new PageResult<>(List.of(), 0L));

        listTool.execute(Map.of(), caller());

        // 一个都没有时查询里就没有账套 —— 那一页会空着，而不是答一串别人的账。
        // With none, the query carries no account set: the page comes back empty rather than with someone else's books.
        assertNull(queryOf().getAccountSetId());
    }

    @Test
    void theReadProjectionCarriesNeitherUserColumn() {
        when(accountUsers.getAccountUserList(CALLER_ID)).thenReturn(List.of(membership(7L, true)));
        FmsVoucherDO row = new FmsVoucherDO();
        row.setId(9L);
        row.setVoucherNumber(9);
        row.setCreator("101");
        row.setReviewerUserId(102L);
        when(service.getVoucherPage(any(), eq(CALLER_ID))).thenReturn(new PageResult<>(List.of(row), 1L));

        ToolResult result = listTool.execute(Map.of(), caller());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.data();
        assertEquals(1, rows.size());
        assertEquals(List.of("id", "voucherNumber", "voucherTime", "debitAmount", "creditAmount", "status"),
                List.copyOf(rows.get(0).keySet()),
                "the projection is the whole allow-list — a user column must not reappear");
    }

    @Test
    void theWriteRefusesWithoutAnId() {
        ToolResult result = reviewTool.execute(Map.of(), caller());

        assertFalse(result.success());
        assertEquals("id is required and must be a number", result.error());
    }

    @Test
    void theWriteRefusesAVoucherThatIsNotThere() {
        when(mapper.selectById(9L)).thenReturn(null);

        ToolResult result = reviewTool.execute(Map.of("id", 9), caller());

        assertFalse(result.success());
        assertEquals("no voucher 9", result.error());
        verify(service, never()).updateVoucherReviewStatus(anyLong(), anyList(), anyInt(), anyLong());
    }

    @Test
    void theWriteApprovesByTheAccountSetItReadsOffTheRow() {
        FmsVoucherDO row = new FmsVoucherDO();
        row.setId(9L);
        row.setAccountSetId(5L);
        when(mapper.selectById(9L)).thenReturn(row);

        ToolResult result = reviewTool.execute(Map.of("id", 9), caller());

        assertTrue(result.success());
        verify(service).updateVoucherReviewStatus(
                eq(5L), eq(List.of(9L)), eq(FmsVoucherStatusEnum.APPROVED.getStatus()), eq(CALLER_ID));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) result.data();
        assertEquals(9L, out.get("id"), "the result carries the id: side_effects.result_id is NOT NULL");
        assertEquals(FmsVoucherStatusEnum.APPROVED.getStatus(), out.get("status"));
    }

    @Test
    void theWriteDeclaresItsResultTypeForTheLedger() {
        assertEquals("fms_voucher", reviewTool.resultType());
        assertEquals("R3", reviewTool.riskLevel());
        assertEquals("list_my_fms_vouchers", listTool.name());
        assertEquals("R1", listTool.riskLevel());
    }

    private static Principal caller() {
        return new Principal(CALLER, Principal.ROLE_USER);
    }

    private static FmsAccountUserDO membership(long accountSetId, boolean isDefault) {
        FmsAccountUserDO member = new FmsAccountUserDO();
        member.setAccountSetId(accountSetId);
        member.setDefaultStatus(isDefault);
        return member;
    }

    private FmsVoucherPageReqVO queryOf() {
        ArgumentCaptor<FmsVoucherPageReqVO> captor = ArgumentCaptor.forClass(FmsVoucherPageReqVO.class);
        verify(service).getVoucherPage(captor.capture(), eq(CALLER_ID));
        return captor.getValue();
    }
}
