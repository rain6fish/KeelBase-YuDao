package cn.iocoder.yudao.module.products.controller.admin.products.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDateTime;
import cn.idev.excel.annotation.*;

@Schema(description = "管理后台 - äº§å“ï¼ˆç”Ÿæˆï¼‰ Response VO")
@Data
@ExcelIgnoreUnannotated
public class ProductsRespVO {

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "24933")
    @ExcelProperty("编号")
    private Long id;

    @Schema(description = "产品名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "李四")
    @ExcelProperty("产品名称")
    private String name;

    @Schema(description = "价格", example = "24943")
    @ExcelProperty("价格")
    private Long price;

    @Schema(description = "状态", example = "2")
    @ExcelProperty("状态")
    private String status;

    @Schema(description = "属主用户", requiredMode = Schema.RequiredMode.REQUIRED, example = "5308")
    @ExcelProperty("属主用户")
    private String ownerUserId;

    @Schema(description = "所属组织", example = "15400")
    @ExcelProperty("所属组织")
    private Long orgId;

    @Schema(description = "所属部门", example = "17162")
    @ExcelProperty("所属部门")
    private Long deptId;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("创建时间")
    private LocalDateTime createTime;

}