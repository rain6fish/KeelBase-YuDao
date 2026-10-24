// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.module.ai1.harness.llm;

import cn.com.keelbase.runtime.audit.ExecutionTraceHolder;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.function.Supplier;

/**
 * 把投递任务时捕获的 trace id 带到工具**真正执行**的那条线程上。
 *
 * <p>任务投递线程把 trace 放进 {@link ExecutionTraceHolder}，但模型工具循环跑在 Reactor 的调度线程
 * （实测是 {@code boundedElastic-N}），ThreadLocal 不会跨线程过去——于是工具写下的审计行与关联行
 * 拿不到 trace。这个装饰器在构造时记下 id，在 {@code call} 前后 set/clear，故下游看到的是这一轮的
 * trace，而不是这条池线程上一个任务的残留。
 *
 * <p>Clears in {@code finally} for the same reason as the caller context: the pool thread picks up
 * another task afterwards. A blank id delegates untouched.
 *
 * 让每次工具调用带上投递任务的 trace；空 id 时原样透传。线程池线程在调用后被清干净，不会漏给下一个任务。
 */
public final class TraceScopedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final String traceId;

    public TraceScopedToolCallback(ToolCallback delegate, String traceId) {
        this.delegate = delegate;
        this.traceId = traceId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return withTrace(() -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return withTrace(() -> delegate.call(toolInput, toolContext));
    }

    private String withTrace(Supplier<String> body) {
        if (traceId == null || traceId.isBlank()) {
            return body.get();
        }
        ExecutionTraceHolder.set(traceId);
        try {
            return body.get();
        } finally {
            ExecutionTraceHolder.clear();
        }
    }
}
