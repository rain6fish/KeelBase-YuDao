// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Collects every {@link GovernedModule} bean and holds the set — the one place this host can be asked
 * "what is governed here".
 *
 * <p>Constructor injection is the whole mechanism: Spring hands over each declaration, and the set is
 * sorted and said once at startup, so a running host answers the question in its own log rather than
 * only in its source.
 *
 * <p><b>The limit, named rather than implied.</b> A module whose aspect does not declare itself is
 * simply absent — this class cannot tell "not governed" from "forgotten". That is exactly the gap the
 * check script closes: it reads the sources for a {@code *Governance} naming a module's own service and
 * refuses one that is not declared here. The registry counts; the check keeps the count true.
 *
 * 收集每个 {@link GovernedModule} bean 并持有这个集合——本宿主唯一能回答「这里被管住的是什么」的地方。
 *
 * <p>构造器注入就是全部机制：Spring 把每一份声明交过来，集合排好序、在启动时说一次，故一台跑着的宿主在自己的日志里
 * 就能回答那个问题，不必去翻源码。
 *
 * <p><b>把限度说出来、而不是暗示它。</b>切面没自报的模块**就是不在**——本类分不出「不受治理」与「被忘了」。而那正是
 * 检查脚本要堵的那个洞：它去源码里找点了模块自己 service 的 {@code *Governance}，拒掉一个没在这里声明的。登记处
 * **计数**；那道检查让这个数**不假**。
 */
@Slf4j
@Component
public class GovernedModuleRegistry {

    private final List<GovernedModule> modules;

    public GovernedModuleRegistry(List<GovernedModule> modules) {
        this.modules = modules.stream()
                .sorted(Comparator.comparing(GovernedModule::module))
                .toList();
        // Said once, at startup. A count nobody reads is the state this class exists to end, so it is
        // put where an operator already looks — one line per module, in the order the set is held.
        // 启动时说一次。一个没人读的计数，正是本类要终结的状态，故它被放在运维本来就会看的地方——一个模块一行、
        // 按集合持有的顺序。
        this.modules.forEach(module ->
                log.info("[governed modules] {} — {} (declared by {})",
                        module.module(), module.ownerField(), module.getClass().getSimpleName()));
        log.info("[governed modules] {} module(s) declared", this.modules.size());
    }

    /**
     * The declarations, sorted by module name. The set a reader can count, and the one
     * `scripts/check-governance-registry.mjs` asserts the sources agree with.
     *
     * 那些声明，按模块名排序。既是读者**数得出来**的集合，也是 `scripts/check-governance-registry.mjs` 断言源码与之一致的那个。
     */
    public List<GovernedModule> all() {
        return modules;
    }
}
