package cn.iocoder.yudao.module.products.service.products;

import java.util.*;
import jakarta.validation.*;
import cn.iocoder.yudao.module.products.controller.admin.products.vo.*;
import cn.iocoder.yudao.module.products.dal.dataobject.products.ProductsDO;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;

/**
 * äº§å“ï¼ˆç”Ÿæˆï¼‰ Service 接口
 *
 * @author spike
 */
public interface ProductsService {

    /**
     * 创建äº§å“ï¼ˆç”Ÿæˆï¼‰
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createProducts(@Valid ProductsSaveReqVO createReqVO);

    /**
     * 更新äº§å“ï¼ˆç”Ÿæˆï¼‰
     *
     * @param updateReqVO 更新信息
     */
    void updateProducts(@Valid ProductsSaveReqVO updateReqVO);

    /**
     * 删除äº§å“ï¼ˆç”Ÿæˆï¼‰
     *
     * @param id 编号
     */
    void deleteProducts(Long id);

    /**
    * 批量删除äº§å“ï¼ˆç”Ÿæˆï¼‰
    *
    * @param ids 编号
    */
    void deleteProductsListByIds(List<Long> ids);

    /**
     * 获得äº§å“ï¼ˆç”Ÿæˆï¼‰
     *
     * @param id 编号
     * @return äº§å“ï¼ˆç”Ÿæˆï¼‰
     */
    ProductsDO getProducts(Long id);

    /**
     * 获得äº§å“ï¼ˆç”Ÿæˆï¼‰分页
     *
     * @param pageReqVO 分页查询
     * @return äº§å“ï¼ˆç”Ÿæˆï¼‰分页
     */
    PageResult<ProductsDO> getProductsPage(ProductsPageReqVO pageReqVO);

}