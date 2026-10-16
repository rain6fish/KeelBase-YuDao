// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.codegen;

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
 *
 * <p>这是读 Spec 的**唯一**入口：三种产出（DDL / 工具面 / 治理切面）都拿同一个 {@code Spec} 实例，故它们看到的是
 * 同一份字段表、同一个能力清单，不会各自解析一遍而读出不同的东西。
 *
 * <p>The slice of a {@code specs/*.json} this generator reads: one object (one table), its fields, and
 * the AI capabilities it declares. Only the first object is used — one module here is one table with a
 * read and a write, and multiple objects are out of scope. The entity name is the pluralised, Pascal-cased
 * object name, because that is the shape the host's own generator names its DO / VO / service after. This
 * is the single entry point for reading a Spec, so all three outputs share one parsed view.
 */
record Spec(String module, String entity, String label, List<Field> fields, List<Cap> caps) {

    /** One field of the object: its column name, its Spec type word, whether it is required, its label. */
    record Field(String name, String type, boolean required, String label) {
    }

    /** One declared AI capability: what it does (`read` / `write`) and, for a write, its risk level. */
    record Cap(String kind, String riskLevel) {
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
                caps.add(new Cap(cap.path("kind").asText(),
                        cap.hasNonNull("riskLevel") ? cap.get("riskLevel").asText() : null));
            }
        }
        return new Spec(module, pascal(plural(name)), label, List.copyOf(fields), List.copyOf(caps));
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
