// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyWrapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes an {@code /admin/**} JSON body readable twice, so the runtime's admin routes work off-prod.
 *
 * <p>Upstream's {@code CacheRequestBodyFilter} caches the request body for everything except URIs it
 * ignores — and its ignore list is <b>{@code /admin/}</b> and {@code /actuator/}, put there to protect
 * Spring Boot Admin (upstream cites its issue 795). <b>This host does not run Spring Boot Admin</b>, so
 * that first entry protects nothing here and instead skips the runtime's own admin routes. Off-prod the
 * access-log interceptor then reads the body ({@code ApiAccessLogInterceptor} does it in its
 * {@code !isProd()} branch), and the {@code @RequestBody} resolver meets an already-closed stream:
 * {@code /admin/ai/chat/stream} answers a 500-carrying-200 while its sibling {@code /ai/chat/stream},
 * which upstream does cache, answers normally. The console's system assistant calls the admin one, so
 * on any non-prod deployment that assistant is entirely unusable — and, because the branch is
 * {@code !isProd()}, production never shows it. See {@code YD-17}.
 *
 * <p><b>Why here and not upstream.</b> The ignore entry is upstream's, and narrowing it would be a
 * change to upstream behaviour — a different kind of act from the wiring this host already accounts
 * for (see {@code YD-T4}). Wrapping from here reuses upstream's own {@link CacheRequestBodyWrapper} and
 * leaves that file alone; a deployment that does run Spring Boot Admin keeps the protection it was
 * written for. The platform is not the place either: the collision comes from this host's admin route,
 * and putting the wrapper in the runtime would charge every embedder for it.
 *
 * <p><b>What is left for this host to answer.</b> That an admin stream off-prod now delivers the whole
 * event sequence <em>and</em> that the access log still records a line — the fix may not be read as
 * licence to silence the access log. That is measured by {@code scripts/run-console-navigation-scenarios.mjs}
 * once it moves back to the admin route it was forced to sidestep.
 *
 * 让 {@code /admin/**} 的 JSON 请求体**能被读两次**，从而使运行时的管理端路由在非生产环境下可用。
 *
 * <p>上游的 {@code CacheRequestBodyFilter} 为除**排除名单**以外的一切缓存请求体，而那份名单里写着
 * **{@code /admin/}** 与 {@code /actuator/} —— 是为保护 **Spring Boot Admin** 加的（上游注释引了它的 issue
 * 795）。**本宿主不跑 Spring Boot Admin**，于是那条 `/admin/` 在这里什么都没保护，反倒把运行时**自己的**
 * 管理端路由跳过了。非生产环境下，访问日志拦截器随后把请求体读走（{@code ApiAccessLogInterceptor} 在它的
 * {@code !isProd()} 分支里读），{@code @RequestBody} 解析器于是碰到一个**已被关闭**的流：
 * {@code /admin/ai/chat/stream} 答出「包着 500 的 200」，而**上游确实会缓存**的兄弟端点
 * {@code /ai/chat/stream} 一切正常。控制台的系统助手走的正是管理端那条，故**任何非生产部署上那个助手
 * 整条不可用** —— 而因为那个分支是 {@code !isProd()}，**生产环境永远看不到**。见 `YD-17`。
 *
 * <p>**为什么修在这里、不修上游。** 那条排除项是上游的，收窄它等于**改上游行为** —— 与本宿主已在记账的
 * 那种「接线」不是一回事（见 `YD-T4`）。从这里包一层**复用上游自己的** {@link CacheRequestBodyWrapper}、
 * **不动那个文件**；将来真跑 Spring Boot Admin 的部署，仍保有那条排除原本提供的保护。也不该放平台侧：
 * 这个冲突源自**本宿主**的管理端路由，把包装放进运行时等于**让每一个嵌入方为它付代价**。
 *
 * <p>**本宿主还欠的回答。** 非生产下管理端流现在能送出**完整事件序列**，**且访问日志仍留行** —— 修法不得
 * 被读成「可以顺手把访问日志关掉」的许可。这一条由 `scripts/run-console-navigation-scenarios.mjs` 在
 * **改回它曾被迫绕开的那条管理端路由**之后来量。
 */
@Configuration
public class YudaoAdminRequestBodyCache {

    /**
     * The surface this exists for: the runtime's admin routes, which the console's assistant uses.
     *
     * 本条存在的理由所在的面：**运行时自己的**管理端路由 —— 控制台助手用的就是它。
     */
    private static final String ADMIN_PREFIX = "/admin/";

    @Bean
    FilterRegistrationBean<AdminRequestBodyCacheFilter> adminRequestBodyCacheFilter() {
        FilterRegistrationBean<AdminRequestBodyCacheFilter> registration =
                new FilterRegistrationBean<>(new AdminRequestBodyCacheFilter());
        // The same slot upstream's cache filter occupies: both run, each covering what the other skips.
        // 与上游那个缓存过滤器同一个位置：两个都跑，各覆盖对方跳过的那一段。
        registration.setOrder(WebFilterOrderEnum.REQUEST_BODY_CACHE_FILTER);
        return registration;
    }

    /**
     * Wraps an admin JSON request so it can be read more than once — the same thing upstream does for
     * every other JSON request, applied to the surface upstream's ignore list took out by accident.
     *
     * 把一个管理端 JSON 请求包成**可读多次** —— 上游对**其余**每个 JSON 请求都在做这件事，这里只是把它
     * 施于**那条被上游的排除名单顺手带走**的面。
     */
    static final class AdminRequestBodyCacheFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            chain.doFilter(new CacheRequestBodyWrapper(request), response);
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            // Only the admin surface, and only JSON — the same two conditions upstream applies, with the
            // prefix kept back to what this host actually serves under it.
            // 只管管理端那一段、且只管 JSON —— 与上游那两个条件相同，只是前缀收紧到本宿主确实挂在它下面的东西。
            return !request.getRequestURI().startsWith(ADMIN_PREFIX)
                    || !ServletUtils.isJsonRequest(request);
        }
    }
}
