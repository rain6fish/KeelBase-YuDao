// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.module.ai1.harness.mcp;

import java.util.function.Supplier;

/**
 * 一次**由模型发出的工具调用**属于哪条会话，在这条调用期间有效。
 *
 * <p>与工厂已经发布的那个「调用方」**同形、也同理**：装配发生在那条**知道会话**的线程上，而模型驱动工具循环用的是
 * 一条**从未有过会话**的线程。一次受治理的调用若说不出自己来自哪一轮，就没有读者能把它放回原处 —— 审计里那一行会
 * 永远停在「这个调用方最近的调用」，而那是**另一个、也更弱**的问题。
 *
 * <p>它是**线程局部**的，且每条 body 之后**还原**（而不是一律清除）：同一条池线程接着领到的任务看不见上一条的会话。
 *
 * The session a model-issued tool call belongs to, for as long as that call runs.
 *
 * <p>Same shape and the same reason as the caller the factory already publishes: assembly happens on a
 * thread that knows the session, and the model drives the tool loop on one that never did. A governed
 * call that cannot say which turn it came from is one no reader can place — its row stops at "this
 * caller's recent calls", which is a different and weaker question.
 *
 * <p>Thread-local, and **restored** after each body rather than merely cleared, so the next task the
 * same pool thread picks up cannot see the previous one's session.
 */
public final class Ai1ToolCallSession {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private Ai1ToolCallSession() {
    }

    /**
     * 在 {@code sessionId} 的作用域里跑 body；为 {@code null} 时**直接跑**——与发布调用方那条同一个口径：没有值就不设，
     * 免得给一个「空的会话」占位。
     *
     * Runs the body inside {@code sessionId}'s scope, or plainly when it is null — the same rule the
     * caller publication uses: no value, no scope, rather than a placeholder standing in for one.
     */
    public static <T> T as(Long sessionId, Supplier<T> body) {
        if (sessionId == null) {
            return body.get();
        }
        Long previous = CURRENT.get();
        CURRENT.set(sessionId);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * 当前这条工具调用所属的会话；不在任何会话里时为 {@code null}。
     *
     * The session the current tool call belongs to, or null when it belongs to none.
     */
    public static Long current() {
        return CURRENT.get();
    }
}
