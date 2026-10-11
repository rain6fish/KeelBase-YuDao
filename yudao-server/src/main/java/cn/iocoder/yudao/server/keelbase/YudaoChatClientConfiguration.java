// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Hands the KeelBase runtime the {@code ChatClient.Builder} it asks for, without switching back on the
 * autoconfiguration this host turned off on purpose.
 *
 * <p>The host's own {@code application.yaml} disables {@code spring.ai.chat.client.enabled} with the
 * reason written next to it: that autoconfiguration wants <b>exactly one</b> {@code ChatModel}, and this
 * project builds several — its own AI module and the report module both contribute — so enabling it
 * fails startup. Passing the model key used to re-enable it anyway, which brought that failure back.
 *
 * <p>So the builder is supplied here instead: the runtime needs a {@code ChatClient.Builder} (its
 * governed runner is declared {@code @ConditionalOnBean} of one), and picking <em>which</em> model that
 * builder talks to is the deployment's decision, not a factory's. It is the OpenAI-compatible model the
 * deployment configures with {@code spring.ai.openai.*}.
 *
 * <p>The switch is this line's own ({@code keelbase.ai.enabled}) rather than "is a key configured": a
 * deployment that passes a placeholder key would otherwise look switched on and answer from a model
 * that cannot answer at all. Off, the host starts exactly as it always did.
 *
 * 把 KeelBase 运行时问要的那个 `ChatClient.Builder` 交给它，而**不**把宿主**故意关掉**的那个自动配置开回来。
 *
 * <p>宿主自己的 `application.yaml` 关掉 `spring.ai.chat.client.enabled` 时就在旁边写了理由：那个自动配置要
 * **恰好一个** `ChatModel`，而本项目会造多个 —— 它自己的 AI 模块与 report 模块都出一份 —— 开起来就启动失败。
 * 过去把模型 key 传进去会把它**一并**开回来，于是那个失败也回来了。
 *
 * <p>所以这里自己提供 builder：运行时需要的是一个 `ChatClient.Builder`（它那条受治理的 runner 就声明成
 * `@ConditionalOnBean` 这个类型），而**这个 builder 跟哪个模型说话**是**部署方**的决定、不是某个工厂的。它取部署
 * 方用 `spring.ai.openai.*` 配的那个 OpenAI 兼容模型。
 *
 * <p>开关是本线自己的（`keelbase.ai.enabled`），而不是「配没配 key」：一个传占位 key 的部署，照后者会被看成
 * **开着**、然后由一个根本答不了的模型去作答。关着时，宿主与从前一模一样地起得来。
 */
@Configuration
@ConditionalOnProperty(name = "keelbase.ai.enabled", havingValue = "true")
public class YudaoChatClientConfiguration {

    @Bean
    @ConditionalOnMissingBean(ChatClient.Builder.class)
    public ChatClient.Builder keelbaseChatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }

    /**
     * Says which of the host's many models is <em>this deployment's</em>. The runtime's own
     * auto-configuration asks for a bare {@code ChatModel} — it reads the name off it for the reply's
     * {@code model} field — and a host that runs several AI modules has more than one; naming the
     * primary is the deployment's call, and it is the only place that call can be made without either
     * touching the runtime or dropping the other modules' models.
     *
     * <p>Same instance, not a copy: this only decides what an ambiguous request resolves to.
     *
     * 在宿主这许多模型里点明**哪一个属于本部署**。运行时自己那条自动配置要的是一个**裸** `ChatModel`（它从上面
     * 读名字、写进回复的 `model` 字段），而一个跑着多个 AI 模块的宿主不止一个；**点名 primary 是部署方的决定**，
     * 也是唯一一个既不碰运行时、又不用丢掉别的模块那些模型的地方。
     *
     * <p>同一个实例、不是副本：这里只决定一次有歧义的请求解析到谁。
     */
    @Bean
    @Primary
    public ChatModel keelbaseChatModel(OpenAiChatModel chatModel) {
        return chatModel;
    }

}
