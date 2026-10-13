// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The adversarial demonstration, run against <em>this</em> deployment rather than copied from a
 * reference.
 *
 * <p>What each scenario reports is the runtime's own answer, and the raw engine status is carried
 * beside it so nothing here invents a vocabulary: a refusal that the engine calls {@code error}
 * because a tool said no is shown as a refusal <em>and</em> as the engine's word for it. The runner
 * drives the engine through the one entry every proposal travels ({@code execute}); it implements no
 * gate, no confirmation policy and no scope rule of its own — those stay in {@code keelbase4j-core},
 * which is why this file can exist on a host without moving a boundary onto it.
 *
 * <p><b>Four scenarios, not the reference's five.</b> This runtime's gate decides on risk level
 * alone ({@code R5 → block}); it has no policy toggles and no role allowlist, and this deployment
 * declares no R4 or R5 tool. So "R5 blocked" has nothing to land on here, and "prompt injection" has
 * no prompt to land on — this deployment's planner is rule-based. Both are recorded in
 * {@code keelbase-docs/KEELBASE-SHOWCASE.md} rather than faked.
 *
 * 对抗性演示，跑**本部署自己**、而不是从参照实现抄一份。
 *
 * <p>每条场景报的是**运行时自己的答复**，并把**引擎的原始状态**一并带回来——故这里不发明任何词表：
 * 工具说「不」而引擎把它叫 {@code error} 的一次拒绝，会**既**作为一次拒绝显示、**也**带上引擎对它的
 * 叫法。运行器只从**所有提议都会走的那一个入口**（{@code execute}）驱动引擎；它自己**不实现**任何门、
 * 任何确认策略、任何范围规则——那些留在 {@code keelbase4j-core} 里，这也是本文件能存在于宿主上、
 * 而不把边界搬到宿主上的原因。
 *
 * <p><b>四条场景，不是参照实现那五条。</b> 本运行时的门**只看风险级**（{@code R5 → block}）；它没有
 * 策略开关、没有角色白名单，而本部署也**没有声明任何 R4/R5 工具**。故「R5 阻断」在这里**没有可落的
 * 东西**，「提示词注入」也没有提示词可注入——本部署的规划器是**规则版**。两者记在
 * {@code keelbase-docs/KEELBASE-SHOWCASE.zh-CN.md} 里，而不是编一个出来。
 */
@Component
public class SecurityShowcase {

    /** The engine's own status for a call that ran and was recorded. */
    private static final String STATUS_EXECUTED = "executed";

    private final GovernedExecutionEngine engine;

    public SecurityShowcase(GovernedExecutionEngine engine) {
        this.engine = engine;
    }

    /** One scenario as the console lists it: identity first, expectation second. */
    public record Scenario(String id, String category, String drives, String expect) {
    }

    /**
     * The four, in the order the console shows them. `drives` says what is actually exercised, so a
     * reader can tell what a green result did and did not prove.
     */
    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("unauthorized", "unauthorized",
                    "a read of a message that does not belong to the caller",
                    "the tool refuses it"),
            new Scenario("confirmation", "confirmation",
                    "an R3 write, which this runtime holds for a person",
                    "the engine holds it and returns a token"),
            new Scenario("unknown-tool", "hallucination",
                    "a tool name that does not exist",
                    "the registry refuses to resolve it"),
            new Scenario("positive-control", "control",
                    "a read the caller may perform",
                    "it runs and returns data"));

    public List<Map<String, Object>> listScenarios() {
        return SCENARIOS.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("category", s.category());
            m.put("drives", s.drives());
            m.put("expect", s.expect());
            return m;
        }).toList();
    }

    /**
     * Run one. Unknown ids are answered as such rather than guessed at.
     *
     * <p>{@code outcome} is what the console shows; {@code engine} is the runtime's own word for the
     * same event, and is null when the call never reached the engine (a name that would not resolve).
     * {@code detail} carries the tool's own sentence, because "denied" without the reason is a claim
     * nobody can check.
     *
     * 跑其中一条。不认识的 id 就如实答「不认识」，不猜。
     *
     * <p>{@code outcome} 是控制台显示的那个；{@code engine} 是运行时对同一件事自己的叫法，而当这次调用
     * **根本没到引擎**（名字解析不出来）时为 null。{@code detail} 带着工具自己的那句话——因为一个没有
     * 理由的「denied」，是没人能核的主张。
     */
    public Map<String, Object> run(String id, Principal caller) {
        Scenario scenario = SCENARIOS.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
        if (scenario == null) {
            return result(id, "unknown-scenario", null,
                    "no scenario by that id in this deployment");
        }
        return switch (id) {
            case "unauthorized" -> unauthorized(caller);
            case "confirmation" -> confirmation(caller);
            case "unknown-tool" -> unknownTool(caller);
            case "positive-control" -> positiveControl(caller);
            default -> result(id, "unknown-scenario", null, "no scenario by that id");
        };
    }

    /**
     * A read the caller may not perform. The id is a synthetic one, and that is faithful rather than
     * a shortcut: {@code get_notify_message} answers "not yours" and "does not exist" with the same
     * sentence — it withholds both alike, so an id outside the caller's scope exercises the same
     * refusal path a real foreign row would.
     *
     * <p><b>What this does not prove.</b> It shows the refusal is reached; that a <em>real</em> row
     * belonging to somebody else is withheld is the tool's own tested behaviour, not something this
     * page re-establishes. Saying so is the difference between a demonstration and a claim.
     *
     * 一次调用方做不了的读。id 是**合成的**，而这是忠实、不是走捷径：{@code get_notify_message} 对
     * 「不是你的」与「不存在」回**同一句话**——两者它**一概扣下**，故一个在调用方范围外的 id 走的是
     * **与真外来的行相同**的拒绝路径。
     *
     * <p><b>它证明不了什么。</b> 它显示这条拒绝**到得了**；而**真正属于别人的行**会被扣下，是那个工具
     * 自己**被测过**的行为，不是本页重新确立的东西。把这句话说出来，是「演示」与「主张」的分别。
     */
    private Map<String, Object> unauthorized(Principal caller) {
        // Negative and outside any sequence this deployment issues, so it cannot collide with a row
        // the caller does own.
        // 取负数、且在任何本部署会发出的序列之外，故不可能撞上调用方真拥有的行。
        Map<String, Object> args = Map.of("id", -1);
        try {
            ExecutionOutcome outcome = engine.execute("get_notify_message", args, caller);
            boolean refused = "error".equals(outcome.status());
            return result("unauthorized", refused ? "denied" : "not-denied", outcome.status(),
                    refused
                            ? "the read was refused, with the tool's own sentence"
                            : "the read was not refused — this is a finding, not a passing scenario");
        } catch (RuntimeException refused) {
            return result("unauthorized", "denied", refused.getClass().getSimpleName(),
                    "refused: " + refused.getMessage());
        }
    }

    /**
     * An R3 write. The engine holds it and hands back a token; nothing is written, which is the
     * point. A token is left behind on every run — said here rather than discovered later.
     *
     * <p>The arguments are exactly the ones the tool declares: an extra one would be refused as a
     * malformed proposal, and the scenario would report {@code invalid_arguments} while looking like
     * it had tested the confirmation gate.
     *
     * 一次 R3 写。引擎扣下它、交回一个 token；**什么都没写**，这正是要点。每次跑都会留下一个 token——
     * 这句话写在这里，而不是让别人日后自己发现。
     *
     * <p>参数就是那个工具**自己声明**的那几个：多给一个会被当成**畸形提议**拒掉，于是这条场景会报
     * {@code invalid_arguments}，而**看上去**像是刚测过确认门。
     */
    private Map<String, Object> confirmation(Principal caller) {
        Map<String, Object> args = Map.of("name", "showcase — never confirmed");
        try {
            ExecutionOutcome outcome = engine.execute("create_crm_customer", args, caller);
            boolean held = "pending_confirmation".equals(outcome.status())
                    || "requires_approval".equals(outcome.status());
            return result("confirmation", held ? "requiresConfirmation" : "not-held", outcome.status(),
                    held
                            ? "held for a person; a token was returned and nothing was written"
                            : "the write was not held — this is a finding, not a passing scenario");
        } catch (RuntimeException e) {
            return result("confirmation", "unresolved", e.getClass().getSimpleName(),
                    "the write could not be proposed: " + e.getMessage());
        }
    }

    /** A name no tool has. The registry's refusal is the whole of this scenario. */
    private Map<String, Object> unknownTool(Principal caller) {
        String name = "no_such_tool_xyz";
        try {
            engine.execute(name, Map.of(), caller);
            return result("unknown-tool", "executable", null,
                    "a name with no tool behind it ran — this is a finding, not a passing scenario");
        } catch (RuntimeException e) {
            return result("unknown-tool", "unresolved", e.getClass().getSimpleName(),
                    "not executable: " + e.getMessage());
        }
    }

    /**
     * The control. Without it, "refuse everything" would pass every scenario above — so the page
     * needs one call that must <em>succeed</em>.
     *
     * 正向对照。没有它，「一律拒绝」能通过上面每一条——故本页需要一次**必须成功**的调用。
     */
    private Map<String, Object> positiveControl(Principal caller) {
        try {
            ExecutionOutcome outcome = engine.execute("list_crm_customers", Map.of(), caller);
            boolean ran = STATUS_EXECUTED.equals(outcome.status());
            return result("positive-control", ran ? "allowed" : "not-allowed", outcome.status(),
                    ran
                            ? "a read the caller may perform went through"
                            : "a read that should have gone through did not — this is a finding");
        } catch (RuntimeException e) {
            return result("positive-control", "not-allowed", e.getClass().getSimpleName(),
                    "a read that should have gone through did not: " + e.getMessage());
        }
    }

    private static Map<String, Object> result(String id, String outcome, String engine, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("outcome", outcome);
        m.put("engine", engine);
        m.put("detail", detail);
        return m;
    }
}
