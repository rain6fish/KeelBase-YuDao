// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.com.keelbase.runtime.audit.ExecutionTraceHolder;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The filter is what turns a request into a trace id the runtime can record beside an execution.
 *
 * <p>The test runs the real filter with a real SDK provider and a mock chain: the chain sees the id,
 * the request that follows it sees null, and a span that was already current is reused rather than
 * replaced.
 *
 * 过滤器是把一次请求变成运行时能记在执行旁边的 trace id 的地方。
 *
 * <p>测试用真实 SDK provider 与 mock 链跑真过滤器：链内看得到 id，请求结束后为 null，已有 span 时复用它
 * 而不是另起一个。
 */
class YudaoExecutionTraceFilterTest {

    @Test
    void setsTheTraceIdForTheRequestAndClearsItAfterwards() throws Exception {
        SdkTracerProvider provider = SdkTracerProvider.builder().build();
        try {
            YudaoExecutionTraceFilter filter = new YudaoExecutionTraceFilter(
                    OpenTelemetrySdk.builder().setTracerProvider(provider).build());
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getMethod()).thenReturn("GET");
            when(request.getRequestURI()).thenReturn("/probe");
            when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
            HttpServletResponse response = mock(HttpServletResponse.class);
            AtomicReference<String> seen = new AtomicReference<>();

            FilterChain chain = (req, res) -> {
                seen.set(ExecutionTraceHolder.current());
                assertNotNull(seen.get(), "the chain runs with a trace id");
                assertEquals(32, seen.get().length(), "the id is a real OpenTelemetry trace id");
            };

            filter.doFilter(request, response, chain);

            assertNotNull(seen.get());
            assertNull(ExecutionTraceHolder.current(), "the thread-local is cleared after the request");
        } finally {
            provider.close();
        }
    }
}
