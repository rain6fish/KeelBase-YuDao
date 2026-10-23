// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyWrapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * {@link YudaoAdminRequestBodyCache} 的测试：管理端 JSON 请求体**能被读两次**，且只覆盖管理端那一段 JSON。
 *
 * <p>断言的是本类 Javadoc 写下的承诺：过滤器注册在上游缓存过滤器**同一个位置**（两个都跑、各覆盖对方
 * 跳过的那一段）；只筛 {@code /admin/} 前缀**且** JSON —— 上游排除名单带走的正是这一段，其余请求仍交给
 * 上游的过滤器；被筛到的请求交给下游时已是 {@link CacheRequestBodyWrapper}，正文读两次得到同一份内容。
 *
 * <p>Tests for {@link YudaoAdminRequestBodyCache}: an admin JSON body is readable twice, and the filter
 * covers only the admin JSON slice. They assert the promises in the class Javadoc: the filter is
 * registered in the <b>same slot</b> as upstream's cache filter (both run, each covering what the other
 * skips); it selects the {@code /admin/} prefix <b>and</b> JSON only — the slice upstream's ignore list
 * took out, leaving the rest to upstream's filter; and a selected request reaches the chain as a
 * {@link CacheRequestBodyWrapper} whose body reads the same twice.
 */
class YudaoAdminRequestBodyCacheTest {

    private static MockHttpServletRequest request(String uri, String contentType) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        request.setContentType(contentType);
        request.setContent("{\"message\":\"hi\"}".getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void theFilterIsRegisteredInTheSlotUpstreamsCacheFilterOccupies() {
        FilterRegistrationBean<YudaoAdminRequestBodyCache.AdminRequestBodyCacheFilter> registration =
                new YudaoAdminRequestBodyCache().adminRequestBodyCacheFilter();

        assertNotNull(registration.getFilter());
        assertInstanceOf(YudaoAdminRequestBodyCache.AdminRequestBodyCacheFilter.class, registration.getFilter());
        assertEquals(WebFilterOrderEnum.REQUEST_BODY_CACHE_FILTER, registration.getOrder());
    }

    @Test
    void onlyTheAdminPrefixAndJsonIsFiltered() {
        YudaoAdminRequestBodyCache.AdminRequestBodyCacheFilter filter = newFilter();

        assertFalse(filter.shouldNotFilter(request("/admin/ai/chat/stream", "application/json")),
                "an admin JSON request is the surface this filter exists for");
        assertTrue(filter.shouldNotFilter(request("/ai/chat/stream", "application/json")),
                "the sibling endpoint upstream does cache is left to upstream's filter");
        assertTrue(filter.shouldNotFilter(request("/admin/ai/chat/stream", "application/x-www-form-urlencoded")),
                "a non-JSON admin request has no JSON body to cache");
        assertTrue(filter.shouldNotFilter(request("/administrator/x", "application/json")),
                "the prefix is kept back to what the host actually serves under /admin/");
    }

    @Test
    void aSelectedRequestReachesTheChainWithABodyThatReadsTwice() throws Exception {
        YudaoAdminRequestBodyCache.AdminRequestBodyCacheFilter filter = newFilter();
        FilterChain chain = mock(FilterChain.class);
        HttpServletRequest incoming = request("/admin/ai/chat/stream", "application/json");
        HttpServletResponse response = mock(HttpServletResponse.class);

        filter.doFilterInternal(incoming, response, chain);

        ArgumentCaptor<ServletRequest> passed = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(passed.capture(), any(ServletResponse.class));
        CacheRequestBodyWrapper wrapper = assertInstanceOf(CacheRequestBodyWrapper.class, passed.getValue());
        assertEquals("{\"message\":\"hi\"}", read(wrapper));
        assertEquals("{\"message\":\"hi\"}", read(wrapper), "the second read must return the same body");
    }

    private static String read(CacheRequestBodyWrapper wrapper) throws Exception {
        try (BufferedReader reader = wrapper.getReader()) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private static YudaoAdminRequestBodyCache.AdminRequestBodyCacheFilter newFilter() {
        return new YudaoAdminRequestBodyCache().adminRequestBodyCacheFilter().getFilter();
    }
}
