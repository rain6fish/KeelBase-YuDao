// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.pipeline.ModuleRoute;

import cn.com.keelbase.runtime.pipeline.ToolCallPlanner;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Brings the host's tool face online: one planner over every {@link ModuleRoute} the host declares.
 *
 * <p>Declared as an ordinary configuration, not an auto-configuration, on purpose: the app's component
 * scan registers it in the user phase, ahead of any auto-configuration, so the runtime's
 * {@code RuleBasedPlannerAutoConfiguration} sees this planner and backs off. That is the whole trick,
 * and it is the same one the RuoYi host used with {@code @AutoConfigureBefore}.
 *
 * 把宿主的工具面接上线：一个规划器，盖过宿主声明的每一条 {@link ModuleRoute}。
 *
 * <p>**刻意**声明成普通配置、不是自动配置：应用的组件扫描在**用户阶段**注册它、早于任何自动配置，于是运行时的
 * {@code RuleBasedPlannerAutoConfiguration} 看见这个规划器就让位。这就是全部诀窍，与 RuoYi 宿主用
 * {@code @AutoConfigureBefore} 做的是同一件事。
 */
@Configuration
public class YudaoToolFaceConfiguration {

    @Bean
    ToolCallPlanner yudaoToolCallPlanner(List<ModuleRoute> routes) {
        return new YudaoToolPlanner(routes);
    }
}
