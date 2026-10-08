// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.pipeline.IntentPlan;
import cn.com.keelbase.runtime.pipeline.RuleBasedPlanner;
import cn.com.keelbase.runtime.pipeline.ToolCallPlanner;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Routes the host's own tool face, and falls back to the runtime's rule-based planner for everything
 * the host says nothing about.
 *
 * <p>The fallback is load-bearing, not politeness. Supplying any {@code ToolCallPlanner} makes the
 * runtime's own {@code RuleBasedPlannerAutoConfiguration} back off — that is what lets a host bring
 * its own tools — so a planner that did not fall back would silently take the sample CRM away. The
 * RuoYi host did the same thing for the same reason.
 *
 * 路由宿主自己的工具面；宿主没说的，一律回落到运行时的规则规划器。
 *
 * <p>**这条回落是承重的，不是客气**。提供任何一个 {@code ToolCallPlanner} 都会让运行时自己的
 * {@code RuleBasedPlannerAutoConfiguration} **让位**——这正是宿主能自带工具的机制——所以**不回落的规划器
 * 会悄悄把样例 CRM 拿走**。RuoYi 宿主出于同一理由做了同一件事。
 */
public class YudaoToolPlanner implements ToolCallPlanner {

    private final List<ModuleRoute> routes;
    private final ToolCallPlanner fallback = new RuleBasedPlanner();

    public YudaoToolPlanner(List<ModuleRoute> routes) {
        this.routes = routes == null ? List.of() : routes;
    }

    @Override
    public Optional<IntentPlan> plan(String message, Map<String, Object> context) {
        String text = message == null ? "" : message;
        // The turn's message is handed to the route as well: the runtime's context carries only what the
        // request declared, and a route that fills a write's arguments from what the user typed has
        // nothing else to read. Declared rather than hidden — a route that ignores it is unaffected.
        // 本轮的消息也交给路由：运行时的上下文只带**请求声明过的**东西，而「从用户所写里填一次写的参数」的路由
        // 没有别的可读。这是**声明出来**的、不是藏起来的——用不到它的路由不受影响。
        Map<String, Object> withMessage = new LinkedHashMap<>();
        if (context != null) {
            withMessage.putAll(context);
        }
        withMessage.put("message", text);
        for (ModuleRoute route : routes) {
            for (String trigger : route.triggers()) {
                if (!text.isEmpty() && text.contains(trigger)) {
                    return Optional.of(new IntentPlan(route.toolName(), new LinkedHashMap<>(route.args(withMessage))));
                }
            }
        }
        return fallback.plan(message, context);
    }
}
