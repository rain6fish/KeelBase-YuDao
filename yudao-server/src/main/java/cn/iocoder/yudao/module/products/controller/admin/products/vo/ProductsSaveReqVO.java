package cn.iocoder.yudao.module.products.controller.admin.products.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import java.util.*;
import jakarta.validation.constraints.*;

@Schema(description = "管理后台 - 产品（生成）新增/修改 Request VO")
@Data
public class ProductsSaveReqVO {

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "31906")
    private Long id;

    @Schema(description = "产品名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "赵六")
    @NotEmpty(message = "产品名称不能为空")
    private String name;

    @Schema(description = "价格", example = "32710")
    private Long price;

    @Schema(description = "状态", example = "2")
    private String status;

    @Schema(description = "属主用户", requiredMode = Schema.RequiredMode.REQUIRED, example = "29080")
    @NotEmpty(message = "属主用户不能为空")
    private String ownerUserId;

    @Schema(description = "所属组织", example = "2701")
    private Long orgId;

    @Schema(description = "所属部门", example = "9334")
    private Long deptId;

}