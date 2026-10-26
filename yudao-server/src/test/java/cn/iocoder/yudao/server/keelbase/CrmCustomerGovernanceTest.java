// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.module.crm.controller.admin.customer.vo.customer.CrmCustomerPageReqVO;
import cn.iocoder.yudao.module.crm.controller.admin.customer.vo.customer.CrmCustomerSaveReqVO;
import cn.iocoder.yudao.module.crm.dal.dataobject.customer.CrmCustomerDO;
import cn.iocoder.yudao.module.crm.dal.mysql.customer.CrmCustomerMapper;
import cn.iocoder.yudao.module.crm.enums.common.CrmSceneTypeEnum;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import cn.com.keelbase.runtime.authz.RuleDeniedException;

/**
 * CRM 客户切面的三条进门路径各收一次：分页补上「只看本人的」、按 id 读把别人的行交成空、按 id 写要求是自己的行。
 *
 * <p>切面在这里直接调用，不经 Spring AOP —— 要证的是「这一段通知对这三条路径做了什么」，不是「注解有没有生效」
 * （后者由上下文测试与真栈门禁管）。三条都是**收窄**：补一个场景、交回 null、抛拒绝；没有一条能放宽。
 *
 * <p>One turn at each of the three doors into the CRM customer rows: the page gets "own rows only" filled
 * in, a read by id hands another's row back as nothing, and a write by id has to be the caller's own
 * row. The advice is called directly rather than through Spring AOP here — what is proven is what this
 * advice does to those three paths, not that the annotations take effect (the context test and the real
 * stack gate cover that). All three narrow: a scene is filled in, a row becomes null, a call is refused;
 * none of them widens.
 */
class CrmCustomerGovernanceTest {

    private static final long CALLER = 42L;

    private YudaoModuleScope scope;
    private CrmCustomerMapper mapper;
    private CrmCustomerGovernance aspect;
    private ProceedingJoinPoint call;

    @BeforeEach
    void setUp() {
        scope = mock(YudaoModuleScope.class);
        mapper = mock(CrmCustomerMapper.class);
        aspect = new CrmCustomerGovernance(scope, mapper);
        call = mock(ProceedingJoinPoint.class);
        when(scope.callerToEnforce()).thenReturn(CALLER);
        when(scope.creatingCaller()).thenReturn(CALLER);
    }

    @Test
    void thePageGetsOwnRowsOnlyWhenNoSceneWasNamed() throws Throwable {
        CrmCustomerPageReqVO query = new CrmCustomerPageReqVO();
        when(call.getArgs()).thenReturn(new Object[]{query, CALLER});
        when(call.proceed()).thenReturn("page");

        assertEquals("page", aspect.narrowToCaller(call));
        assertEquals(CrmSceneTypeEnum.OWNER.getType(), query.getSceneType(),
                "upstream's default is every row, so the scene has to be supplied");
    }

    @Test
    void aSceneTheCallerNamedIsLeftAlone() throws Throwable {
        CrmCustomerPageReqVO query = new CrmCustomerPageReqVO();
        query.setSceneType(CrmSceneTypeEnum.OWNER.getType());
        when(call.getArgs()).thenReturn(new Object[]{query, CALLER});

        aspect.narrowToCaller(call);

        assertEquals(CrmSceneTypeEnum.OWNER.getType(), query.getSceneType());
    }

    @Test
    void withNoCallerThePageIsNotTouched() throws Throwable {
        when(scope.callerToEnforce()).thenReturn(null);
        CrmCustomerPageReqVO query = new CrmCustomerPageReqVO();
        when(call.getArgs()).thenReturn(new Object[]{query, null});

        aspect.narrowToCaller(call);

        assertNull(query.getSceneType());
    }

    @Test
    void aReadByIdHandsAnothersRowBackAsNothing() throws Throwable {
        CrmCustomerDO others = customer(7L);
        when(scope.belongsToCaller("7", CALLER)).thenReturn(false);
        when(call.proceed()).thenReturn(others);

        assertNull(aspect.withholdOthersRow(call), "not yours and not there get the same answer");
    }

    @Test
    void aReadByIdReturnsTheCallersOwnRow() throws Throwable {
        CrmCustomerDO mine = customer(7L);
        when(scope.belongsToCaller("7", CALLER)).thenReturn(true);
        when(call.proceed()).thenReturn(mine);

        assertSame(mine, aspect.withholdOthersRow(call));
    }

    @Test
    void aReadByIdWithoutACallerIsNotFiltered() throws Throwable {
        when(scope.callerToEnforce()).thenReturn(null);
        CrmCustomerDO row = customer(7L);
        when(call.proceed()).thenReturn(row);

        assertSame(row, aspect.withholdOthersRow(call));
    }

    @Test
    void aSaveIsStampedWithTheCaller() throws Throwable {
        CrmCustomerSaveReqVO saved = new CrmCustomerSaveReqVO();
        when(call.getArgs()).thenReturn(new Object[]{saved});

        aspect.stampOwner(call);

        assertEquals(CALLER, saved.getOwnerUserId(),
                "the owner column cannot be pointed at someone else");
        verify(call).proceed();
    }

    @Test
    void anUpdateOfAnothersRowIsRefusedBeforeItRuns() throws Throwable {
        CrmCustomerSaveReqVO update = new CrmCustomerSaveReqVO();
        update.setId(9L);
        when(call.getArgs()).thenReturn(new Object[]{update});
        when(mapper.selectById(9L)).thenReturn(customer(7L));
        doThrow(new RuleDeniedException(null, "该行不在你的数据范围内", "Customer", "update", null))
                .when(scope).requireOwned("7", CALLER, "Customer", "update");

        assertThrows(RuleDeniedException.class, () -> aspect.requireOwnership(call));
        verify(call, never()).proceed();
    }

    @Test
    void anUpdateOfTheCallersOwnRowProceeds() throws Throwable {
        CrmCustomerSaveReqVO update = new CrmCustomerSaveReqVO();
        update.setId(9L);
        when(call.getArgs()).thenReturn(new Object[]{update});
        when(mapper.selectById(9L)).thenReturn(customer(CALLER));

        aspect.requireOwnership(call);

        verify(scope).requireOwned(String.valueOf(CALLER), CALLER, "Customer", "update");
        verify(call).proceed();
    }

    @Test
    void anUpdateOfARowThatIsNotThereIsLeftToTheModule() throws Throwable {
        CrmCustomerSaveReqVO update = new CrmCustomerSaveReqVO();
        update.setId(9L);
        when(call.getArgs()).thenReturn(new Object[]{update});
        when(mapper.selectById(9L)).thenReturn(null);

        aspect.requireOwnership(call);

        verify(scope).requireOwned(null, CALLER, "Customer", "update");
        verify(call).proceed();
    }

    @Test
    void theModuleDeclaresItselfAndItsOwnerColumn() {
        assertEquals("crm", aspect.module());
        assertEquals("owner_user_id", aspect.ownerField());
    }

    private static CrmCustomerDO customer(long ownerUserId) {
        CrmCustomerDO row = new CrmCustomerDO();
        row.setId(9L);
        row.setOwnerUserId(ownerUserId);
        return row;
    }
}
