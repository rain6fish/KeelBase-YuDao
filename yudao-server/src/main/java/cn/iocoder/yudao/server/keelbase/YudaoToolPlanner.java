// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.pipeline.ModuleRoute;
import cn.com.keelbase.runtime.pipeline.ModuleRoutePlanner;
import java.util.List;

/**
 * The host's planner: the routes this deployment declares, asked before the runtime's rule-based one.
 *
 * <p>As of {@code D34} the walk belongs to the platform — {@link ModuleRoutePlanner} asks this host's
 * routes first, puts the turn's message into the context a route reads, and hands the runtime's
 * {@code RuleBasedPlanner} the <b>original</b> context. Nothing is overridden here beyond which routes
 * this host owns: the behaviour is what this class used to carry, moved up so that a second host does
 * not have to carry it too.
 *
 * 宿主的 planner：本部署声明的路由，**先问它们**、再落到运行时的规则版。
 *
 * <p>{@code D34} 之后，走法属于**平台**——{@link ModuleRoutePlanner} 先问本宿主的路由、把本轮消息放进路由
 * 读的上下文里，最后把**原**上下文交给运行时的 {@code RuleBasedPlanner}。这里除了「本宿主拥有哪些路由」之外
 * **什么都不 override**：行为就是本类原来那一份，上提了，好让第二个宿主不必再带一遍。
 */
public class YudaoToolPlanner extends ModuleRoutePlanner {

    private final List<ModuleRoute> routes;

    public YudaoToolPlanner(List<ModuleRoute> routes) {
        this.routes = routes == null ? List.of() : routes;
    }

    @Override
    protected List<ModuleRoute> routes() {
        return routes;
    }
}
