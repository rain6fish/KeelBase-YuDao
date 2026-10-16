// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.identity.ActingCaller;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import java.util.concurrent.Callable;
import org.springframework.security.access.AccessDeniedException;

/**
 * 一次受治理的写，跑在**调用方**下、**租户**下——并且让这次写的**拒绝**以它自己的形状浮出来。
 *
 * <p><b>它补的是什么。</b>宿主把一次受治理的写包在 {@link TenantUtils#execute(Long, Callable)} 里，而那个重载
 * 的收尾是 `catch (Exception e) { throw new RuntimeException(e); }`。切面（如
 * {@code CrmCustomerGovernance.requireOwnership}）在 {@code call.proceed()} **之前**抛的
 * {@link AccessDeniedException} 因此被**压平**成一层裸 `RuntimeException`；而运行时那句
 * 「一次失败**证明**没落库就释放认领」（`provesNothingLanded`）只认**裸类型**，认不出被压平的 ⇒ 一次被拒的写，
 * 在 `write_claims` 里留下一条 `effect_id is null` 的 `claimed` 行。
 *
 * <p><b>为什么修在这里、而不是去让运行时沿 cause 链认。</b>那一处的 javadoc 明说它**刻意**只认裸类型：一个把远端
 * 调用失败包进这类异常的工具**有可能先写了再失败**，而按那个猜测放走的键，正是「写两次」的来路。这份「什么都没落地」
 * 的证据**只存在于宿主这一侧**——切面在 proceed 之前抛——所以它在**这里**被交回它自己的类型。
 *
 * <p><b>只拆一层。</b>这一层就是 {@link TenantUtils} 那一层包装，别的一律不动：往 cause 链更深处解包，就是把
 * 「一个裸的拒绝类型」悄悄放宽成「cause 链里含它的一切」——正是运行时拒绝走的那一步。不是那一层的失败，原样抛出。
 *
 * A governed write, run under the caller and the tenant — and a refusal reaches the runtime in its own
 * shape instead of being flattened by the tenant wrapper.
 *
 * <p><b>What it repairs.</b> The host runs a governed write inside
 * {@link TenantUtils#execute(Long, Callable)}, whose tail is
 * `catch (Exception e) { throw new RuntimeException(e); }`. An {@link AccessDeniedException} the aspect
 * (e.g. {@code CrmCustomerGovernance.requireOwnership}) raises <em>before</em> {@code call.proceed()} is
 * therefore flattened into a bare `RuntimeException`, and the runtime's "the failure proves nothing
 * landed, release the claim" (`provesNothingLanded`) names the bare type only — so a refused write
 * leaves a `claimed` row with `effect_id is null` in `write_claims`.
 *
 * <p><b>Why here, and not by teaching the runtime to walk the cause chain.</b> That javadoc says it
 * names the bare type <em>on purpose</em>: a tool that wraps a remote call's failure in one of those may
 * well have written first, and a key freed on that guess is how a write happens twice. The evidence that
 * nothing landed exists only on the host's side — the aspect raises before proceed — so it is handed
 * back here, as its own type.
 *
 * <p><b>It unwraps one layer.</b> That layer is {@link TenantUtils}'s wrapper and nothing else:
 * unwrapping further down the cause chain would quietly widen "one bare refusal type" into "anything
 * whose cause chain contains one" — exactly the step the runtime declines to take. A failure that is not
 * that layer is re-thrown unchanged.
 */
public final class YudaoGovernedCall {

    private YudaoGovernedCall() {
    }

    /**
     * Runs {@code body} as {@code userId}, under this call's tenant — and re-raises a tenant-wrapped
     * refusal as the refusal itself, so the runtime can recognise it.
     *
     * 以 {@code userId}、在本调用的租户下跑 {@code body}——并把被租户包装的拒绝**作为拒绝本身**重抛，于是运行时
     * 认得出它。
     */
    public static <V> V write(String userId, Callable<V> body) {
        try {
            return ActingCaller.as(userId, () -> TenantUtils.execute(YudaoCallerTenant.current(), body));
        } catch (RuntimeException failed) {
            if (failed.getCause() instanceof AccessDeniedException refusal) {
                throw refusal;
            }
            throw failed;
        }
    }
}
