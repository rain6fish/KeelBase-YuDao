// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.audit.ExecutionTraceHolder;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every host request a trace id and hands it to the runtime's audit sink.
 *
 * <p>When an upstream instrumenter already started a span, this uses it; otherwise it starts a
 * server span itself. The trace id is put on the runtime's thread-local for the duration of the
 * request, so an audit row written inside it can be paired with the host's own operation log, which
 * takes its trace id from the same span.
 *
 * 给每个宿主请求一个 trace id，并交给运行时的审计写入处。
 *
 * <p>上游已有 span 时直接使用；否则自己起一个 server span。请求期间把 trace id 放进运行时的线程本地值，
 * 于是请求内写下的审计行能与宿主自己的操作日志配对 —— 后者取的是同一个 span 的 trace id。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class YudaoExecutionTraceFilter extends OncePerRequestFilter {

    private final Tracer tracer;

    public YudaoExecutionTraceFilter(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("yudao-server");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Span current = Span.current();
        boolean created = !current.getSpanContext().isValid();
        Span span = created
                ? tracer.spanBuilder(request.getMethod() + " " + request.getRequestURI())
                        .setSpanKind(SpanKind.SERVER)
                        .startSpan()
                : current;
        try (Scope ignored = span.makeCurrent()) {
            ExecutionTraceHolder.set(span.getSpanContext().getTraceId());
            chain.doFilter(request, response);
        } finally {
            ExecutionTraceHolder.clear();
            if (created) {
                span.end();
            }
        }
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
