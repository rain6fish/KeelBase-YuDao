// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 收集每一份 {@link GovernedPath} 声明并持有这个集合——本宿主唯一能回答「这里被数到的是什么」的地方。**两个来源**：
 * 模块在**自己类上自报**（切面是 bean），工具面从 {@link GovernedToolFaces} 的**目录**里读（那是数据，不是 bean）。
 *
 * <p>构造器注入就是全部机制：Spring 把每一份声明交过来，集合排好序、在启动时说一次，故一台跑着的宿主在自己的日志里
 * 就能回答那个问题，不必去翻源码。**它现在按种类分组**：模块、工具、工作流、定时任务各一栏，于是「本宿主被数全了吗」
 * 在**每一栏**上都能问——在 `YD-24` 之前，这里只装模块一种，别的种类的路径**一个都数不到**。
 *
 * <p><b>把限度说出来、而不是暗示它。</b>一条切面没自报的路径**就是不在**——本类分不出「不受治理」与「被忘了」。而那正是
 * 检查脚本要堵的那个洞：它去源码里找每种形状长什么样（模块的 `service` 切点、工具面的 `AiTool` bean / `Ai1LocalToolProvider`
 * / MCP 切面），拒掉一个没在这里声明的。登记处**计数**；那道检查让这个数**不假**。
 *
 * <p>Collects every {@link GovernedPath} declaration and holds the set — the one place this host can be
 * asked "what is counted here". <b>Two sources</b>: a module <b>declares itself</b> (its aspect is a
 * bean), a tool face is read from {@link GovernedToolFaces}'s <b>catalogue</b> (data, not a bean).
 *
 * <p>Constructor injection is the whole mechanism: Spring hands over each declaration, and the set is
 * sorted and said once at startup, so a running host answers the question in its own log rather than
 * only in its source. <b>It is now grouped by kind</b> — one line for modules, one for tools, one for
 * workflows, one for scheduled tasks — so "is this host accounted for?" can be asked of <b>each</b>
 * column: before `YD-24` this held modules alone and every other kind of path was uncountable.
 *
 * <p><b>The limit, named rather than implied.</b> A path whose carrier does not declare itself is
 * simply absent — this class cannot tell "not governed" from "forgotten". That is exactly the gap the
 * check script closes: it reads the sources for the shape each kind has (a module's `service.` pointcut;
 * a tool face's `AiTool` bean, `Ai1LocalToolProvider`, or MCP aspect) and refuses one not declared
 * here. The registry counts; the check keeps the count true.
 */
@Slf4j
@Component
public class GovernedModuleRegistry {

    private final List<GovernedPath> paths;

    public GovernedModuleRegistry(List<GovernedPath> selfDeclared) {
        // Two sources, on purpose: a **module** declares itself (its aspect has to be a bean — it is what
        // gets advised), while a **tool face** is data and is read from the catalogue instead of being
        // wired as a bean. It was a bean once, and the bean name collided with the component of the same
        // name and stopped the host starting (see GovernedToolFaces).
        // 两个来源，刻意如此：**模块**自报（切面**必须**是 bean——要被通知到），而**工具面**是数据、从目录里读，
        // 不接成 bean。它曾是个 bean，bean 名与同名组件相撞、把宿主顶得起不来（见 GovernedToolFaces）。
        this.paths = Stream.concat(selfDeclared.stream(), GovernedToolFaces.all().stream())
                .sorted(Comparator.comparing((GovernedPath path) -> path.kind().ordinal())
                        .thenComparing(GovernedPath::id))
                .toList();
        // Said once, at startup. A count nobody reads is the state this class exists to end, so it is
        // put where an operator already looks — one line per path, in the order the set is held.
        // 启动时说一次。一个没人读的计数，正是本类要终结的状态，故它被放在运维本来就会看的地方——一条路径一行、
        // 按集合持有的顺序。
        this.paths.forEach(path ->
                log.info("[governed paths] {} {} (declared by {})",
                        path.kind(), path.id(), path.getClass().getSimpleName()));
        // The per-kind tally is what `YD-24` added: the total was never the question — which kinds are
        // covered, and whether any is empty, is. 逐种类的计数是 `YD-24` 加的：总数从来不是问题——**哪些种类被盖到、
        // 有没有哪一种是空的**才是。
        Map<GovernedPath.Kind, Long> byKind = this.paths.stream()
                .collect(Collectors.groupingBy(GovernedPath::kind, Collectors.counting()));
        for (GovernedPath.Kind kind : GovernedPath.Kind.values()) {
            log.info("[governed paths] {}: {}", kind, byKind.getOrDefault(kind, 0L));
        }
        log.info("[governed paths] {} path(s) declared", this.paths.size());
    }

    /**
     * 那些声明，先按种类、再按名字排序。既是读者**数得出来**的集合，也是
     * `scripts/check-governance-registry.mjs` 断言源码与之一致的那个。
     *
     * The declarations, sorted by kind and then by name. The set a reader can count, and the one
     * `scripts/check-governance-registry.mjs` asserts the sources agree with.
     */
    public List<GovernedPath> all() {
        return paths;
    }
}
