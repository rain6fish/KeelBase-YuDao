// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import org.springframework.stereotype.Component;

/**
 * Who is acting, read from wherever this host keeps it — the same two places the RuoYi adapter read,
 * in the same order.
 *
 * <p>On a request that came through Yudao's own chain the login user is in the security context. On a
 * KeelBase path it is not: the runtime's chain is scoped to its own controllers, Yudao's filter never
 * ran, and the runtime carries the caller on {@link ActingCaller} instead. Reading the first and
 * falling back to the second is what makes one governance aspect cover both paths.
 *
 * 谁在行动，从**这个宿主放它的地方**读——与 RuoYi 适配器读的同样是那两处、同样那个顺序。
 *
 * <p>经芋道自己那条链进来的请求，登录用户在安全上下文里。在 KeelBase 路径上则不在：运行时的链只认自己的
 * 控制器、芋道的过滤器**从未运行**，而运行时把调用方放在 {@link ActingCaller} 上。先读前者、再回落后者，
 * 才使**一个**治理切面同时盖住两条路。
 */
@Component
public class YudaoCallerId {

    public Long current() {
        try {
            Long fromRequest = SecurityFrameworkUtils.getLoginUserId();
            if (fromRequest != null) {
                return fromRequest;
            }
        } catch (RuntimeException noSecurityContext) {
            // No authentication in context: fall through to the acting caller. / 上下文里没有认证：落到调用方。
        }
        String acting = ActingCaller.current();
        if (acting == null) {
            return null;
        }
        try {
            return Long.valueOf(acting);
        } catch (NumberFormatException notAnId) {
            return null;
        }
    }
}
