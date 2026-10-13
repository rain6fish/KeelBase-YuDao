// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.identity.IdentityEvidence;
import cn.com.keelbase.runtime.identity.IdentityResolver;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The showcase's two routes, guarded the same way the audit chain is: an administrator, or a
 * refusal. The demonstration drives the runtime as the caller who asks for it, so whoever may run it
 * decides whose tools are exercised — that is not a thing to hand to every logged-in user.
 *
 * <p><b>Why the caller is read the host's way rather than through the runtime's
 * {@code CurrentPrincipal}.</b> The runtime's security chain applies only to routes it owns, and it
 * decides ownership by the controller's package — {@code cn.com.keelbase.runtime}. This controller
 * lives in the host's package, so that chain never runs for it and nothing ever puts an
 * {@code AuthenticatedSubject} in the security context. The request is still authenticated (the
 * host's own filter chain did that), so the caller is read from there and resolved through the same
 * {@link IdentityResolver} the runtime uses — same subject, same resolver, different door.
 *
 * <p>An earlier version of this class asked {@code CurrentPrincipal} and answered 401 to a
 * perfectly valid administrator token; the cause is the paragraph above rather than a missing token,
 * which is why it is written down here instead of left to be rediscovered.
 *
 * 展示的两条路由，把守方式与审计链相同：管理员，否则拒绝。演示是**以请求者自己的身份**驱动运行时的，
 * 故谁可以跑它，就决定了**谁的工具**被演练——那不是能交给每个登录用户的事。
 *
 * <p><b>为什么调用方是**按宿主的方式**读的，而不是走运行时的 {@code CurrentPrincipal}。</b>
 * 运行时的安全链只施加于**它自己的**路由，而「是不是它的」由**控制器的包**决定 ——
 * {@code cn.com.keelbase.runtime}。本控制器住在**宿主的**包里，故那条链**从不为它运行**，也就没有东西
 * 把 {@code AuthenticatedSubject} 放进安全上下文。请求**仍然是认证过的**（宿主的过滤链做的），故调用方
 * 从那里读、并经**同一个** {@link IdentityResolver} 解析 —— 同一个主体、同一个解析器，只是换了道门。
 *
 * <p>本类早先的版本问的是 {@code CurrentPrincipal}，于是对一个**完全有效的管理员令牌**答了 401；成因是
 * 上面那一段、而不是令牌缺失 —— 这也是它被写在这里、而不是留给后人重新发现的原因。
 */
@RestController
public class SecurityShowcaseController {

    private final SecurityShowcase showcase;
    private final YudaoCallerId callers;
    private final IdentityResolver identities;

    public SecurityShowcaseController(SecurityShowcase showcase, YudaoCallerId callers,
                                      IdentityResolver identities) {
        this.showcase = showcase;
        this.callers = callers;
        this.identities = identities;
    }

    @GetMapping("/ai/security-showcase/scenarios")
    public List<Map<String, Object>> scenarios() {
        requireAdministrator();
        return showcase.listScenarios();
    }

    /**
     * Runs one scenario. An id this deployment does not know is answered as {@code unknown-scenario}
     * with HTTP 200 — it is a question about the showcase, not a missing resource, and a 404 would
     * make the console show a network error where the answer is "there is no such scenario".
     *
     * 跑其中一条。本部署不认识的 id 以 {@code unknown-scenario}、HTTP 200 作答——那是一个**关于展示
     * 本身**的问题，不是缺失的资源，而 404 会让控制台在一个答案明确是「没有这条场景」的地方显示网络错误。
     */
    @PostMapping("/ai/security-showcase/run/{id}")
    public Map<String, Object> run(@PathVariable String id) {
        return showcase.run(id, requireAdministrator());
    }

    private Principal requireAdministrator() {
        Long userId = callers.current();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no authenticated caller");
        }
        Principal caller = identities.resolve(
                IdentityEvidence.ofAuthenticatedSubject(String.valueOf(userId), null));
        if (!caller.isManager()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "the security showcase is run by an administrator");
        }
        return caller;
    }
}
