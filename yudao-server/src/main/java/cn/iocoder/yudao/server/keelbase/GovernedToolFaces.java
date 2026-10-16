// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import java.util.List;

/**
 * 本宿主上每一个**工具面**的声明：AI 到达执行的那些地方，逐个列出来。
 *
 * <p><b>它补的是登记处过去数不到的那一半。</b>登记处过去只装模块（{@link GovernedModule}）；而 AI 在本宿主上还经**工具**
 * 到达执行——把可调用物交给引擎或模型的地方。这些地方过去**一个都没登记**，于是「本宿主被数全了吗」在模块那一栏之外
 * 无从问起。这里有十二处：八个**工具面**（声明 {@code @Bean AiTool} 的配置、或直接 {@code implements AiTool} 的组件）、
 * 两条**工具路**（把工具交给模型/引擎的缝：本部署自己的引擎工具面 {@code YudaoGovernedAgentTools}，与 MCP 那条路的
 * 治理切面 {@code Ai1McpGovernance}）、以及 {@code ai1} 那两条**装配端**（{@code Ai1McpToolFactory} 与
 * {@code Ai1SkillToolFactory}——它们在本机上**装配**模型可调的工具，见下面 SKILL 那条的警示）。
 * {@code scripts/check-governance-registry.mjs} 读源码里这些形状，拒掉一个没在这里列出的——故这份目录**漂不了**：
 * 它是「登记处计数、检查让数诚实」里被检查的那一半。
 *
 * <p><b>为什么工具是**一份手写目录**，而模块是**在自己类上自报**。</b>这是刻意的不对称，理由只有一条：工具面里有一个是
 * **生成的**——`generated/ProductsTools`（来自 `specs/products.json`）。生成的文件不许手改，而让生成器给它发出声明是
 * **另一个工作项**（生成器，`YD-25`），不在本项射程内。于是工具的声明落在这**一处手写的地方**：模块有「能自报的载体」
 * （切面，生成器已经替它发出 {@code implements GovernedModule}），工具面则没有——除非动生成器。把十二处收进一份可读的
 * 目录，比在类上逐个加记号、再给生成面与 {@code ai1} 那两处各开一个特例要诚实。
 *
 * <p><b>⚠ 它是一份清单、不是一批 bean——而这是修正过的一版。</b>第一版把每一条都做成 {@code @Bean GovernedPath}，
 * 方法名（如 {@code crmCustomerTools}）**正好**等于那条路径自己组件的默认 bean 名（{@code @Component class
 * CrmCustomerTools} ⇒ {@code crmCustomerTools}）⇒ 宿主**起不来**（bean 重名、且不允许覆盖；2026-10-16 部署实测）。
 * 声明是**数据**、不是**组件**：唯一读它的是登记处与检查脚本，没有谁需要它是一个 bean。故现在是一份 {@code static}
 * 清单，宿主容器里**一个 bean 都不多出**——也就再没有可撞的名字。
 *
 * <p><b>⚠ 它不做的事。</b>列在这里**不等于**这条路径受治理。工具面经引擎执行、受确认与审计管住；MCP 那条路只由它自己的
 * 切面管身份与审计、**不经引擎、不扣人**（`YD-T22` 的「丙」）——它的**posture 不在本声明里**，而在线上的那道边界
 * （`scripts/check-ungoverned-tool-beans.mjs` 的边界对象）与 MCP 门禁里。**SKILL 那条更空：它上面一条缝都没有**
 * ——没有身份、没有审计、没有确认、没有拒绝，而它造的 `ShellTools` 在**这台服务器上**跑 shell。它进这份目录只是
 * **被数到了**，不是被管住了。此处只回答「这条路存在、有人枚举过它」。
 *
 * <p>The declaration of every <em>tool face</em> on this host: the places where the AI reaches execution,
 * listed one by one.
 *
 * <p><b>It fills the half the registry could not count.</b> The registry used to hold modules alone
 * ({@link GovernedModule}); the AI also reaches execution here through <em>tools</em> — the places that
 * hand callables to the engine or to a model. None of those places was declared, so beyond the module
 * column "is this host accounted for?" had no answer. There are twelve: eight <b>tool faces</b> (a
 * {@code @Configuration} declaring {@code @Bean AiTool}, or a component that {@code implements AiTool}),
 * two <b>tool paths</b> (the seams that hand tools to a model or the engine: this deployment's own
 * engine face {@code YudaoGovernedAgentTools}, and the MCP path's governance aspect
 * {@code Ai1McpGovernance}), and `ai1`'s two <b>assembling ends</b> ({@code Ai1McpToolFactory} and
 * {@code Ai1SkillToolFactory} — they **assemble** the model-callable tools on this machine; see the
 * warning on the SKILL one below). {@code scripts/check-governance-registry.mjs} reads those shapes in
 * the sources and refuses one not listed here — so this catalogue <b>cannot drift</b>: it is the half
 * the check keeps honest.
 *
 * <p><b>Why tools are a <em>hand-written catalogue</em> while modules <em>declare themselves</em>.</b>
 * A deliberate asymmetry, for one reason: one tool face is <b>generated</b> — `generated/ProductsTools`
 * (from `specs/products.json`). A generated file may not be hand-edited, and making the generator emit
 * the declaration is <b>a separate work item</b> (the generator, `YD-25`), outside this task. So the tool
 * declarations land in <b>this one hand-written place</b>: a module has a carrier that can declare itself
 * (the aspect, which the generator already emits {@code implements GovernedModule} for), a tool face does
 * not — not without touching the generator. Gathering the twelve into one readable catalogue is more
 * honest than a marker on each class plus a special case for the generated face and `ai1`'s two.
 *
 * <p><b>⚠ It is a list, not a set of beans — and this is the corrected version.</b> The first version made
 * each entry a {@code @Bean GovernedPath}, and the method name (e.g. {@code crmCustomerTools}) happened to
 * <b>equal</b> the default bean name of that path's own component ({@code @Component class CrmCustomerTools}
 * ⇒ {@code crmCustomerTools}) — so the host <b>would not start</b> (duplicate bean name, overriding
 * disabled; measured on a 2026-10-16 deploy). A declaration is <b>data</b>, not a <b>component</b>: the
 * only readers are the registry and the check script, and neither needs it to be a bean. It is now a
 * {@code static} list, so the host's context holds <b>not one extra bean</b> — and there is no name left
 * to collide.
 *
 * <p><b>⚠ What it does not do.</b> Being listed here is <b>not</b> being governed. The tool faces run
 * through the engine and are held by confirmation and audit; the MCP path is held by its own aspect for
 * identity and audit alone, <b>without the engine, without holding a call for a person</b> (`YD-T22`'s
 *「丙」) — its posture is not in this declaration but on the boundary the gate reads
 * (`scripts/check-ungoverned-tool-beans.mjs`'s boundary object) and in the MCP gate. **The SKILL path is
 * emptier still: it has no seam at all** — no identity, no audit, no confirmation, no refusal — and the
 * `ShellTools` it builds runs shell **on this server**. Being in this catalogue means it is *counted*,
 * not that it is *held*. This answers only "the path exists and someone enumerated it".
 */
public final class GovernedToolFaces {

    // No Spring annotation, on purpose — see the class comment. A catalogue is data; wiring it as twelve
    // @Bean methods is what a bean name collided with. / 刻意不带 Spring 注解——见类注释。目录是数据；把它接成十二个
    // @Bean 方法，正是 bean 名相撞的来源。
    private GovernedToolFaces() {
    }

    /**
     * 工具面的目录，一次给全。登记处（{@code GovernedModuleRegistry}）读它；那道检查脚本读的是**同一份源码**——按
     * 下面这些 {@code new ToolFace(…)} 认。
     *
     * The catalogue, in full. {@code GovernedModuleRegistry} reads it; the check script reads <b>the same
     * source</b>, by the {@code new ToolFace(…)} each entry names.
     */
    public static List<GovernedPath> all() {
        return List.of(
                // 八个工具面 / the eight tool faces
                new ToolFace("CrmCustomerTools"),
                new ToolFace("CrmFollowUpTools"),
                new ToolFace("BpmOALeaveTools"),
                new ToolFace("FmsVoucherTools"),
                new ToolFace("SystemNotifyMessageTools"),
                new ToolFace("ProductsTools"), // 生成的 / generated
                new ToolFace("ConsolePages"),
                new ToolFace("ListYudaoUsersTool"),
                // 两条工具路 / the two tool paths：受治理那侧的引擎工具面，与 MCP 那条路的治理切面
                new ToolFace("YudaoGovernedAgentTools"),
                new ToolFace("Ai1McpGovernance"),
                // ai1 的两条装配端 / ai1's two assembling ends
                new ToolFace("Ai1McpToolFactory"),
                // ⚠ 本目录里唯一一条「完全没有治理」的路径：没有身份、没有审计、没有拒绝，其 ShellTools 在**本机**跑 shell
                // The one path here with no governance at all: no identity, no audit, no refusal, and its ShellTools runs shell on this host
                new ToolFace("Ai1SkillToolFactory"));
    }

    /**
     * 一条工具面，作为一条声明。名字就是**类的简单名**——那是检查脚本能从源码里读出来、与这里比对的东西。
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
