// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.module.ai1.harness.skill;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.ai.tool.ToolCallback;

/**
 * 本轮会话的 SKILL 执行体：名字 → 该 agent 物化出来的那个 delegate。
 *
 * <p><b>为什么是线程上下文。</b>注册给引擎的工具按名字稳定存在，而 SKILL 的 delegate 是**每个 agent**
 * 在自己的沙箱里物化出来的（shell 的工作目录、文件工具的允许目录都不相同）。执行发生在模型驱动的线程上，
 * 那条线程不知道这次属于哪个 agent；装配处知道。于是装配处把这一轮的 delegate 放进上下文，执行体按名字取。
 *
 * <p>它随一次调用设置、随即清除，故一个 agent 的 delegate 不会漏进同一个线程上的下一次调用。
 *
 * This turn's SKILL delegates: name → the callback the agent's own sandbox produced.
 *
 * <p><b>Why a thread context.</b> A tool registered with the engine is stable by name, while a SKILL
 * delegate is materialised **per agent** in that agent's sandbox (shell working directory, file tools'
 * allowed directory). Execution happens on a model-driven thread that does not know which agent this
 * turn belongs to; assembly does. So assembly publishes this turn's delegates and the body reads them
 * by name.
 *
 * <p>Set for one call and cleared afterwards, so one agent's delegate cannot leak into the next call
 * on the same thread.
 */
public final class Ai1SkillExecutionContext {

    private static final ThreadLocal<Map<String, ToolCallback>> CURRENT = new ThreadLocal<>();

    private Ai1SkillExecutionContext() {
    }

    /** Run the body with these delegates visible to it. / 让这段 body 看见这些 delegate。 */
    public static <T> T run(Map<String, ToolCallback> delegates, Supplier<T> body) {
        Map<String, ToolCallback> previous = CURRENT.get();
        CURRENT.set(delegates);
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

    /** The delegate for this name in this turn, or null. / 这一轮里这个名字的 delegate；没有则 null。 */
    public static ToolCallback current(String name) {
        Map<String, ToolCallback> delegates = CURRENT.get();
        return delegates == null ? null : delegates.get(name);
    }

}
