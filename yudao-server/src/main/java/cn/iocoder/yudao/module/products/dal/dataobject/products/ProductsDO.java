package cn.iocoder.yudao.module.products.dal.dataobject.products;

import lombok.*;
import java.util.*;
import java.time.LocalDateTime;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.*;
import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;

/**
 * äº§å“ï¼ˆç”Ÿæˆï¼‰ DO
 *
 * @author spike
 */
@TableName("products")
@KeySequence("products_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductsDO extends BaseDO {

    /**
     * 编号
     */
    @TableId
    private Long id;
    /**
     * 产品名称
     */
    private String name;
    /**
     * 价格
     */
    private Long price;
    /**
     * 状态
     */
    private String status;
    /**
     * 属主用户
     */
    private String ownerUserId;
    /**
     * 所属组织
     */
    private Long orgId;
    /**
     * 所属部门
     */
    private Long deptId;


}