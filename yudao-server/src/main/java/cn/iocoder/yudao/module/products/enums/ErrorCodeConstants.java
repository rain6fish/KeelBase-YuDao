package cn.iocoder.yudao.module.products.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * products 错误码枚举类
 *
 * <p>Yudao's codegen emits this as a hand-paste snippet and does not compile it. In the RuoYi host the
 * equivalent step was the naming UPDATEs against the generator's own tables; here it is one class the
 * adapter must add. The block is kept out of the numbered ranges Yudao's own modules use.
 *
 * Yudao 的 codegen 把这一类当**手工粘贴片段**给出、并不编译它。在 RuoYi 宿主上对应的一步是对生成器
 * 自己那两张表的命名 UPDATE；在这里则是适配器必须补一个类。号段避开芋道自有模块已用的区间。
 */
public interface ErrorCodeConstants {

    // ========== 产品（生成）1-099-000-000 ==========
    ErrorCode PRODUCTS_NOT_EXISTS = new ErrorCode(1_099_000_000, "产品不存在");

}
