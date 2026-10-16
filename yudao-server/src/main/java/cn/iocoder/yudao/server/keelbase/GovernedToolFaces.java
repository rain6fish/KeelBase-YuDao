// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本宿主上每一个**工具面**的声明：AI 到达执行的那些地方，逐个列出来。
 *
 * <p><b>它补的是登记处过去数不到的那一半。</b>登记处过去只装模块（{@link GovernedModule}）；而 AI 在本宿主上还经**工具**
 * 到达执行——把可调用物交给引擎或模型的地方。这些地方过去**一个都没登记**，于是「本宿主被数全了吗」在模块那一栏之外
 * 无从问起。这里有十处：八个**工具面**（声明 {@code @Bean AiTool} 的配置、或直接 {@code implements AiTool} 的组件）、
 * 以及两条**工具路**（把工具交给模型/引擎的缝：本部署自己的引擎工具面 {@code YudaoGovernedAgentTools}，与 MCP 那条路的
 * 治理切面 {@code Ai1McpGovernance}）。{@code scripts/check-governance-registry.mjs} 读源码里这些形状，拒掉一个没在这里
 * 列出的——故这份目录**漂不了**：它是「登记处计数、检查让数诚实」里被检查的那一半。
 *
 * <p><b>为什么工具是**一份手写目录**，而模块是**在自己类上自报**。</b>这是刻意的不对称，理由只有一条：工具面里有一个是
 * **生成的**——`generated/ProductsTools`（来自 `specs/products.json`）。生成的文件不许手改，而让生成器给它发出声明是
 * **另一个工作项**（生成器，`YD-25`），不在本项射程内。于是工具的声明落在这**一处手写的地方**：模块有「能自报的载体」
 * （切面，生成器已经替它发出 {@code implements GovernedModule}），工具面则没有——除非动生成器。把十处收进一份可读的
 * 目录，比给九个类各加一个记号、再给生成面单开一个特例要诚实。
 *
 * <p><b>⚠ 它不做的事。</b>列在这里**不等于**这条路径受治理。工具面经引擎执行、受确认与审计管住；MCP 那条路只由它自己的
 * 切面管身份与审计、**不经引擎、不扣人**（`YD-T22` 的「丙」）——它的**posture 不在本声明里**，而在线上的那道边界
 * （`scripts/check-ungoverned-tool-beans.mjs` 的边界对象）与 MCP 门禁里。此处只回答「这条路存在、有人枚举过它」。
 *
 * <p>The declaration of every <em>tool face</em> on this host: the places where the AI reaches execution,
 * listed one by one.
 *
 * <p><b>It fills the half the registry could not count.</b> The registry used to hold modules alone
 * ({@link GovernedModule}); the AI also reaches execution here through <em>tools</em> — the places that
 * hand callables to the engine or to a model. None of those places was declared, so beyond the module
 * column "is this host accounted for?" had no answer. There are ten: eight <b>tool faces</b> (a
 * {@code @Configuration} declaring {@code @Bean AiTool}, or a component that {@code implements AiTool})
 * and two <b>tool paths</b> (the seams that hand tools to a model or the engine: this deployment's own
 * engine face {@code YudaoGovernedAgentTools}, and the MCP path's governance aspect
 * {@code Ai1McpGovernance}). {@code scripts/check-governance-registry.mjs} reads those shapes in the
 * sources and refuses one not listed here — so this catalogue <b>cannot drift</b>: it is the half the
 * check keeps honest.
 *
 * <p><b>Why tools are a <em>hand-written catalogue</em> while modules <em>declare themselves</em>.</b>
 * A deliberate asymmetry, for one reason: one tool face is <b>generated</b> — `generated/ProductsTools`
 * (from `specs/products.json`). A generated file may not be hand-edited, and making the generator emit
 * the declaration is <b>a separate work item</b> (the generator, `YD-25`), outside this task. So the tool
 * declarations land in <b>this one hand-written place</b>: a module has a carrier that can declare itself
 * (the aspect, which the generator already emits {@code implements GovernedModule} for), a tool face does
 * not — not without touching the generator. Gathering the ten into one readable catalogue is more honest
 * than a marker on nine classes plus a special case for the generated one.
 *
 * <p><b>⚠ What it does not do.</b> Being listed here is <b>not</b> being governed. The tool faces run
 * through the engine and are held by confirmation and audit; the MCP path is held by its own aspect for
 * identity and audit alone, <b>without the engine, without holding a call for a person</b> (`YD-T22`'s
 *「丙」) — its posture is not in this declaration but on the boundary the gate reads
 * (`scripts/check-ungoverned-tool-beans.mjs`'s boundary object) and in the MCP gate. This answers only
 * "the path exists and someone enumerated it".
 */
@Configuration
public class GovernedToolFaces {

    /** CRM 的工具面：跑在上游已发布的 `CrmCustomerService` 上的一读一写。 / CRM's tool face over upstream's own service: one read, one write. */
    @Bean
    GovernedPath crmCustomerTools() {
        return new ToolFace("CrmCustomerTools");
    }

    /** CRM 旗舰闭环的工具面：队列一读、跟进一写。 / The flagship loop's tool face: the queue read, the follow-up write. */
    @Bean
    GovernedPath crmFollowUpTools() {
        return new ToolFace("CrmFollowUpTools");
    }

    /** BPM 请假单的工具面，同第二个上游模块一起接入。 / BPM's leave-request tool face, onboarded with the second upstream module. */
    @Bean
    GovernedPath bpmOaLeaveTools() {
        return new ToolFace("BpmOALeaveTools");
    }

    /** FMS 记账凭证的工具面，第五个上治理的模块、也是第一个非 CRM。 / FMS voucher's tool face, the fifth module under governance and the first that is not CRM. */
    @Bean
    GovernedPath fmsVoucherTools() {
        return new ToolFace("FmsVoucherTools");
    }

    /** 系统站内信的工具面，第三个上游模块。 / The in-app message tool face, the third upstream module. */
    @Bean
    GovernedPath systemNotifyMessageTools() {
        return new ToolFace("SystemNotifyMessageTools");
    }

    /** 生成的 products 模块的工具面；**生成物**，故由本手写目录代它自报（见类注释的不对称）。 / The generated products module's tool face; <b>generated</b>, so this hand-written catalogue declares it on its behalf (see the class comment). */
    @Bean
    GovernedPath productsTools() {
        return new ToolFace("ProductsTools");
    }

    /** 控制台导航的工具面：一个读工具 + 一条无模型路由，走同一个引擎。 / The console-navigation tool face: a read tool plus a model-free route, through the same engine. */
    @Bean
    GovernedPath consolePages() {
        return new ToolFace("ConsolePages");
    }

    /** 跑在宿主自己数据上的单工具：管理端用户，经芋道自己的服务读。 / The single tool over the host's own data: admin users, read through Yudao's own service. */
    @Bean
    GovernedPath listYudaoUsersTool() {
        return new ToolFace("ListYudaoUsersTool");
    }

    /** 本部署自己的引擎工具面：运行时持有的每一个工具经 `GovernedExecutionEngine` 执行——受治理那侧的那条缝。 / This deployment's own engine tool face: every tool the runtime holds runs through {@code GovernedExecutionEngine} — the seam on the governed side. */
    @Bean
    GovernedPath yudaoGovernedAgentTools() {
        return new ToolFace("YudaoGovernedAgentTools");
    }

    /** MCP 那条路的治理切面：经 Spring AI 的工具循环、**不经引擎**，只担身份、审计与「声明是写的就拒」。 / The MCP path's governance aspect: driven by Spring AI's tool loop, <b>not through the engine</b>, carrying identity, audit and the refusal of an explicitly declared write. */
    @Bean
    GovernedPath ai1McpGovernance() {
        return new ToolFace("Ai1McpGovernance");
    }

    /**
     * 一个工具面，作为一条声明。名字就是**类的简单名**——那是检查脚本能从源码里读出来、与这里比对的东西。
     *
     * A tool face, as a declared path. The name is the <b>class's simple name</b> — what the check reads
     * out of the sources and compares against this.
     */
    record ToolFace(String id) implements GovernedPath {

        @Override
        public Kind kind() {
            return Kind.TOOL;
        }
    }
}
