// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

/**
 * A module that declares itself governed, so that <em>what</em> is held by this host's advices is
 * readable in one place rather than only in a pointcut nobody enumerates.
 *
 * <p><b>Why an interface and not a list.</b> A hand-written list is a second place to forget. A
 * declaration on the module's own class travels with the module: the aspect cannot exist without it,
 * and {@link GovernedModuleRegistry} collects it by constructor injection. What the interface buys is
 * the thing the pointcuts cannot give — a set you can count.
 *
 * <p><b>What it does not do.</b> Declaring yourself governed does not make you governed; the advices
 * do. This marker says "there is something here", and the number is only as true as the declarations
 * are complete — which is why `scripts/check-governance-registry.mjs` reads our sources and refuses a
 * `*Governance` that names a module's own service without declaring itself here. The interface makes
 * the set countable; the check keeps the count honest.
 *
 * <p><b>Where this sits.</b> RuoYi's host carries the same pair ({@code GovernedModule} +
 * {@code GovernedModuleRegistry}) in its own repository, and the platform carries neither — it was
 * checked rather than assumed (2026-10-12). So this is a host-local declaration, not an abstraction
 * being pushed upward, and it leaves `D34` alone.
 *
 * 一个**自报受治理**的模块，使本宿主的通知到底管住了**什么**，在一处可读，而不是只散在一个没人枚举的 pointcut 里。
 *
 * <p><b>为什么是接口、不是一张清单。</b>手写清单就是**第二个可以忘的地方**。声明长在模块自己的类上、随模块走：
 * 切面不可能不带它，而 {@link GovernedModuleRegistry} 以构造器注入把它收起来。接口买到的是 pointcut 给不了的那样
 * 东西——**一个数得出来的集合**。
 *
 * <p><b>它不做什么。</b>声明自己受治理**并不会**让自己受治理；治理是那些通知做的。这个记号说的是「这里有点东西」，
 * 而那个数字**只与声明有多全一样真**——正因如此，`scripts/check-governance-registry.mjs` 去读我们的源码，拒掉一个
 * 点了模块自己的 service、却没在这里自报的 `*Governance`。接口让集合**可数**；那道检查让这个数**诚实**。
 *
 * <p><b>它坐在哪。</b>RuoYi 宿主把同一对（{@code GovernedModule} + {@code GovernedModuleRegistry}）放在**它自己的仓**
 * 里，而**平台两样都没有**——这是 2026-10-12 **查过**的、不是假定的。故本接口是**宿主本地的声明**，不是往上推的抽象，
 * 也就不动 `D34`。
 */
public interface GovernedModule {

    /**
     * The module this class governs, as the build names it — the segment in
     * {@code cn.iocoder.yudao.module.<module>.service}, which is also the one the check reads out of
     * the pointcut and compares against this.
     *
     * 本类治理的模块，按构建的叫法——即 {@code cn.iocoder.yudao.module.<module>.service} 里那一段；那道检查也是从
     * pointcut 里读出它、再与这里比对。
     */
    String module();

    /**
     * The row's owner field this module's advices read: {@code owner_user_id} for the modules the
     * adapter generates and for CRM, {@code user_id} for BPM's leave request. It is named here because
     * it is the per-module reading that does not compress (see
     * {@code keelbase-docs/KEELBASE-MODULE-ONBOARDING.md} §4.1).
     *
     * 本模块的通知所读的**属主字段**：适配器生成的模块与 CRM 是 {@code owner_user_id}，BPM 的请假单是
     * {@code user_id}。它写在这里，因为它是那份**不肯压缩的逐模块读**（见
     * {@code keelbase-docs/KEELBASE-MODULE-ONBOARDING.md} §4.1）。
     */
    String ownerField();
}
