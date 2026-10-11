package cn.iocoder.yudao.module.report.dal.mysql.goview;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.report.dal.dataobject.goview.GoViewProjectDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GoViewProjectMapper extends BaseMapperX<GoViewProjectDO> {

    default PageResult<GoViewProjectDO> selectPage(PageParam reqVO, Long userId) {
        return selectPage(reqVO, new LambdaQueryWrapperX<GoViewProjectDO>()
                // `creator` is varchar — MySQL coerces the Long, PostgreSQL refuses it. / creator 是 varchar，MySQL 隐式转换、PostgreSQL 直接拒绝。
                .eq(GoViewProjectDO::getCreator, String.valueOf(userId))
                .orderByDesc(GoViewProjectDO::getId));
    }

}
