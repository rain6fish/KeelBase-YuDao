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

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.infra.controller.admin.demo.demo01.vo.Demo01ContactSaveReqVO;
import cn.iocoder.yudao.module.infra.dal.dataobject.demo.demo01.Demo01ContactDO;
import cn.iocoder.yudao.module.infra.dal.mysql.demo.demo01.Demo01ContactMapper;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import cn.com.keelbase.runtime.authz.RuleDeniedException;

/**
 * demo01 联系人的四条门，其中两条**与别的模块不同形**：分页是**模块答完之后**再过滤（而不是改查询条件），
 * 创建是**insert 之后按 id 补盖章**（保存 VO 没有 creator 字段）。
 *
 * <p>Four doors for the demo01 contact, two of them shaped differently from the other modules: the
 * page is filtered <b>after</b> the module answers rather than by rewriting its query, and a create is
 * stamped by id <b>after</b> the insert, because the save VO has no creator field.
 */
class Demo01ContactGovernanceTest {

    private static final long CALLER = 42L;

    private YudaoModuleScope scope;
    private Demo01ContactMapper mapper;
    private Demo01ContactGovernance aspect;
    private ProceedingJoinPoint call;

    @BeforeEach
    void setUp() {
        scope = mock(YudaoModuleScope.class);
        mapper = mock(Demo01ContactMapper.class);
        aspect = new Demo01ContactGovernance(scope, mapper);
        call = mock(ProceedingJoinPoint.class);
        when(scope.callerToEnforce()).thenReturn(CALLER);
        when(scope.creatingCaller()).thenReturn(CALLER);
    }

    @Test
    void thePageKeepsOnlyTheCallersRowsAndResizes() throws Throwable {
        Demo01ContactDO mine = contact(1L, "42");
        Demo01ContactDO theirs = contact(2L, "7");
        when(scope.belongsToCaller("42", CALLER)).thenReturn(true);
        when(scope.belongsToCaller("7", CALLER)).thenReturn(false);
        when(call.proceed()).thenReturn(new PageResult<>(List.of(mine, theirs), 2L));

        @SuppressWarnings("unchecked")
        PageResult<Demo01ContactDO> page = (PageResult<Demo01ContactDO>) aspect.narrowToCaller(call);

        assertEquals(List.of(1L), page.getList().stream().map(Demo01ContactDO::getId).toList());
        assertEquals(1L, page.getTotal(), "the total follows the filtered rows, not the module's count");
    }

    @Test
    void withNoCallerThePageIsReturnedAsItCame() throws Throwable {
        when(scope.callerToEnforce()).thenReturn(null);
        PageResult<Demo01ContactDO> original = new PageResult<>(List.of(contact(2L, "7")), 1L);
        when(call.proceed()).thenReturn(original);

        assertSame(original, aspect.narrowToCaller(call));
    }

    @Test
    void anOutcomeThatIsNotAPageIsLeftAlone() throws Throwable {
        when(call.proceed()).thenReturn("something else");

        assertEquals("something else", aspect.narrowToCaller(call));
    }

    @Test
    void aReadByIdHandsAnothersContactBackAsNothing() throws Throwable {
        when(scope.belongsToCaller("7", CALLER)).thenReturn(false);
        when(call.proceed()).thenReturn(contact(2L, "7"));

        assertNull(aspect.withholdOthersRow(call));
    }

    @Test
    void aCreateIsStampedByTheCallerAfterTheInsert() throws Throwable {
        when(call.proceed()).thenReturn(9L);

        assertEquals(9L, aspect.stampCreator(call));

        ArgumentCaptor<Demo01ContactDO> stamp = ArgumentCaptor.forClass(Demo01ContactDO.class);
        verify(mapper).updateById(stamp.capture());
        assertEquals(9L, stamp.getValue().getId());
        assertEquals("42", stamp.getValue().getCreator(),
                "without this the row lands with an empty creator and is invisible to its owner");
    }

    @Test
    void withNoCallerTheCreateIsNotStamped() throws Throwable {
        when(scope.creatingCaller()).thenReturn(null);
        when(call.proceed()).thenReturn(9L);

        aspect.stampCreator(call);

        verify(mapper, never()).updateById(any(Demo01ContactDO.class));
    }

    @Test
    void anUpdateOfAnothersContactIsRefusedBeforeItRuns() throws Throwable {
        Demo01ContactSaveReqVO request = new Demo01ContactSaveReqVO();
        request.setId(9L);
        when(call.getArgs()).thenReturn(new Object[]{request});
        when(mapper.selectById(9L)).thenReturn(contact(9L, "7"));
        doThrow(new RuleDeniedException(null, "该行不在你的数据范围内", "Demo01Contact", "update", null))
                .when(scope).requireOwned("7", CALLER, "Demo01Contact", "update");

        assertThrows(RuleDeniedException.class, () -> aspect.requireOwnership(call));
        verify(call, never()).proceed();
    }

    @Test
    void anUpdateOfTheCallersOwnContactProceeds() throws Throwable {
        Demo01ContactSaveReqVO request = new Demo01ContactSaveReqVO();
        request.setId(9L);
        when(call.getArgs()).thenReturn(new Object[]{request});
        when(mapper.selectById(9L)).thenReturn(contact(9L, "42"));

        aspect.requireOwnership(call);

        verify(scope).requireOwned("42", CALLER, "Demo01Contact", "update");
        verify(call).proceed();
    }

    @Test
    void theModuleDeclaresItselfAndTheColumnItReads() {
        assertEquals("infra", aspect.module());
        assertEquals("creator", aspect.ownerField());
    }

    private static Demo01ContactDO contact(long id, String creator) {
        Demo01ContactDO row = new Demo01ContactDO();
        row.setId(id);
        row.setCreator(creator);
        return row;
    }
}
