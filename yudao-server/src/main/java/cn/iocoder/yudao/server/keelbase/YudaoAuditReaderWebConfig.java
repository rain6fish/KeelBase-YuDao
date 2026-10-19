// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把审计读者的执行入口守卫挂到运行时的那几条路径上。
 *
 * <p>清单与校验器同源：`YudaoAuditReaderExecutionGuard` 用这里的常量裁决，故「挂上了哪些路径」与「挡了哪些
 * 路径」不会各说各话。`scripts/check-audit-reader-execution-guard.mjs` 对这三处（规则源、守卫、本注册）
 * 做静态对账，防止清单漂走。
 *
 * <p>英文：
 *
 * <p>Hangs the audit reader's execution-entry guard on the runtime's paths.
 *
 * <p>The list is the same source the guard decides from — the guard reads these constants — so "which
 * paths are registered" and "which paths are refused" cannot drift apart.
 * `scripts/check-audit-reader-execution-guard.mjs` reconciles the three places (rule source, guard,
 * this registration) statically so the list cannot quietly change.
 */
@Configuration
public class YudaoAuditReaderWebConfig implements WebMvcConfigurer {

    /**
     * 放行工具执行的入口：运行时那三条，以及宿主 AI 模块的两条与 AI1 会话流。
     *
     * <p>宿主 AI 模块的 `/admin-api/ai/chat/message/send`（及其流式兄弟）**没有** {@code @PreAuthorize}，
     * 却把角色的工具回调交给模型；AI1 的 `/admin-api/ai1/session/message/send-stream` 会装配 MCP、SKILL 与
     * 受治理的本地工具。前者今天靠表缺失才没执行，后者靠 `ai1:session:my` 才没执行——两者都仍然是对审计读者
     * **可达的形状**，故一并挡在这里，而不是依赖一张可能被误授的权限串。
     *
     * <p>英文：
     *
     * <p>The entries where a tool may execute: the runtime's three, plus the host AI module's two and
     * the AI1 session stream. The host AI module's `/admin-api/ai/chat/message/send` (and its streaming
     * sibling) carries **no** {@code @PreAuthorize} yet hands a role's tool callbacks to the model; AI1's
     * `/admin-api/ai1/session/message/send-stream` assembles MCP, SKILL and governed local tools. The
     * former only avoids execution today because its tables are absent, the latter only because of
     * `ai1:session:my` — both remain **reachable shapes** for an audit reader, so they are refused here
     * rather than left to a permission string that could be granted by mistake.
     */
    static final List<String> EXECUTION_PATHS = List.of(
            "/ai/chat",
            "/ai/chat/stream",
            "/ai/task",
            "/admin-api/ai/chat/message/send",
            "/admin-api/ai/chat/message/send-stream",
            "/admin-api/ai1/session/message/send-stream");

    /** 确认的写侧前缀：GET 之外的请求在这里裁决或再执行。 / The write-side confirmation prefixes: non-GET requests decide or re-execute here. */
    static final List<String> DECISION_PREFIXES = List.of(
            "/ai/confirmations",
            "/ai/my/confirmations");

    private final YudaoAuditReaderExecutionGuard guard;

    public YudaoAuditReaderWebConfig(YudaoAuditReaderExecutionGuard guard) {
        this.guard = guard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guard)
                .addPathPatterns(
                        "/ai/chat", "/ai/chat/stream", "/ai/task",
                        "/admin-api/ai/chat/message/send",
                        "/admin-api/ai/chat/message/send-stream",
                        "/admin-api/ai1/session/message/send-stream",
                        "/ai/confirmations/**", "/ai/my/confirmations/**");
    }
}
