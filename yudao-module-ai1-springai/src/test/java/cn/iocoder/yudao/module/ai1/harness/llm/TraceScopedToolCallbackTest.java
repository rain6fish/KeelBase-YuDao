// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.module.ai1.harness.llm;

import cn.com.keelbase.runtime.audit.ExecutionTraceHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 装饰器把投递线程捕获的 trace 带到工具执行线程，并在调用后清掉。
 *
 * <p>The decorator carries the submitting thread's trace onto the tool thread and clears it afterwards.
 */
class TraceScopedToolCallbackTest {

    @AfterEach
    void clearHolder() {
        ExecutionTraceHolder.clear();
    }

    @Test
    void callCarriesTheCapturedTraceAndClearsAfterwards() {
        AtomicReference<String> seen = new AtomicReference<>();
        ToolCallback scoped = new TraceScopedToolCallback(new FakeTool(seen), "trace-1");

        assertEquals("ok", scoped.call("{}"));
        assertEquals("trace-1", seen.get());
        assertNull(ExecutionTraceHolder.current(), "the pool thread must not keep the trace");
    }

    @Test
    void toolContextOverloadIsScopedToo() {
        AtomicReference<String> seen = new AtomicReference<>();
        ToolCallback scoped = new TraceScopedToolCallback(new FakeTool(seen), "trace-2");

        assertEquals("ok", scoped.call("{}", new ToolContext(Map.of())));
        assertEquals("trace-2", seen.get());
        assertNull(ExecutionTraceHolder.current());
    }

    @Test
    void blankTraceDelegatesWithoutClearingAnOuterTrace() {
        ExecutionTraceHolder.set("outer");
        AtomicReference<String> seen = new AtomicReference<>();
        ToolCallback scoped = new TraceScopedToolCallback(new FakeTool(seen), " ");

        assertEquals("ok", scoped.call("{}"));
        assertEquals("outer", seen.get());
        assertEquals("outer", ExecutionTraceHolder.current());
    }

    private static final class FakeTool implements ToolCallback {

        private final AtomicReference<String> seen;

        private FakeTool(AtomicReference<String> seen) {
            this.seen = seen;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return null;
        }

        @Override
        public String call(String toolInput) {
            seen.set(ExecutionTraceHolder.current());
            return "ok";
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            seen.set(ExecutionTraceHolder.current());
            return "ok";
        }
    }
}
