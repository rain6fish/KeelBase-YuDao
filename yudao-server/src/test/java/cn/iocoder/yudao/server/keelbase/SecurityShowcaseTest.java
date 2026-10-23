// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link SecurityShowcase} 的测试：每条场景报的是**引擎自己的答复**，而不是编出来的话。
 *
 * <p>这里断言的是本类 Javadoc 写下的几条承诺：四条场景按固定顺序列出；不认识的 id 如实答「不认识」；
 * 每条场景把引擎的状态**原样**带回并据此判定 outcome；引擎抛异常时如实记下异常类名；参数按工具声明的
 * 样子送出（多给一个会被当成畸形提议）；调用方原样传下去。本类不实现任何门、不缓存任何结果 —— 每条
 * 断言都落在这几件事上，不落在措辞的细枝末节上。
 *
 * <p>Tests for {@link SecurityShowcase}: each scenario reports the <em>engine's own</em> answer rather
 * than an invented one. They assert what the class Javadoc promises: the four scenarios are listed in a
 * fixed order; an unknown id is answered as such; each scenario carries the engine status back verbatim
 * and derives its outcome from it; an engine exception is reported by class name; arguments are sent as
 * the tool declares them (an extra one would be a malformed proposal); the caller travels through
 * unchanged. No gate, policy or scope rule lives here — the assertions stay on those promises, not on
 * incidental wording.
 */
class SecurityShowcaseTest {

    private GovernedExecutionEngine engine;
    private SecurityShowcase showcase;
    private Principal caller;

    @BeforeEach
    void setUp() {
        engine = mock(GovernedExecutionEngine.class);
        showcase = new SecurityShowcase(engine);
        caller = new Principal("42", Principal.ROLE_USER);
    }

    private static ExecutionOutcome outcome(String status) {
        return new ExecutionOutcome(status, null, null, null, null);
    }

    @Test
    void scenariosAreListedInTheOrderTheConsoleShowsThem() {
        List<Map<String, Object>> scenarios = showcase.listScenarios();

        assertEquals(List.of("unauthorized", "confirmation", "unknown-tool", "positive-control"),
                scenarios.stream().map(s -> s.get("id")).toList());
        assertEquals(List.of("unauthorized", "confirmation", "hallucination", "control"),
                scenarios.stream().map(s -> s.get("category")).toList());
        // `drives` says what is actually exercised, so a reader can tell what a green result proved.
        for (Map<String, Object> scenario : scenarios) {
            assertFalse(((String) scenario.get("drives")).isBlank(), scenario.get("id") + " drives nothing");
            assertFalse(((String) scenario.get("expect")).isBlank(), scenario.get("id") + " expects nothing");
        }
        verifyNoInteractions(engine);
    }

    @Test
    void anUnknownIdIsAnsweredAsSuchAndNeverReachesTheEngine() {
        Map<String, Object> result = showcase.run("no-such-scenario", caller);

        assertEquals("no-such-scenario", result.get("id"));
        assertEquals("unknown-scenario", result.get("outcome"));
        assertNull(result.get("engine"), "the call never reached the engine, so there is no engine word");
        assertFalse(((String) result.get("detail")).isBlank());
        verifyNoInteractions(engine);
    }

    @Test
    void aNullIdIsAlsoUnknownRatherThanAGuess() {
        Map<String, Object> result = showcase.run(null, caller);

        assertEquals("unknown-scenario", result.get("outcome"));
        verifyNoInteractions(engine);
    }

    @Test
    void unauthorizedReportsTheEngineStatusVerbatimAndCallsTheReadOutsideTheCallersScope() {
        when(engine.execute(eq("get_notify_message"), any(), any())).thenReturn(outcome("error"));

        Map<String, Object> result = showcase.run("unauthorized", caller);

        assertEquals("denied", result.get("outcome"));
        assertEquals("error", result.get("engine"), "the engine's own word is carried beside the outcome");
        verify(engine).execute(eq("get_notify_message"), eq(Map.of("id", -1)), same(caller));
    }

    @Test
    void unauthorizedIsAFindingWhenTheReadWasNotRefused() {
        when(engine.execute(eq("get_notify_message"), any(), any())).thenReturn(outcome("executed"));

        Map<String, Object> result = showcase.run("unauthorized", caller);

        assertEquals("not-denied", result.get("outcome"));
        assertTrue(((String) result.get("detail")).contains("finding"),
                "a call that should have been refused and was not must read as a finding, not a pass");
    }

    @Test
    void unauthorizedReportsAnEngineRefusalThatArrivedAsAnException() {
        when(engine.execute(eq("get_notify_message"), any(), any()))
                .thenThrow(new IllegalStateException("not yours"));

        Map<String, Object> result = showcase.run("unauthorized", caller);

        assertEquals("denied", result.get("outcome"));
        assertEquals("IllegalStateException", result.get("engine"));
        assertTrue(((String) result.get("detail")).contains("not yours"),
                "detail carries the reason, because a denial without one is a claim nobody can check");
    }

    @Test
    void confirmationIsHeldWhenTheEngineSaysPendingOrRequiresApproval() {
        when(engine.execute(eq("create_crm_customer"), any(), any())).thenReturn(outcome("pending_confirmation"));

        Map<String, Object> pending = showcase.run("confirmation", caller);
        assertEquals("requiresConfirmation", pending.get("outcome"));
        assertEquals("pending_confirmation", pending.get("engine"));

        when(engine.execute(eq("create_crm_customer"), any(), any())).thenReturn(outcome("requires_approval"));
        Map<String, Object> approval = showcase.run("confirmation", caller);
        assertEquals("requiresConfirmation", approval.get("outcome"));
        assertEquals("requires_approval", approval.get("engine"));
    }

    @Test
    void confirmationIsAFindingWhenTheWriteWasNotHeld() {
        when(engine.execute(eq("create_crm_customer"), any(), any())).thenReturn(outcome("executed"));

        Map<String, Object> result = showcase.run("confirmation", caller);

        assertEquals("not-held", result.get("outcome"));
        assertTrue(((String) result.get("detail")).contains("finding"));
    }

    @Test
    void confirmationSendsExactlyTheArgumentsTheToolDeclares() {
        when(engine.execute(eq("create_crm_customer"), any(), any())).thenReturn(outcome("pending_confirmation"));

        showcase.run("confirmation", caller);

        // An extra argument would be refused as a malformed proposal and the scenario would look like
        // it had tested the confirmation gate. / 多给一个参数会被当成畸形提议拒掉，而这条场景会看上去
        // 像是刚测过确认门。
        verify(engine).execute("create_crm_customer", Map.of("name", "showcase — never confirmed"), caller);
    }

    @Test
    void confirmationReportsAProposalThatCouldNotBeMadeAtAll() {
        when(engine.execute(eq("create_crm_customer"), any(), any()))
                .thenThrow(new IllegalArgumentException("bad args"));

        Map<String, Object> result = showcase.run("confirmation", caller);

        assertEquals("unresolved", result.get("outcome"));
        assertEquals("IllegalArgumentException", result.get("engine"));
        assertTrue(((String) result.get("detail")).contains("bad args"));
    }

    @Test
    void unknownToolPassesWhenTheRegistryRefusesToResolveIt() {
        when(engine.execute(eq("no_such_tool_xyz"), any(), any()))
                .thenThrow(new IllegalArgumentException("no tool named no_such_tool_xyz"));

        Map<String, Object> result = showcase.run("unknown-tool", caller);

        assertEquals("unresolved", result.get("outcome"));
        assertEquals("IllegalArgumentException", result.get("engine"));
        verify(engine).execute("no_such_tool_xyz", Map.of(), caller);
    }

    @Test
    void unknownToolThatRanIsAFindingNotAPass() {
        when(engine.execute(eq("no_such_tool_xyz"), any(), any())).thenReturn(outcome("executed"));

        Map<String, Object> result = showcase.run("unknown-tool", caller);

        assertEquals("executable", result.get("outcome"));
        assertNull(result.get("engine"), "a call that ran has no engine refusal to carry");
        assertTrue(((String) result.get("detail")).contains("finding"));
    }

    @Test
    void positiveControlIsAllowedOnlyWhenTheEngineExecutedIt() {
        when(engine.execute(eq("list_crm_customers"), any(), any())).thenReturn(outcome("executed"));

        Map<String, Object> result = showcase.run("positive-control", caller);

        assertEquals("allowed", result.get("outcome"));
        assertEquals("executed", result.get("engine"));
        verify(engine).execute("list_crm_customers", Map.of(), caller);
    }

    @Test
    void positiveControlIsAFindingWhenTheReadThatShouldHaveGoneThroughDidNot() {
        when(engine.execute(eq("list_crm_customers"), any(), any())).thenReturn(outcome("error"));

        Map<String, Object> result = showcase.run("positive-control", caller);

        assertEquals("not-allowed", result.get("outcome"));
        assertTrue(((String) result.get("detail")).contains("finding"));
    }

    @Test
    void positiveControlReportsAnExceptionAsNotAllowedWithItsClass() {
        when(engine.execute(eq("list_crm_customers"), any(), any()))
                .thenThrow(new IllegalStateException("store offline"));

        Map<String, Object> result = showcase.run("positive-control", caller);

        assertEquals("not-allowed", result.get("outcome"));
        assertEquals("IllegalStateException", result.get("engine"));
        assertTrue(((String) result.get("detail")).contains("store offline"));
    }
}
