// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.module.fms.controller.admin.voucher.vo.FmsVoucherPageReqVO;
import cn.iocoder.yudao.module.fms.dal.dataobject.voucher.FmsVoucherDO;
import cn.iocoder.yudao.module.fms.dal.mysql.voucher.FmsVoucherMapper;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import cn.com.keelbase.runtime.authz.RuleDeniedException;

/**
 * FMS 切面的三条进门路径，与 CRM 那份同形但**两处不同**：收窄写在第 0 个实参上（用户 id 那一位判的是账套，
 * 不是行范围），而审核那条收的是**一列 id**，故任何一行不是调用方录的，整次调用就被拒。
 *
 * <p>Two places differ from the CRM aspect and both are pinned here: the narrowing lands on argument 0
 * (the user-id argument decides the account set, not the row range), and the review path takes a
 * **list of ids**, so one row that the caller did not enter refuses the whole call.
 */
class FmsVoucherGovernanceTest {

    private static final long CALLER = 42L;

    private YudaoModuleScope scope;
    private FmsVoucherMapper mapper;
    private FmsVoucherGovernance aspect;
    private ProceedingJoinPoint call;

    @BeforeEach
    void setUp() {
        scope = mock(YudaoModuleScope.class);
        mapper = mock(FmsVoucherMapper.class);
        aspect = new FmsVoucherGovernance(scope, mapper);
        call = mock(ProceedingJoinPoint.class);
        when(scope.callerToEnforce()).thenReturn(CALLER);
    }

    @Test
    void thePageGetsTheCallersCreatorFilter() throws Throwable {
        FmsVoucherPageReqVO query = new FmsVoucherPageReqVO();
        query.setCreatorUserId(7L);
        when(call.getArgs()).thenReturn(new Object[]{query, 5L});
        when(call.proceed(any(Object[].class))).thenReturn("page");

        assertEquals("page", aspect.narrowToCaller(call));
        assertEquals(CALLER, query.getCreatorUserId(),
                "a request naming someone else cannot widen what the caller sees");
    }

    @Test
    void withNoCallerThePageIsLeftAlone() throws Throwable {
        when(scope.callerToEnforce()).thenReturn(null);
        FmsVoucherPageReqVO query = new FmsVoucherPageReqVO();
        when(call.getArgs()).thenReturn(new Object[]{query, 5L});
        when(call.proceed()).thenReturn("page");

        aspect.narrowToCaller(call);

        assertNull(query.getCreatorUserId());
    }

    @Test
    void aReadByIdHandsAnothersVoucherBackAsNothing() throws Throwable {
        FmsVoucherDO others = voucher("7");
        when(scope.belongsToCaller("7", CALLER)).thenReturn(false);
        when(call.proceed()).thenReturn(others);

        assertNull(aspect.withholdOthersRow(call));
    }

    @Test
    void aReadByIdReturnsTheCallersOwnVoucher() throws Throwable {
        FmsVoucherDO mine = voucher("42");
        when(scope.belongsToCaller("42", CALLER)).thenReturn(true);
        when(call.proceed()).thenReturn(mine);

        assertSame(mine, aspect.withholdOthersRow(call));
    }

    @Test
    void theReviewRefusesWhenAnyRowIsNotTheCallers() throws Throwable {
        when(call.getArgs()).thenReturn(new Object[]{5L, List.of(1L, 2L), 1, 42L});
        when(mapper.selectById(1L)).thenReturn(voucher("42"));
        when(mapper.selectById(2L)).thenReturn(voucher("7"));
        doThrow(new RuleDeniedException(null, "该行不在你的数据范围内", "FmsVoucher", "update", null))
                .when(scope).requireOwned("7", CALLER, "FmsVoucher", "update");

        assertThrows(RuleDeniedException.class, () -> aspect.requireOwnership(call));
        verify(call, never()).proceed();
    }

    @Test
    void theReviewProceedsWhenEveryRowIsTheCallers() throws Throwable {
        when(call.getArgs()).thenReturn(new Object[]{5L, List.of(1L, 2L), 1, 42L});
        when(mapper.selectById(1L)).thenReturn(voucher("42"));
        when(mapper.selectById(2L)).thenReturn(voucher("42"));

        aspect.requireOwnership(call);

        verify(call).proceed();
    }

    @Test
    void theModuleDeclaresItselfAndTheColumnItReads() {
        assertEquals("fms", aspect.module());
        // 不是专设属主列：MyBatis-Plus 的审计串，而它正是 YudaoModuleScope 按文本比较的那一列。
        // Not a dedicated owner column: MyBatis-Plus's audit string, exactly what the scope compares as text.
        assertEquals("creator", aspect.ownerField());
    }

    private static FmsVoucherDO voucher(String creator) {
        FmsVoucherDO row = new FmsVoucherDO();
        row.setId(1L);
        row.setCreator(creator);
        return row;
    }
}
