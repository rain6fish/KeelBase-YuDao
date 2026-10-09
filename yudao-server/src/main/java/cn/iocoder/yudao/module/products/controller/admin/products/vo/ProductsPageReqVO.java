package cn.iocoder.yudao.module.products.controller.admin.products.vo;

import lombok.*;
import java.util.*;
import io.swagger.v3.oas.annotations.media.Schema;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 产品（生成）分页 Request VO")
@Data
public class ProductsPageReqVO extends PageParam {

    @Schema(description = "产品名称", example = "芋艿")
    private String name;

    @Schema(description = "价格", example = "24883")
    private Long price;

    @Schema(description = "状态", example = "1")
    private String status;

    @Schema(description = "属主用户", example = "3510")
    private String ownerUserId;

    @Schema(description = "所属组织", example = "3431")
    private Long orgId;

    @Schema(description = "所属部门", example = "24361")
    private Long deptId;

    @Schema(description = "创建时间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;

}