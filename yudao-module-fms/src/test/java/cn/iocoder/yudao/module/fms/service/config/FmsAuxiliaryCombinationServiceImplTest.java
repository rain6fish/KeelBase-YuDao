package cn.iocoder.yudao.module.fms.service.config;

import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.module.fms.dal.dataobject.config.FmsAuxiliaryCombinationDO;
import cn.iocoder.yudao.module.fms.dal.mysql.config.FmsAuxiliaryCombinationMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertPojoEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Import(FmsAuxiliaryCombinationServiceImpl.class)
public class FmsAuxiliaryCombinationServiceImplTest extends BaseDbUnitTest {

    @Resource
    private FmsAuxiliaryCombinationServiceImpl auxiliaryCombinationService;
    @Resource
    private FmsAuxiliaryCombinationMapper auxiliaryCombinationMapper;

    @Test
    public void testDeleteAuxiliaryCombinationByAuxiliaryItemIds() {
        // mock 数据
        FmsAuxiliaryCombinationDO.AuxiliaryItem deletedItem = FmsAuxiliaryCombinationDO.AuxiliaryItem.builder()
                .typeId(31L).itemId(41L).build();
        FmsAuxiliaryCombinationDO deletedCombination = new FmsAuxiliaryCombinationDO().setAccountSetId(1L)
                .setSubjectId(101L).setItems(Collections.singletonList(deletedItem));
        auxiliaryCombinationMapper.insert(deletedCombination);
        // 数据库时间列只存微秒，夹具显式给出微秒精度的值，避免自动填充的纳秒被存储层舍入
        // The database time columns store microseconds, so the fixture supplies microsecond-precision
        // values, keeping auto-filled nanoseconds from being rounded by the storage layer.
        LocalDateTime fixtureTime = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        FmsAuxiliaryCombinationDO retainedCombination = new FmsAuxiliaryCombinationDO().setAccountSetId(1L)
                .setSubjectId(101L).setItems(Collections.singletonList(
                        FmsAuxiliaryCombinationDO.AuxiliaryItem.builder().typeId(31L).itemId(42L).build()));
        retainedCombination.setCreateTime(fixtureTime);
        retainedCombination.setUpdateTime(fixtureTime);
        auxiliaryCombinationMapper.insert(retainedCombination);

        // 调用
        auxiliaryCombinationService.deleteAuxiliaryCombinationByAuxiliaryItemIds(
                1L, Collections.singletonList(41L));

        // 断言
        assertNull(auxiliaryCombinationMapper.selectById(deletedCombination.getId()));
        assertPojoEquals(retainedCombination,
                auxiliaryCombinationMapper.selectById(retainedCombination.getId()), "deleted");
    }

}
