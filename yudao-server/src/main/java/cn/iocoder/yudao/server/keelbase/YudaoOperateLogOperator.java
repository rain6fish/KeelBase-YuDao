// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.identity.ActingCallerId;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.operatelog.core.service.OperateLogOperatorSource;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import org.springframework.stereotype.Component;

/**
 * 本宿主对「操作日志里『谁』去哪儿问」的**绑定**：把运行时那条**已经裁决过次序**的规则接进来。
 *
 * <p>次序原样复用 {@link ActingCallerId} —— **请求侧优先、其次看运行时发布的调用方** —— 而不是在这里另写一遍。
 * 这与 {@link YudaoCallerId}（反方向：把宿主请求里的身份交给运行时）是**同一个形状**，故两处不会各自漂移。
 *
 * <p>怎么会走到这里：AI 的工具路径跑在安全过滤器链之外，{@code SecurityFrameworkUtils} 在那里答不出调用方，
 * 而调用者其实**在工具手里** —— 运行时把它发布在别处，{@code ActingCallerId} 正是读那个地方的两处规则之一。
 * 所以这条接缝接上之后，一次由 AI 写下的业务记录在宿主自己的操作日志里**挂得上人**，「谁」那一列不再只能留空。
 *
 * <p>用户类型取 **{@code ADMIN}**：本部署的调用方一律来自芋道的管理端令牌存储（见 {@link
 * YudaoCallerAuthenticator} 直接问 {@code OAuth2TokenCommonApi}），故它在宿主自己的模型里就是一个管理端用户 ——
 * 这也正是操作日志页把 {@code userId} 译成昵称时所用的那张表。
 */
@Component
public class YudaoOperateLogOperator implements OperateLogOperatorSource {

    @Override
    public LoginUser currentOperator() {
        Long userId = ActingCallerId.current(SecurityFrameworkUtils::getLoginUserId);
        if (userId == null) {
            return null;
        }
        LoginUser operator = new LoginUser();
        operator.setId(userId);
        operator.setUserType(UserTypeEnum.ADMIN.getValue());
        return operator;
    }
}
