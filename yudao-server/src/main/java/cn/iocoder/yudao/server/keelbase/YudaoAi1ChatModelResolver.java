// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.iocoder.yudao.module.ai.dal.dataobject.model.AiModelDO;
import cn.iocoder.yudao.module.ai.enums.model.AiModelTypeEnum;
import cn.iocoder.yudao.module.ai.service.model.AiModelService;
import cn.iocoder.yudao.module.ai1.dal.dataobject.agent.Ai1AgentDO;
import cn.iocoder.yudao.module.ai1.harness.llm.Ai1ChatModelResolver;
import java.util.Objects;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.ai1.enums.Ai1ErrorCodeConstants.MODEL_NOT_BELONG_PROVIDER;
import static cn.iocoder.yudao.module.ai1.enums.Ai1ErrorCodeConstants.MODEL_TYPE_NOT_CHAT;

/**
 * Answers the agent module's model question from the host's <em>own</em> AI module, so a deployment
 * configures a model once — on the console's model page — and both AIs here mean the same row.
 *
 * <p>This host runs two AI modules upstream ships as separate variants. Rather than teach the agent
 * module to depend on its sibling — which would make one variant depend on the other, a shape upstream
 * deliberately does not have — the deployment answers through the seam the agent module already
 * declares ({@code Ai1ChatModelResolver}). The module keeps knowing nothing about where a model comes
 * from; this host keeps knowing nothing about how a session is run.
 *
 * <p><b>The two vocabularies line up, and that is checked rather than assumed.</b> An agent names a
 * provider and a model; upstream's model module names an API key and a model, and a model belongs to
 * the key it is reached with — the same relation, so the deployment's provider <em>is</em> that API
 * key. An agent whose provider and model disagree is refused the way upstream's own module refuses it,
 * with the same error, because it is the same question.
 *
 * <p><b>Which model this deployment's agent talks to stops being a file.</b> It was
 * {@code spring.ai.openai.*} on the runtime's side of the house — a key in a deployment file. Here it
 * comes from rows somebody maintains on a page, which is what "the host's own model management" means;
 * the console's model page is where a change is made, and it takes effect without a rebuild.
 *
 * 用宿主**自己的** AI 模块回答 agent 模块的模型问题——于是模型只配一次（在控制台的模型页上），而这里的两个
 * AI 指的是同一行。
 *
 * <p>本宿主跑着上游当作**两个变体**发布的两个 AI 模块。与其教会 agent 模块去依赖它的兄弟——那会让一个变体
 * 依赖另一个，而上游**刻意**没有这个形状——不如让部署方经 agent 模块**本来就声明了**的那条缝
 * （`Ai1ChatModelResolver`）来回答。模块继续不知道模型从哪来；本宿主继续不知道一次会话怎么跑。
 *
 * <p><b>两套词表是对得上的，而这是**查过**的、不是假定的。</b>一个 agent 点名 provider 与 model；上游的模型
 * 模块点名 api_key 与 model，而模型属于**够到它的那把 key**——同一个关系，故本部署的 provider **就是**那行
 * api_key。provider 与 model 对不上的 agent，按上游自己那个模块拒它的同一种方式被拒、用的是同一个错误，
 * 因为那是同一个问题。
 *
 * <p><b>本部署的 agent 跟哪个模型说话，不再是一个文件。</b>在运行时那一侧它是 `spring.ai.openai.*`——部署
 * 文件里的一把 key。这里它来自**有人在页面上维护的行**，而「宿主自己的模型管理」就是这个意思；要改就在控制台的
 * 模型页上改，不必重新构建。
 */
@Component
public class YudaoAi1ChatModelResolver implements Ai1ChatModelResolver {

    private final AiModelService models;

    public YudaoAi1ChatModelResolver(AiModelService models) {
        this.models = models;
    }

    /**
     * The model behind the agent's reference. Both refusals are upstream's own — a model that is
     * missing or disabled, and one that is not a conversational model — because a deployment that
     * names a model here should be told what the model page would have told it.
     *
     * agent 点名所指的那个模型。两处拒绝都是**上游自己的**——模型不存在或被禁用，以及模型不是对话模型——
     * 因为在这里点名了一个模型的部署，应当听到**模型页本来会告诉它的话**。
     */
    @Override
    public ChatModel resolve(Ai1AgentDO agent) {
        // Throws upstream's MODEL_NOT_EXISTS / MODEL_DISABLE when there is nothing usable to resolve.
        // 没有可解析的东西时，抛上游的 MODEL_NOT_EXISTS / MODEL_DISABLE。
        AiModelDO model = models.validateModel(agent.getModelId());
        if (!Objects.equals(AiModelTypeEnum.CHAT.getType(), model.getType())) {
            throw exception(MODEL_TYPE_NOT_CHAT);
        }
        if (agent.getProviderId() != null && !agent.getProviderId().equals(model.getKeyId())) {
            throw exception(MODEL_NOT_BELONG_PROVIDER);
        }
        // The key and the platform are the model's, so the runtime is built by upstream's own factory
        // rather than reassembled here.
        // key 与 platform 都是那个模型的，故运行时由**上游自己的工厂**建出来，而不是在这里再拼一遍。
        return models.getChatModel(model.getId());
    }
}
