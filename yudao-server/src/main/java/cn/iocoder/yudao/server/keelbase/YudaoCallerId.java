// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.identity.ActingCallerId;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import org.springframework.stereotype.Component;

/**
 * This host's binding of the runtime's caller-id rule: <b>where to ask</b> for the request-side
 * identity. The order, the fallback and the parsing live in {@link ActingCallerId}; what stays here is
 * the one line only this host can write — {@code SecurityFrameworkUtils}. A second host writes its own
 * and the shape is shared. As of {@code D34} it is shared, rather than copied.
 *
 * 本宿主对运行时那条「调用方 id」规则的**绑定**：请求侧身份**去问哪里**。次序、回落与解析都在
 * {@link ActingCallerId} 里；留在这里的，只有**这宿主才写得出的那一行**——{@code SecurityFrameworkUtils}。
 * 第二个宿主写它自己那份，而**形状是共用的**——{@code D34} 之后是**真共用**，不再是一人一份的抄本。
 */
@Component
public class YudaoCallerId {

    public Long current() {
        return ActingCallerId.current(SecurityFrameworkUtils::getLoginUserId);
    }
}
