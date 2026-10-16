// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

/**
 * 本宿主 AI 能到达执行的一条路径——**声明出来**，好让它们的集合在一处可数。
 *
 * <p><b>为什么集合必须变宽。</b>登记处过去只装 {@link GovernedModule}，而一个 {@code GovernedModule} 是「自己的
 * {@code service} 层被一条通知管住」的**业务模块**——是几种路径里的**一种**。AI 还经**工具**（把可调用物交给引擎或
 * 模型的那些工具面）、经**工作流**、经**定时任务**到达执行。一个只数第一种的登记处，答不了「本宿主被数全了吗」——
 * 一个从没自报的工具面**就是不在**，与一个被忘掉的模块**一模一样**。{@code kind()} 就是让四种在**同一个可数集合**里
 * 分得开的东西：留下的那个洞因此是**诚实的**（一条没人枚举的路径），而不是「一种没列进去的类」。
 *
 * <p><b>它是什么、不是什么。</b>声明**不是**保证：声明一条路径受治理**并不会**让它受治理。它说的是「这条路径在清单上」，
 * 而清单**只与声明有多全一样真**——正因如此，`scripts/check-governance-registry.mjs` 去读我们源码里每种形状各长什么样，
 * 拒掉一条从没自报的。登记处**计数**；那道检查让这个数**诚实**。
 *
 * <p><b>{@code id} 从哪来。</b>路径自己报名字。模块报的是它 pointcut 所指的那一段（{@link GovernedModule#module()}）；
 * 工具面报的是它的类名。下面那个缺省就是**类的简单名**，对每一个没有更强名字可报的载体，它都是对的答案。
 *
 * <p>One path through which the AI on this host can reach execution — declared so the set of them is
 * countable in a single place.
 *
 * <p><b>Why the set had to widen.</b> The registry used to hold {@link GovernedModule} alone, and a
 * {@code GovernedModule} is a business module whose own {@code service} layer is held by an advice —
 * one kind of path out of several. The AI also reaches execution through <em>tools</em> (the faces that
 * hand callables to the engine or to a model), through <em>workflows</em>, and through
 * <em>scheduled tasks</em>. A registry that counted only the first kind could not answer "is this host
 * fully accounted for?" — a tool face that was never declared was simply absent, exactly as a forgotten
 * module was. {@code kind()} is what makes the four kinds distinguishable in one countable set, so the
 * gap that remains is the honest one (a path nobody enumerated) rather than a path of an unlisted kind.
 *
 * <p><b>What this is, and what it is not.</b> A declaration is not a guarantee: declaring a path
 * governed does not govern it. It says "this path is on the list", and the list is only as true as the
 * declarations are complete — which is why {@code scripts/check-governance-registry.mjs} reads our
 * sources for the shapes each kind has and refuses a path that never declared itself. The registry
 * counts; the check keeps the count true.
 *
 * <p><b>Where {@code id} comes from.</b> A path names itself. A module names the segment its pointcuts
 * point at ({@link GovernedModule#module()}); a tool face names its class. The default below is the
 * class's simple name, which is the right answer for every carrier that has no stronger name to give.
 */
public interface GovernedPath {

    /**
     * 本宿主能持有的四种，用**词**而不是序号，故一行日志或一页控制台读起来就是人说话的样子。
     *
     * The four kinds this host can hold, as words rather than ordinals so a log line or a console page
     * reads them the way a person says them.
     */
    enum Kind {
        /** 自己的 service 层在一条通知之下的业务模块。 / A business module whose own service layer is under an advice. */
        MODULE,
        /** 把工具交给引擎或模型的地方。 / A place that hands tools to the engine or to a model. */
        TOOL,
        /** AI 驱动的多步流程。 / A multi-step flow the AI drives. */
        WORKFLOW,
        /** 宿主为 AI 按计划跑的任务。 / A task the host runs on a schedule for the AI. */
        SCHEDULED_TASK
    }

    /**
     * 属于四者中的哪一种。**必填、不给缺省**：一条说不出自己种类的路径，正是本接口要终结的那种含糊。
     *
     * Which of the four this is. Required rather than defaulted: a path that cannot say its kind is
     * exactly the ambiguity this interface exists to end.
     */
    Kind kind();

    /**
     * 路径自己的名字。缺省为载体的**简单类名**——对工具面是对的，而在有更强名字处被覆盖（模块报的是自己那一段）。
     *
     * The path's own name. Defaulted to the carrier's simple class name — right for a tool face, and
     * overridden where a stronger name exists (a module gives its own segment).
     */
    default String id() {
        return getClass().getSimpleName();
    }
}
