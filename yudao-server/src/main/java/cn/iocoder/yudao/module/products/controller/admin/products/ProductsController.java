package cn.iocoder.yudao.module.products.controller.admin.products;

import org.springframework.web.bind.annotation.*;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;

import jakarta.validation.constraints.*;
import jakarta.validation.*;
import jakarta.servlet.http.*;
import java.util.*;
import java.io.IOException;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import static cn.iocoder.yudao.framework.apilog.core.enums.OperateTypeEnum.*;

import cn.iocoder.yudao.module.products.controller.admin.products.vo.*;
import cn.iocoder.yudao.module.products.dal.dataobject.products.ProductsDO;
import cn.iocoder.yudao.module.products.service.products.ProductsService;

@Tag(name = "管理后台 - äº§å“ï¼ˆç”Ÿæˆï¼‰")
@RestController
@RequestMapping("/products/products")
@Validated
public class ProductsController {

    @Resource
    private ProductsService productsService;

    @PostMapping("/create")
    @Operation(summary = "创建äº§å“ï¼ˆç”Ÿæˆï¼‰")
    @PreAuthorize("@ss.hasPermission('products:products:create')")
    public CommonResult<Long> createProducts(@Valid @RequestBody ProductsSaveReqVO createReqVO) {
        return success(productsService.createProducts(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新äº§å“ï¼ˆç”Ÿæˆï¼‰")
    @PreAuthorize("@ss.hasPermission('products:products:update')")
    public CommonResult<Boolean> updateProducts(@Valid @RequestBody ProductsSaveReqVO updateReqVO) {
        productsService.updateProducts(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除äº§å“ï¼ˆç”Ÿæˆï¼‰")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('products:products:delete')")
    public CommonResult<Boolean> deleteProducts(@RequestParam("id") Long id) {
        productsService.deleteProducts(id);
        return success(true);
    }

    @DeleteMapping("/delete-list")
    @Parameter(name = "ids", description = "编号", required = true)
    @Operation(summary = "批量删除äº§å“ï¼ˆç”Ÿæˆï¼‰")
                @PreAuthorize("@ss.hasPermission('products:products:delete')")
    public CommonResult<Boolean> deleteProductsList(@RequestParam("ids") List<Long> ids) {
        productsService.deleteProductsListByIds(ids);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得äº§å“ï¼ˆç”Ÿæˆï¼‰")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('products:products:query')")
    public CommonResult<ProductsRespVO> getProducts(@RequestParam("id") Long id) {
        ProductsDO products = productsService.getProducts(id);
        return success(BeanUtils.toBean(products, ProductsRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得äº§å“ï¼ˆç”Ÿæˆï¼‰分页")
    @PreAuthorize("@ss.hasPermission('products:products:query')")
    public CommonResult<PageResult<ProductsRespVO>> getProductsPage(@Valid ProductsPageReqVO pageReqVO) {
        PageResult<ProductsDO> pageResult = productsService.getProductsPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, ProductsRespVO.class));
    }

    @GetMapping("/export-excel")
    @Operation(summary = "导出äº§å“ï¼ˆç”Ÿæˆï¼‰ Excel")
    @PreAuthorize("@ss.hasPermission('products:products:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void exportProductsExcel(@Valid ProductsPageReqVO pageReqVO,
              HttpServletResponse response) throws IOException {
        pageReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<ProductsDO> list = productsService.getProductsPage(pageReqVO).getList();
        // 导出 Excel
        ExcelUtils.write(response, "äº§å“ï¼ˆç”Ÿæˆï¼‰.xls", "数据", ProductsRespVO.class,
                        BeanUtils.toBean(list, ProductsRespVO.class));
    }

}