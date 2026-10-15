// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.pipeline.ModuleRoute;
import cn.com.keelbase.runtime.pipeline.ModuleRoutePlanner;
import cn.com.keelbase.runtime.pipeline.ToolCallPlanner;
import java.util.List;

/**
 * 宿主的 planner：本部署声明的路由**先被问**；路由认不出的，交给**路由之后那个**。
 *
 * <p>「路由之后是谁」由 {@link YudaoToolFaceConfiguration} 决定，不再是写死的规则版 —— 那是**组合口**。
 * 一个声明了路由的部署会**顺带把模型驱动的规划器也顶掉**：两者争的是同一条 {@code ToolCallPlanner} 缝，且都按
 * {@code @ConditionalOnMissingBean} 注册 ⇒ 靠注册本身，「占住这条缝」与「走到一个配了的模型」不可能同时成立。
 * 所以模型只能从这里接进来 —— 见 {@link ModuleRoutePlanner#ModuleRoutePlanner(ToolCallPlanner)}。
 *
 * <p>{@code D34} 之后，走法属于**平台**：{@link ModuleRoutePlanner} 先问本宿主的路由、把本轮的消息放进路由读
 * 的上下文里，最后把**原**上下文交给兜底。这里除了「本宿主拥有哪些路由」之外**什么都不 override**。
 */
public class YudaoToolPlanner extends ModuleRoutePlanner {

    private final List<ModuleRoute> routes;

    public YudaoToolPlanner(List<ModuleRoute> routes, ToolCallPlanner afterTheRoutes) {
        super(afterTheRoutes);
        this.routes = routes == null ? List.of() : routes;
    }

    @Override
    protected List<ModuleRoute> routes() {
        return routes;
    }
}
