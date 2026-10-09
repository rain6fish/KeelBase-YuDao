package cn.iocoder.yudao.module.products.service.products;

import java.util.*;
import jakarta.validation.*;
import cn.iocoder.yudao.module.products.controller.admin.products.vo.*;
import cn.iocoder.yudao.module.products.dal.dataobject.products.ProductsDO;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;

/**
 * 产品（生成） Service 接口
 *
 * @author 芋道源码
 */
public interface ProductsService {

    /**
     * 创建产品（生成）
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createProducts(@Valid ProductsSaveReqVO createReqVO);

    /**
     * 更新产品（生成）
     *
     * @param updateReqVO 更新信息
     */
    void updateProducts(@Valid ProductsSaveReqVO updateReqVO);

    /**
     * 删除产品（生成）
     *
     * @param id 编号
     */
    void deleteProducts(Long id);

    /**
    * 批量删除产品（生成）
    *
    * @param ids 编号
    */
    void deleteProductsListByIds(List<Long> ids);

    /**
     * 获得产品（生成）
     *
     * @param id 编号
     * @return 产品（生成）
     */
    ProductsDO getProducts(Long id);

    /**
     * 获得产品（生成）分页
     *
     * @param pageReqVO 分页查询
     * @return 产品（生成）分页
     */
    PageResult<ProductsDO> getProductsPage(ProductsPageReqVO pageReqVO);

}