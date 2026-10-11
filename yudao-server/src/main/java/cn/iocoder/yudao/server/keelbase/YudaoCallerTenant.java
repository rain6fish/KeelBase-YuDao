// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;

/**
 * The tenant a host-service call runs under, asked in one place — because four sites used to answer it
 * for themselves, and all four answered with the same constant.
 *
 * <p>Yudao's tenant interceptor adds a {@code tenant_id} predicate to every statement and refuses to run
 * without a tenant in context, and a KeelBase request arrives with none — the runtime's contract had no
 * tenant concept. So the adapter has to establish one. <b>Which</b> tenant was the open half; it is
 * answered now: <b>the tenant the host authenticated the caller under</b>, read from the framework's own
 * context rather than declared by the request. That is the same instinct as the caller id next door — a
 * caller does not get to name its own tenant any more than it names its own identity.
 *
 * <p><b>The seed tenant is what is used when there is nothing to read, and that is a fallback, not the
 * answer.</b> The governed tool path does not always carry a tenant — measured: the role read on that
 * path throws on exactly this — and the data layer refuses to run without one, so the fallback keeps that
 * path working while the honest reading stands wherever there is one. A missing context means we can say
 * less about a caller; it must not stop the call, and it must not invent a wider answer either.
 *
 * <p>It is a static reading of a thread-local holder for the same reason {@code TenantContextHolder} and
 * {@code TenantUtils} are: there is nothing to configure and nowhere else to put it.
 *
 * 宿主服务调用运行在哪个租户下——**只在一个地方问**，因为过去有四处各自回答它，而四处答的是同一个常量。
 *
 * <p>芋道的租户拦截器给每条语句加 `tenant_id` 谓词、且上下文里没有租户就拒跑；而 KeelBase 请求到达时没有租户
 * ——运行时契约里根本没有租户概念。故适配器必须自己建立。**哪个**租户是那未答的一半；**它现在答了**：
 * **宿主据以认证该调用方的那个租户**，读自框架自己的上下文，而不是由请求自述。这与隔壁那条 caller id 是同一个
 * 直觉：**调用方不能自报租户，正如它不能自报身份**。
 *
 * <p>**没有可读的东西时用的是种子租户 —— 那是回落，不是答案。** 受治理的工具路径**不总带租户**（实测：那条路上
 * 的角色读法正是在这一点上抛的），而数据层没有租户就拒跑，故回落让那条路继续可用、同时诚实的那半在有的地方都成立。
 * **缺上下文只意味着我们少说一点这个调用方的事**；它不该让调用停下，也不该反过来编出一个更宽的答案。
 *
 * <p>它是对线程局部持有者的静态读数，理由与 `TenantContextHolder`、`TenantUtils` 相同：没有可配置的东西，也没有
 * 别的地方可放。
 */
public final class YudaoCallerTenant {

    /**
     * What the four call sites used to hard-code. It stays as the fallback for a path with no tenant in
     * context — declared, in one place, rather than repeated and mistaken for an answer.
     *
     * 那四处过去写死的值。它留下作「上下文里没有租户」那条路的**回落**——**声明出来、且只此一处**，而不是各写
     * 一遍、被当成答案。
     */
    private static final long SEED_TENANT_ID = 1L;

    private YudaoCallerTenant() {
    }

    /** The tenant this call runs under: the host's own reading, else the declared fallback. */
    public static long current() {
        Long authenticated = TenantContextHolder.getTenantId();
        return authenticated == null ? SEED_TENANT_ID : authenticated;
    }

    /**
     * Whether this call's tenant was read rather than fallen back to — for a place that would rather say
     * which of the two happened than guess.
     *
     * 这个调用的租户是**读到的**还是**回落的**——留给愿意说清是哪种、而不是猜的地方。
     */
    public static boolean isRead() {
        return TenantContextHolder.getTenantId() != null;
    }
}
