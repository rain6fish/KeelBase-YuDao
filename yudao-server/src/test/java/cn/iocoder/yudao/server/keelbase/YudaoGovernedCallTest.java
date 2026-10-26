// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import static org.junit.jupiter.api.Assertions.assertSame;

import cn.com.keelbase.runtime.authz.RuleDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * What a write's refusal looks like on its way out of the tenant wrapper.
 *
 * <p>{@code TenantUtils.execute} flattens every thrown exception into a bare {@code RuntimeException},
 * and the runtime only frees a write claim when it can see the refusal itself. So this class has one
 * job with two inputs, and the second input is the one that was missing: Spring Security's
 * {@code AccessDeniedException} and the runtime's {@code RuleDeniedException} are both refusals the
 * aspect raises before {@code proceed}, and both have to come back out as themselves. Recognising only
 * the first was measured as a regression — the claim stayed `claimed`, so an identical retry of a write
 * that never happened would have been refused as already executed.
 *
 * 一次写的拒绝在**离开租户包装**时是什么样子。
 *
 * <p>{@code TenantUtils.execute} 把抛出的每个异常压平成一层裸 {@code RuntimeException}，而运行时**只在看得见
 * 拒绝本身**时才释放写声明。于是本类只有一件事、两个入参，而**第二个入参**正是当初漏掉的那个：Spring Security
 * 的 {@code AccessDeniedException} 与运行时的 {@code RuleDeniedException} 都是切面在 {@code proceed} 之前抛的
 * 拒绝，两者都必须原样出来。只认前者是**被量出来的**回归 —— 声明停在 `claimed`，于是一次**从未发生**的写的
 * 相同重放，会被当成「已执行」拒掉。
 */
class YudaoGovernedCallTest {

    @Test
    void aTenantWrappedRuntimeRefusalComesBackAsItself() {
        RuleDeniedException refusal =
                new RuleDeniedException(null, "该行不在你的数据范围内", "Customer", "update", null);

        RuntimeException raised = YudaoGovernedCall.refusalInsideOneLayer(new RuntimeException(refusal));

        assertSame(refusal, raised, "the runtime has to see its own refusal type to free the claim");
    }

    @Test
    void aTenantWrappedSecurityRefusalComesBackAsItself() {
        AccessDeniedException refusal = new AccessDeniedException("该行不在你的数据范围内");

        RuntimeException raised = YudaoGovernedCall.refusalInsideOneLayer(new RuntimeException(refusal));

        assertSame(refusal, raised);
    }

    @Test
    void aFailureThatIsNotARefusalIsLeftExactlyAsItWas() {
        RuntimeException failure = new RuntimeException(new IllegalStateException("connection reset"));
        RuntimeException plain = new RuntimeException("no cause at all");

        assertSame(failure, YudaoGovernedCall.refusalInsideOneLayer(failure));
        assertSame(plain, YudaoGovernedCall.refusalInsideOneLayer(plain));
    }

    @Test
    void onlyOneLayerIsUnwrapped() {
        // A refusal two layers down is NOT this class's evidence: the tenant wrapper is one layer, and
        // going deeper would widen "a bare refusal" into "anything containing one" — the step the
        // runtime declines to take, because a tool that wrote and then failed would look safe.
        //
        // 第二层深处的拒绝**不是**本类的证据：租户只有一层，再往里拆就把「一个裸拒绝」放宽成「cause 链里有它的
        // 一切」—— 那正是运行时拒绝走的一步，因为「写了才失败」的工具会因此看起来安全。
        RuleDeniedException refusal =
                new RuleDeniedException(null, "该行不在你的数据范围内", "Customer", "update", null);
        RuntimeException outer = new RuntimeException(new RuntimeException(refusal));

        assertSame(outer, YudaoGovernedCall.refusalInsideOneLayer(outer));
    }
}
