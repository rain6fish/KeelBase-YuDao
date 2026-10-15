// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.pipeline.IntentPlan;
import cn.com.keelbase.runtime.pipeline.ModuleRoute;
import cn.com.keelbase.runtime.pipeline.RuleBasedPlanner;
import cn.com.keelbase.runtime.pipeline.ToolCallPlanner;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import cn.com.keelbase.springai.SpringAiToolCallPlanner;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把宿主的工具面接上线：一个规划器，盖过宿主声明的每一条 {@link ModuleRoute}。
 *
 * <p>**刻意**声明成普通配置、不是自动配置：应用的组件扫描在**用户阶段**注册它、早于任何自动配置，于是运行时的
 * {@code RuleBasedPlannerAutoConfiguration} 看见这个规划器就让位。这就是全部诀窍，与 RuoYi 宿主用
 * {@code @AutoConfigureBefore} 做的是同一件事。
 *
 * <p>**同一个动作也把模型驱动的规划器顶掉了** —— 两者争的是同一条缝、且都按 {@code @ConditionalOnMissingBean}
 * 注册。所以「路由之后由谁答」由本类**显式**接上：见 {@link #behindTheRoutes}。
 */
@Configuration
public class YudaoToolFaceConfiguration {

    @Bean
    ToolCallPlanner yudaoToolCallPlanner(List<ModuleRoute> routes,
                                         ObjectProvider<ChatClient.Builder> modelBuilder,
                                         ObjectProvider<ToolRegistry> toolRegistry) {
        return new YudaoToolPlanner(routes,
                behindTheRoutes(modelBuilder.getIfAvailable(), toolRegistry.getIfAvailable()));
    }

    /**
     * 路由之后由谁答：**运行时的规则版照旧在前，模型（配了才有）在最后**。
     *
     * <p>为什么得由这里接：模型驱动的规划器与宿主这个争的是同一条 {@code ToolCallPlanner} 缝，两者都按
     * {@code @ConditionalOnMissingBean} 注册 ⇒ 声明了路由的部署**顺带把模型也顶掉了**。运行时的组合口
     * （{@code ModuleRoutePlanner} 的兜底参数）正是为这件事存在的。
     *
     * <p>次序刻意是「规则在前、模型在后」：这样本次改动**只可能把「没有提议」变成「有提议」**，
     * **改不了任何一个已经存在的提议** —— 没配模型时更是与从前一字不差（兜底就是那同一个规则版）。
     *
     * <p>{@code ObjectProvider} 而不是直接注入：本部署**不给 key 时没有模型**，那时这里必须原样退回规则版，
     * 而不是起不来。
     */
    private static ToolCallPlanner behindTheRoutes(ChatClient.Builder modelBuilder, ToolRegistry tools) {
        ToolCallPlanner rules = new RuleBasedPlanner();
        if (modelBuilder == null || tools == null) {
            return rules;
        }
        ToolCallPlanner model = new SpringAiToolCallPlanner(modelBuilder.build(), tools);
        return new RulesThenModel(rules, model);
    }

    /** 规则先答；它认不出的才问模型 —— 一条链，也就是这次改动**只有加法**的那条性质。 */
    private record RulesThenModel(ToolCallPlanner rules, ToolCallPlanner model) implements ToolCallPlanner {

        @Override
        public Optional<IntentPlan> plan(String message, Map<String, Object> context) {
            return rules.plan(message, context).or(() -> model.plan(message, context));
        }
    }
}
