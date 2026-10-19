// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.identity.AuthenticatedSubject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 把审计读者挡在运行时那条**会执行工具**的入口之外。
 *
 * <p>为什么宿主得自己挡：运行时的 `/ai/chat`（及其流式兄弟 `/ai/chat/stream`、多步 `/ai/task`）**没有**
 * {@code @PreAuthorize}，而引擎的闸门只按风险级与治理策略裁决，**不查权限规则源**。于是一个只被授予六个治理读面
 * {@code read} 的账户，仍能在这里让 R1 工具真实执行；R3 写则生成一条 `immediate` 确认，而该账户**自己**就能裁它
 * （`/ai/confirmations/{token}` 只拒绝别人的行）——「不能写、不能审批、不能执行」于是在运行时入口上被绕开。
 *
 * <p>本守卫只做一件事：持有启用的 `audit_reader` 角色 ⇒ 拒绝这些执行入口。读面不挡：`GET /ai/confirmations`
 * 等仍是该角色的正当射程。角色读不出来时 `YudaoAuditReaderRules` 按「不持有」处理，故守卫失败即放行给运行时
 * 自己的身份处理；这一取舍与规则源一致——缺一次角色读只会收窄规则，不会在这里凭空放大权限。
 *
 * <p>清单里还有宿主自己的 AI 执行形状：AI 模块的 `/admin-api/ai/chat/message/send`（及其流式兄弟）
 * **没有** {@code @PreAuthorize}，却把角色的工具回调交给模型；AI1 的
 * `/admin-api/ai1/session/message/send-stream` 会装配 MCP、SKILL 与受治理的本地工具。它们出于同一个理由、
 * 由同一个开关拒绝。
 *
 * <p>英文：
 *
 * <p>Keeps the audit reader out of the runtime's <b>tool-executing</b> entries.
 *
 * <p>The list also carries the host's own AI execution shapes: the AI module's
 * `/admin-api/ai/chat/message/send` (and its stream) hands a role's tool callbacks to the model with no
 * `@PreAuthorize`, and AI1's `/admin-api/ai1/session/message/send-stream` assembles MCP, SKILL and
 * governed local tools. They are refused for the same reason and by the same switch.
 *
 * <p>Why the host must do it: the runtime's `/ai/chat` (and its streaming sibling `/ai/chat/stream` and
 * the multi-step `/ai/task`) carry **no** {@code @PreAuthorize}, and the engine's gate decides on risk
 * level and governance policy only — it **never consults the permission rule source**. An account
 * granted nothing but `read` on six governance faces could therefore still make an R1 tool run here;
 * an R3 write raises an `immediate` confirmation that the same account can decide itself
 * (`/ai/confirmations/{token}` refuses only somebody else's row). "Cannot write, cannot approve,
 * cannot execute" was thus bypassable at the runtime entry.
 *
 * <p>This guard does one thing: holding an enabled `audit_reader` role ⇒ refuse those execution
 * entries. Reads stay open — `GET /ai/confirmations` and friends are the role's proper reach. An
 * unreadable role is treated as "does not hold" by `YudaoAuditReaderRules`, so the guard lets the
 * request through to the runtime's own identity handling; that matches the rule source's stance, where
 * a missing role read narrows the rules and never widens a permission here.
 */
@Component
public class YudaoAuditReaderExecutionGuard implements HandlerInterceptor {

    private final YudaoAuditReaderRules rules;
    private final YudaoCallerAuthenticator callerAuthenticator;
    private final YudaoAdministrator administrator;

    public YudaoAuditReaderExecutionGuard(YudaoAuditReaderRules rules,
                                          YudaoCallerAuthenticator callerAuthenticator,
                                          YudaoAdministrator administrator) {
        this.rules = rules;
        this.callerAuthenticator = callerAuthenticator;
        this.administrator = administrator;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 运行时的链只认自己的路径，芋道的 `TokenAuthenticationFilter` 在 KeelBase 路径上**可能从不运行**，
        // 故 `SecurityFrameworkUtils` 在这里读到的是空。改用宿主自己的认证接缝——运行时拿到调用方走的也是它。
        // The runtime's chain is scoped to its own paths, so Yudao's `TokenAuthenticationFilter` **may never
        // run** on a KeelBase path and `SecurityFrameworkUtils` reads nothing here. Ask the host's own
        // authenticator instead — the same seam the runtime uses to establish the caller.
        AuthenticatedSubject caller = callerAuthenticator.authenticate(request);
        // 未认证的请求交给运行时自己的身份处理；本守卫只回答「已认证的审计读者」这一问。
        // An unauthenticated request goes to the runtime's own identity handling; this guard answers one
        // question only — "an authenticated audit reader".
        if (caller == null || caller.subject() == null || !rules.isAuditReader(caller.subject())) {
            return true;
        }
        // 规则源对管理员不看 `audit_reader`（管理员拿通配）；守卫照同一口径：管理员即便挂着该角色，也仍是他自己。
        // The rule source ignores `audit_reader` for administrators (they get the wildcard); the guard keeps
        // the same reading: an administrator holding the role is still themselves.
        try {
            if (administrator.isAdmin(Long.valueOf(caller.subject()))) {
                return true;
            }
        } catch (NumberFormatException notAnId) {
            return true;
        }
        String path = pathOf(request);
        boolean executionEntry = YudaoAuditReaderWebConfig.EXECUTION_PATHS.contains(path);
        // 确认的**写**入口：GET 是队列读面、仍属该角色；POST 是裁决/再执行，属执行面。
        // The **write** side of confirmations: GET is the queue read and stays in the role; POST decides
        // or re-executes and belongs to the execution face.
        boolean decisionEntry = !"GET".equalsIgnoreCase(request.getMethod())
                && YudaoAuditReaderWebConfig.DECISION_PREFIXES.stream().anyMatch(path::startsWith);
        if (executionEntry || decisionEntry) {
            throw new AccessDeniedException(
                    "the audit reader is a reader: it cannot reach the AI execution entries");
        }
        return true;
    }

    /** 去掉上下文路径的请求路径，好让登记的模式与拦截器的匹配对上。 / The request path without its context path, so it matches the registered patterns. */
    private static String pathOf(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && path.startsWith(context)
                ? path.substring(context.length())
                : path;
    }
}
