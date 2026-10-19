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

    /** 运行时里放行工具执行的入口。 / The runtime entries where a tool may execute. */
    static final List<String> EXECUTION_PATHS = List.of(
            "/ai/chat",
            "/ai/chat/stream",
            "/ai/task");

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
                .addPathPatterns("/ai/chat", "/ai/chat/stream", "/ai/task",
                        "/ai/confirmations/**", "/ai/my/confirmations/**");
    }
}
