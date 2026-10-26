// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.codegen;

import cn.com.keelbase.protocol.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 从一份 {@code specs/*.json} 读出的那一小块规格：一个对象（= 一张表）、它的字段，以及它声明的 AI 能力。
 *
 * <p>本生成器**只用得上一个对象**（`objects[0]`）——它产出的一个模块就是「一张表 + 一读一写」，多对象不在射程内。
 * 实体名取复数化后的大驼峰（`product` ⇒ `Products`），因为宿主自己的生成器按这个形状命名它的 DO / VO / service。
 * 另留一个 {@code subject}：规格里那个对象名的**单数**形（`product` ⇒ `Product`），因为一次拒绝点名的是单数资源名，
 * 而不是生成类所用的复数实体名。
 *
 * <p>这是读 Spec 的**唯一**入口：三种产出（DDL / 工具面 / 治理切面）都拿同一个 {@code Spec} 实例，故它们看到的是
 * 同一份字段表、同一个能力清单，不会各自解析一遍而读出不同的东西。它同时也是**拒绝自相矛盾规格**的地方：一条
 * 声明了 `requiresConfirmation` 的能力，其布尔必须与它的风险级一致（见 {@link Cap}），否则读取即失败。
 *
 * <p>The slice of a {@code specs/*.json} this generator reads: one object (one table), its fields, and
 * the AI capabilities it declares. Only the first object is used — one module here is one table with a
 * read and a write, and multiple objects are out of scope. The entity name is the pluralised, Pascal-cased
 * object name, because that is the shape the host's own generator names its DO / VO / service after.
 * A separate {@code subject} carries the object name in the **singular** (`product` → `Product`): a refusal
 * names the singular resource, not the plural entity the generated classes are called after. This
 * is the single entry point for reading a Spec, so all three outputs share one parsed view. It is also
 * where a self-contradicting Spec is refused: a capability that states {@code requiresConfirmation} must
 * agree with its risk level (see {@link Cap}), or reading fails.
 */
record Spec(String module, String entity, String subject, String label, List<Field> fields,
            List<Cap> caps) {

    /** One field of the object: its column name, its Spec type word, whether it is required, its label. */
    record Field(String name, String type, boolean required, String label) {
    }

    /**
     * 一条声明的 AI 能力：它做什么（`read` / `write`）、它的风险级，以及**规格若写了的话**——它是否需确认。
     *
     * <p><b>风险级是唯一的权威。</b>`requiresConfirmation` 是一句**派生**声明：它说的正是风险级已经蕴含的东西
     * （协议 §4 —— R3/R4 才是「为人停下」的两级）。故一条**写了**这个布尔、却与其风险级相矛盾的规格会被**拒绝**，
     * 而不是被半信半疑：工具由风险级写出，那个布尔是一句被生成器**核对**的复述，不是第二个旋钮。
     *
     * <p>One declared AI capability: what it does (`read` / `write`), its risk level, and — when the Spec
     * states it — whether it needs confirmation. <b>The risk level is the one authority.</b>
     * {@code requiresConfirmation} is a <em>derived</em> statement: it says what the level already implies
     * (protocol §4 — R3/R4 are the levels that stop for a human). A Spec that states the flag yet
     * contradicts its own level is refused rather than half-believed: the tools are written from the
     * level, and the flag is a restatement the generator checks, not a second knob.
     */
    record Cap(String kind, String riskLevel, Boolean requiresConfirmation) {

        /**
         * 该能力的工具面将携带的风险级：读是 R1，写取声明级、无则 R3 —— 与 {@link ToolsGenerator} 写进工具的默认一致。
         *
         * <p>The level the tool face will carry for this capability: a read is R1, a write is its declared
         * level or R3 — the same defaults {@link ToolsGenerator} writes into the tools.
         */
        String effectiveLevel() {
            return "read".equals(kind) ? "R1" : riskLevel != null ? riskLevel : "R3";
        }
    }

    /** Read and normalise a Spec from disk; an unreadable or empty one is a hard failure, not a default. */
    static Spec read(Path path) throws IOException {
        JsonNode spec = new ObjectMapper().readTree(path.toFile());
        JsonNode objects = spec.get("objects");
        JsonNode object = objects != null && objects.isArray() && !objects.isEmpty() ? objects.get(0) : null;
        if (object == null || !object.hasNonNull("fields") || object.get("fields").isEmpty()) {
            throw new IllegalArgumentException("spec has no object with fields");
        }
        String module = object.hasNonNull("module") ? object.get("module").asText() : spec.get("feature").asText();
        String name = object.get("name").asText();
        String label = object.hasNonNull("label") ? object.get("label").asText() : name;
        List<Field> fields = new ArrayList<>();
        for (JsonNode field : object.get("fields")) {
            fields.add(new Field(field.get("name").asText(), field.get("type").asText(),
                    field.hasNonNull("required") && field.get("required").asBoolean(),
                    field.hasNonNull("label") ? field.get("label").asText() : field.get("name").asText()));
        }
        List<Cap> caps = new ArrayList<>();
        JsonNode capabilities = spec.get("aiCapabilities");
        if (capabilities != null && capabilities.isArray()) {
            for (JsonNode cap : capabilities) {
                Cap parsed = new Cap(cap.path("kind").asText(),
                        cap.hasNonNull("riskLevel") ? cap.get("riskLevel").asText() : null,
                        cap.hasNonNull("requiresConfirmation") ? cap.get("requiresConfirmation").asBoolean() : null);
                requireConsistent(path, parsed);
                caps.add(parsed);
            }
        }
        return new Spec(module, pascal(plural(name)), pascal(name), label, List.copyOf(fields),
                List.copyOf(caps));
    }

    /**
     * 拒绝一条自相矛盾的规格：一条能力**写了** `requiresConfirmation`，那句话就必须正是其风险级已经蕴含的东西 ——
     * 因为工具与运行时依据的是风险级（协议 §4），而一个与之不符的布尔会是一个**第二权威**、且没人读。既然写了，
     * 就写对；否则这条规格读不出来（且报出是哪一条能力、差在哪）。
     *
     * <p>Refuse a Spec that contradicts itself: when a capability <em>states</em>
     * {@code requiresConfirmation}, that statement must be what its risk level already implies — because
     * the level is what the tool and the runtime act on (protocol §4), and a flag that disagrees would be
     * a second, unread authority. Stated at all, it is stated consistently, or the Spec does not read —
     * and the failure names which capability disagreed and by how much.
     */
    private static void requireConsistent(Path path, Cap cap) {
        if (cap.requiresConfirmation() == null) {
            return;
        }
        boolean implied = RiskLevel.needsConfirmation(cap.effectiveLevel());
        if (cap.requiresConfirmation() != implied) {
            throw new IllegalArgumentException("spec " + path.getFileName() + " refuses: the `" + cap.kind()
                    + "` capability declares requiresConfirmation=" + cap.requiresConfirmation()
                    + ", but its risk level `" + cap.effectiveLevel() + "` implies " + implied
                    + "; the risk level is authoritative and the flag is derived, so the two statements"
                    + " must agree");
        }
    }

    /** The naming convention shared with the tool face: `product` -> `Product`. Package-private because
     * {@link ToolsGenerator} builds setter/projection names the same way. */
    static String pascal(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String plural(String value) {
        if (value.endsWith("s")) {
            return value;
        }
        return value.endsWith("y") ? value.substring(0, value.length() - 1) + "ies" : value + "s";
    }
}
