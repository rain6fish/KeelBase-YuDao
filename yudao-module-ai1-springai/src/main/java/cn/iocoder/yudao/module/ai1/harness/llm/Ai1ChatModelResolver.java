// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.module.ai1.harness.llm;

import cn.iocoder.yudao.module.ai1.dal.dataobject.agent.Ai1AgentDO;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Which conversational model a session runs on, answered by the deployment when it has an answer of
 * its own.
 *
 * <p>This module ships one: a provider row's address and key, turned into an OpenAI-compatible model
 * per request (see {@link Ai1LlmModelFactory}). A deployment whose models are managed somewhere else
 * does not have to keep a second copy of the same facts here. It supplies a bean of this type and the
 * module asks it instead — and because "somewhere else" means the deployment's own catalogue, the
 * interface takes the whole agent rather than an id: which of its fields name a model is that
 * deployment's vocabulary, not this module's.
 *
 * <p><b>Absent is a supported state, not a broken one.</b> With no bean of this type the module keeps
 * its own path, exactly as before. That is what keeps this module usable on its own, and it is why
 * both callers test for null rather than requiring an implementation.
 *
 * <p>Being the thing that resolves a model also makes this the thing that <em>validates</em> one: a
 * reference that names nothing usable is refused here, so creating an agent and starting a session
 * fail the same way instead of at two different moments.
 *
 * 一次会话跑在哪个对话模型上——部署方自己有答案时由它回答。
 *
 * <p>本模块自带一个：用 provider 行的地址与密钥，逐请求建出一个 OpenAI 兼容模型（见
 * {@link Ai1LlmModelFactory}）。把模型管在别处的部署，不必在这儿再留一份同样的事实。它提供一个本类型的
 * bean，模块改问它——而因为「别处」指的是**部署方自己的目录**，本接口收整个 agent 而不是一个 id：它的哪个字段
 * 在指明一个模型，是**那个部署方的**词表，不是本模块的。
 *
 * <p><b>「没有实现」是一种受支持的状态，不是坏掉的状态。</b>没有本类型的 bean 时，模块走自己那条路、与从前
 * 一模一样。`ai1` 因此能单独用，也正是两处调用方都**判空**、而不要求必须有实现的原因。
 *
 * <p>解析模型这件事同时也让本接口成为**校验**模型的地方：点名了用不了的东西在这里被拒，于是「建 agent」与
 * 「开一次会话」会以同一种方式失败，而不是在两个不同的时刻各失败一次。
 *
 * <p><b>A file of KeelBase's inside an upstream module</b> — the seam is ours and so is its one host-side
 * implementation; both are registered in {@code keelbase-docs/KEELBASE-UPSTREAM.md}.
 *
 * <p><b>上游模块里的一份 KeelBase 文件</b> —— 这条缝是我们的、宿主侧那唯一的实现也是；两者都登记在
 * {@code keelbase-docs/KEELBASE-UPSTREAM.md}。
 */
public interface Ai1ChatModelResolver {

    /**
     * The model this agent's session should use, or a refusal when the agent names nothing usable.
     *
     * 这个 agent 的会话该用的模型；agent 什么都没点名可用的，则拒绝。
     */
    ChatModel resolve(Ai1AgentDO agent);
}
