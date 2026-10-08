package cn.iocoder.yudao.module.products.dal.mysql.products;

import java.util.*;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.products.dal.dataobject.products.ProductsDO;
import org.apache.ibatis.annotations.Mapper;
import cn.iocoder.yudao.module.products.controller.admin.products.vo.*;

/**
 * äº§å“ï¼ˆç”Ÿæˆï¼‰ Mapper
 *
 * @author spike
 */
@Mapper
public interface ProductsMapper extends BaseMapperX<ProductsDO> {

    default PageResult<ProductsDO> selectPage(ProductsPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<ProductsDO>()
                .likeIfPresent(ProductsDO::getName, reqVO.getName())
                .eqIfPresent(ProductsDO::getPrice, reqVO.getPrice())
                .eqIfPresent(ProductsDO::getStatus, reqVO.getStatus())
                .eqIfPresent(ProductsDO::getOwnerUserId, reqVO.getOwnerUserId())
                .eqIfPresent(ProductsDO::getOrgId, reqVO.getOrgId())
                .eqIfPresent(ProductsDO::getDeptId, reqVO.getDeptId())
                .betweenIfPresent(ProductsDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(ProductsDO::getId));
    }

}