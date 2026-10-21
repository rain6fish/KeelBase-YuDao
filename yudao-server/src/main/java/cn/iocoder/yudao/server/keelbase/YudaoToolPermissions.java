// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.identity.ActingCaller;
import cn.iocoder.yudao.framework.common.biz.system.permission.PermissionCommonApi;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import org.springframework.stereotype.Component;

/**
 * 一道工具路径上的权限判据：**读自宿主自己的权限表**，问法与 REST 路径上那句
 * {@code @PreAuthorize("@ss.hasPermission(...)")} 是**同一个问题**。
 *
 * <p><b>它补的是什么。</b>REST 路径由安全过滤器链上的权限注解挡着；AI 的工具路径跑在那条链**之外**，
 * 故同一个写，REST 对普通调用方答 403、工具却照做。这里不新增一套词表，也不放宽 REST——只是让工具问出
 * 与 REST 相同的那一问，且**无权限就不执行**。
 *
 * <p><b>三道回落都朝「拒绝」。</b>指不出调用方、权限读不出来、权限码在本宿主没有对应的菜单（芋道的严格
 * 模式本来也这么判）——三者都答「没有权限」。判据读不出来时放行，等于把「查不到」当成「有」；那一步不做。
 *
 * <p><b>租户与身份都按宿主自己的读法给。</b>权限读法要一个租户上下文，而工具路径不总带；{@link
 * YudaoCallerTenant} 是那一个回答处。身份用 {@link ActingCaller} 带着走，与隔壁那些受治理的读法是同一条路。
 *
 * An authorisation check on the tool path: asked of the host's own permission table, the same question
 * the REST path's {@code @PreAuthorize("@ss.hasPermission(...)")} asks.
 *
 * <p><b>What it repairs.</b> REST paths are held by the permission annotation on the security filter
 * chain; the AI tool path runs outside that chain, so the same write answers 403 to an ordinary caller
 * on REST and is executed by the tool. This adds no second vocabulary and does not widen REST — it makes
 * the tool ask the same question REST asks, and it does not run when the answer is no.
 *
 * <p><b>All three fallbacks deny.</b> No caller to ask about, a permission read that fails, and a
 * permission code with no menu behind it (Yudao's strict mode already answers no for that) all answer
 * "not permitted". Reading a failed check as "allowed" would turn "could not look" into "has it"; that
 * step is not taken.
 *
 * <p><b>Tenant and identity are supplied the host's own way.</b> The permission read needs a tenant in
 * context and the tool path does not always carry one; {@link YudaoCallerTenant} is the one place that
 * answers it. The identity travels through {@link ActingCaller}, the same route the governed reads next
 * door use.
 */
@Component
public class YudaoToolPermissions {

    private final PermissionCommonApi permissions;

    public YudaoToolPermissions(PermissionCommonApi permissions) {
        this.permissions = permissions;
    }

    /**
     * 调用方缺这条权限码时给出的理由；有权限时返回 {@code null}。
     *
     * <p>理由里**点名那条权限码**，因为调用方（或读审计的人）要能据此补齐授权，而不是拿到一句「无权」。
     *
     * The reason the caller lacks this permission code, or {@code null} when it holds it.
     *
     * <p>The reason **names the permission code**, so the caller (or an auditor reading the trail) can
     * act on it rather than receiving a bare "not allowed".
     */
    public String refusal(String userId, String permission) {
        Long caller = asId(userId);
        if (caller == null) {
            return "not permitted: there is no local caller to hold the '" + permission + "' permission";
        }
        boolean allowed;
        try {
            allowed = ActingCaller.as(userId, () -> TenantUtils.execute(YudaoCallerTenant.current(),
                    () -> permissions.hasAnyPermissions(caller, permission)));
        } catch (RuntimeException cannotRead) {
            return "not permitted: the '" + permission + "' permission could not be verified";
        }
        return allowed ? null : "not permitted: this caller lacks the '" + permission + "' permission";
    }

    private static Long asId(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException notAnId) {
            return null;
        }
    }
}
