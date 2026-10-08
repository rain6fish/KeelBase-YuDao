package cn.iocoder.yudao.module.products.service.products;

import cn.hutool.core.collection.CollUtil;
import org.springframework.stereotype.Service;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import cn.iocoder.yudao.module.products.controller.admin.products.vo.*;
import cn.iocoder.yudao.module.products.dal.dataobject.products.ProductsDO;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;

import cn.iocoder.yudao.module.products.dal.mysql.products.ProductsMapper;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.diffList;
import static cn.iocoder.yudao.module.products.enums.ErrorCodeConstants.*;

/**
 * äº§å“ï¼ˆç”Ÿæˆï¼‰ Service 实现类
 *
 * @author spike
 */
@Service
@Validated
public class ProductsServiceImpl implements ProductsService {

    @Resource
    private ProductsMapper productsMapper;

    @Override
    public Long createProducts(ProductsSaveReqVO createReqVO) {
        // 插入
        ProductsDO products = BeanUtils.toBean(createReqVO, ProductsDO.class);
        productsMapper.insert(products);

        // 返回
        return products.getId();
    }

    @Override
    public void updateProducts(ProductsSaveReqVO updateReqVO) {
        // 校验存在
        validateProductsExists(updateReqVO.getId());
        // 更新
        ProductsDO updateObj = BeanUtils.toBean(updateReqVO, ProductsDO.class);
        productsMapper.updateById(updateObj);
    }

    @Override
    public void deleteProducts(Long id) {
        // 校验存在
        validateProductsExists(id);
        // 删除
        productsMapper.deleteById(id);
    }

    @Override
        public void deleteProductsListByIds(List<Long> ids) {
        // 删除
        productsMapper.deleteByIds(ids);
        }


    private void validateProductsExists(Long id) {
        if (productsMapper.selectById(id) == null) {
            throw exception(PRODUCTS_NOT_EXISTS);
        }
    }

    @Override
    public ProductsDO getProducts(Long id) {
        return productsMapper.selectById(id);
    }

    @Override
    public PageResult<ProductsDO> getProductsPage(ProductsPageReqVO pageReqVO) {
        return productsMapper.selectPage(pageReqVO);
    }

}