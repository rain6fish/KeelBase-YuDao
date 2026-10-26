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

import cn.iocoder.yudao.module.bpm.controller.admin.oa.vo.BpmOALeavePageReqVO;
import cn.iocoder.yudao.module.bpm.dal.dataobject.oa.BpmOALeaveDO;
import cn.iocoder.yudao.module.bpm.dal.mysql.oa.BpmOALeaveMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import cn.com.keelbase.runtime.authz.RuleDeniedException;

/**
 * OA 请假切面的四条进门路径，其中一条**未跑过**要说清楚：创建那条在本宿主没有流程定义，故它只被单测覆盖，
 * 门禁断言不到。
 *
 * <p>The OA leave aspect's four doors. One of them has to be named as **unexercised**: creating has no
 * process definition in this host, so only a unit test reaches it and no gate can assert on it.
 */
class BpmOALeaveGovernanceTest {

    private static final long CALLER = 42L;

    private YudaoModuleScope scope;
    private BpmOALeaveMapper mapper;
    private BpmOALeaveGovernance aspect;
    private ProceedingJoinPoint call;

    @BeforeEach
    void setUp() {
        scope = mock(YudaoModuleScope.class);
        mapper = mock(BpmOALeaveMapper.class);
        aspect = new BpmOALeaveGovernance(scope, mapper);
        call = mock(ProceedingJoinPoint.class);
        when(scope.callerToEnforce()).thenReturn(CALLER);
        when(scope.creatingCaller()).thenReturn(CALLER);
    }

    @Test
    void thePageReplacesWhoeverTheRequestNamed() throws Throwable {
        BpmOALeavePageReqVO query = new BpmOALeavePageReqVO();
        when(call.getArgs()).thenReturn(new Object[]{7L, query});
        when(call.proceed(any(Object[].class))).thenReturn("page");

        assertEquals("page", aspect.narrowToCaller(call));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(call).proceed(args.capture());
        assertEquals(CALLER, args.getValue()[0], "the caller's id replaces the request's");
    }

    @Test
    void withNoCallerThePageIsLeftAlone() throws Throwable {
        when(scope.callerToEnforce()).thenReturn(null);
        when(call.getArgs()).thenReturn(new Object[]{7L, new BpmOALeavePageReqVO()});
        when(call.proceed()).thenReturn("page");

        aspect.narrowToCaller(call);

        verify(call).proceed();
    }

    @Test
    void aReadByIdHandsAnothersLeaveBackAsNothing() throws Throwable {
        BpmOALeaveDO others = leave(7L);
        when(scope.belongsToCaller("7", CALLER)).thenReturn(false);
        when(call.proceed()).thenReturn(others);

        assertNull(aspect.withholdOthersRow(call));
    }

    @Test
    void aReadByIdReturnsTheCallersOwnLeave() throws Throwable {
        BpmOALeaveDO mine = leave(CALLER);
        when(scope.belongsToCaller("42", CALLER)).thenReturn(true);
        when(call.proceed()).thenReturn(mine);

        assertSame(mine, aspect.withholdOthersRow(call));
    }

    @Test
    void aStatusChangeToAnothersLeaveIsRefusedBeforeItRuns() throws Throwable {
        when(call.getArgs()).thenReturn(new Object[]{9L});
        when(mapper.selectById(9L)).thenReturn(leave(7L));
        doThrow(new RuleDeniedException(null, "该行不在你的数据范围内", "OaLeave", "update", null))
                .when(scope).requireOwned("7", CALLER, "OaLeave", "update");

        assertThrows(RuleDeniedException.class, () -> aspect.requireOwnership(call));
        verify(call, never()).proceed();
    }

    @Test
    void aStatusChangeToTheCallersOwnLeaveProceeds() throws Throwable {
        when(call.getArgs()).thenReturn(new Object[]{9L});
        when(mapper.selectById(9L)).thenReturn(leave(CALLER));

        aspect.requireOwnership(call);

        verify(scope).requireOwned("42", CALLER, "OaLeave", "update");
        verify(call).proceed();
    }

    @Test
    void aCreateIsStampedWithTheCaller() throws Throwable {
        when(call.getArgs()).thenReturn(new Object[]{7L, "anything"});
        when(call.proceed(any(Object[].class))).thenReturn("created");

        aspect.stampApplicant(call);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(call).proceed(args.capture());
        assertEquals(CALLER, args.getValue()[0],
                "unexercised in a gate — no process definition exists in this host, hence this unit test");
    }

    @Test
    void theModuleDeclaresItselfAndItsOwnerColumn() {
        assertEquals("bpm", aspect.module());
        assertEquals("user_id", aspect.ownerField());
    }

    private static BpmOALeaveDO leave(long userId) {
        BpmOALeaveDO row = new BpmOALeaveDO();
        row.setId(9L);
        row.setUserId(userId);
        return row;
    }
}
