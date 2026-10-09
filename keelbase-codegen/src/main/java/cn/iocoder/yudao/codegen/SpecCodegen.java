// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turn a Business Spec into what this host needs for a governed module: the DDL that creates its
 * table, and the two files of ours that give it an AI tool face and a row-scope pointcut.
 *
 * <pre>
 *   bash scripts/gen-from-spec.sh specs/products.json
 *   bash scripts/gen-from-spec.sh specs/products.json --check
 * </pre>
 *
 * <p>What this does <b>not</b> do: it does not generate the module's own CRUD. That is this host's own
 * code generator, driven over its API — a separate step, because it needs a running stack. This half is
 * deterministic and offline, which is what makes it checkable: {@code --check} compares what it would
 * write against what is on disk and exits non-zero on any drift.
 *
 * <p>It used to be a Node script ({@code scripts/keelbase-gen-from-spec.mjs}, inherited from the RuoYi
 * line). It moved here because <b>what it emits is Java</b>, and a Java host line whose generator is
 * JavaScript is a seam that has to be explained every time someone reads it. The port is held to the
 * same judgement the previous version was: it has to reproduce the committed artifacts, and the
 * artifacts it produces now say so in their provenance line.
 *
 * 把一份 Business Spec 变成这个宿主需要的两样东西：建表的 DDL，以及**我们自己的**两个文件——AI 工具面与行范围切点。
 *
 * <pre>
 *   bash scripts/gen-from-spec.sh specs/products.json
 *   bash scripts/gen-from-spec.sh specs/products.json --check
 * </pre>
 *
 * <p><b>不做</b>的事：不生成模块自己的 CRUD。那是这个宿主自己的代码生成器干的、经它的 API 驱动——**另一趟**，因为它
 * 需要一套跑着的栈。这一半是确定性的、离线的，而正因如此它才**可核**：{@code --check} 拿「它会写的」比对「盘上的」，
 * 有漂移就非零退出。
 *
 * <p>它原本是一个 Node 脚本（{@code scripts/keelbase-gen-from-spec.mjs}，从 RuoYi 线继承）。搬到这里，是因为
 * **它产出的东西就是 Java**；而一条「Java 宿主线配 JS 生成器」的缝，每换一个人读都要解释一遍。这次移植被钉在它上一版
 * 那条**同一条判据**上：必须能复现已提交的产物——而它现在产出的东西，**出处行**里写着这一点。
 */
public final class SpecCodegen {

    /** Where the two seam files go. Names the module the host generates, not one we own. */
    private static final List<String> GENERATED_DIR = List.of(
            "yudao-server", "src", "main", "java", "cn", "iocoder", "yudao", "server", "keelbase", "generated");

    /** The host's BaseDO columns, plus ours. Ours are the governance pair: `owner_user_id` is what the
     * row scope narrows on and what the write path stamps. The host's are what its generated DO extends. */
    private static final List<String[]> GOVERNANCE_COLUMNS = List.of(
            new String[]{"owner_user_id", "varchar(255)", "not null", "属主用户"},
            new String[]{"org_id", "bigint", "", "所属组织"},
            new String[]{"dept_id", "bigint", "", "所属部门"});

    private static final List<String[]> BASE_COLUMNS = List.of(
            new String[]{"create_time", "timestamp", "not null default CURRENT_TIMESTAMP", "创建时间"},
            new String[]{"update_time", "timestamp", "not null default CURRENT_TIMESTAMP", "更新时间"},
            new String[]{"creator", "varchar(64)", "default ''", "创建者"},
            new String[]{"updater", "varchar(64)", "default ''", "更新者"},
            // `smallint`, not `boolean`: the host's own PostgreSQL schema writes `deleted int2 ... default 0`,
            // and MyBatis-Plus's logic delete compares against the numeric 0/1 it is configured with. A
            // `boolean` column here fails at query time with `operator does not exist: boolean = integer`.
            // **`smallint` 而不是 `boolean`**：宿主自己的 PG schema 写的是 `deleted int2 ... default 0`，而
            // MyBatis-Plus 的逻辑删除按它配置的**数字 0/1** 去比。用 `boolean` 会在查询时报
            // `operator does not exist: boolean = integer`——门禁第一次跑就量到了。
            new String[]{"deleted", "smallint", "not null default 0", "是否删除"});

    // **PostgreSQL**, because that is where this line put its datastore (D27). The spike's DDL was MySQL —
    // written before that decision and runnable there — and it does not run here: `auto_increment` is not
    // PostgreSQL, and neither is an inline `comment '…'`. Comments become separate `COMMENT ON` statements,
    // which is what the host's generator reads when it imports the table.
    // **PostgreSQL**——因为本线的数据存储放在那里（D27）。Spike 那份 DDL 是 MySQL 的：写在那条决定之前、
    // 在那边跑得动，**在这里跑不动**：`auto_increment` 不是 PostgreSQL 的，行内 `comment '…'` 也不是。
    // 注释改写成独立的 `COMMENT ON` 语句——那正是宿主生成器导入这张表时所读的东西。
    private static final String PG_STRING = "varchar(255)";
    private static final String PG_TEXT = "text";
    private static final String PG_INT = "bigint";
    private static final String PG_BOOL = "boolean";
    private static final String PG_DATE = "date";

    /** The line that says where a generated file came from. It names the generator, so a reader of the
     * artifact can find the thing that wrote it. / 生成物的出处行。它点名生成器，好让读产物的人找得到写它的东西。 */
    private static final String GENERATED_BY = "keelbase-codegen";

    private SpecCodegen() {
    }

    public static void main(String[] args) throws IOException {
        boolean check = false;
        String specArg = null;
        for (String arg : args) {
            if ("--check".equals(arg)) {
                check = true;
            } else if (!arg.startsWith("--")) {
                specArg = arg;
            }
        }
        if (specArg == null) {
            System.err.println("usage: gen-from-spec.sh <spec.json> [--check]");
            System.exit(2);
        }
        Path root = repoRoot();
        Spec spec = readSpec(Path.of(specArg));

        List<Output> outputs = List.of(
                new Output(root.resolve("sql").resolve("keelbase-" + spec.module() + ".sql"), buildDdl(spec)),
                new Output(root.resolve(String.join("/", GENERATED_DIR)).resolve(spec.entity() + "Tools.java"),
                        buildToolsJava(spec)),
                new Output(root.resolve(String.join("/", GENERATED_DIR)).resolve(spec.entity() + "Governance.java"),
                        buildGovernanceJava(spec)));

        int drift = 0;
        for (Output output : outputs) {
            String rel = root.relativize(output.path()).toString().replace('\\', '/');
            if (check) {
                if (!Files.exists(output.path())) {
                    System.err.println("  MISSING  " + rel);
                    drift += 1;
                } else if (!normalise(Files.readString(output.path(), StandardCharsets.UTF_8)).equals(output.content())) {
                    System.err.println("  DRIFT    " + rel);
                    drift += 1;
                } else {
                    System.out.println("  ok       " + rel);
                }
            } else {
                Files.createDirectories(output.path().getParent());
                Files.writeString(output.path(), output.content(), StandardCharsets.UTF_8);
                System.out.println("  wrote    " + rel);
            }
        }
        if (check && drift > 0) {
            System.err.println("\n" + drift + " file(s) differ from the spec — re-run without --check to regenerate.");
            System.exit(1);
        }
        if (check) {
            System.out.println("\nno drift.");
        }
    }

    /**
     * The repository root, which is where the run has to happen: the generator writes into paths it does
     * not own, so it refuses rather than guessing.
     *
     * 仓库根——运行必须在它那里：生成器要写进它并不拥有的路径，故它**拒绝**而不是猜。
     */
    private static Path repoRoot() {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (!Files.isDirectory(here.resolve("sql")) || !Files.isDirectory(here.resolve("yudao-server"))) {
            System.err.println("run this from the repository root (sql/ and yudao-server/ not found under " + here + ")");
            System.exit(2);
        }
        return here;
    }

    /** Compare as text with line endings normalised: this repository is checked out on Windows, so what
     * is on disk is CRLF while what is committed — and what this writes — is LF. */
    private static String normalise(String text) {
        return text.replace("\r\n", "\n");
    }

    private record Output(Path path, String content) {
    }

    private record Field(String name, String type, boolean required, String label) {
    }

    private record Cap(String kind, String riskLevel) {
    }

    private record Spec(String module, String entity, String label, List<Field> fields, List<Cap> caps) {
    }

    private static Spec readSpec(Path path) throws IOException {
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

    private static String pascal(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String plural(String value) {
        if (value.endsWith("s")) {
            return value;
        }
        return value.endsWith("y") ? value.substring(0, value.length() - 1) + "ies" : value + "s";
    }

    private static String pgType(String type) {
        return switch (type) {
            case "string" -> PG_STRING;
            case "text" -> PG_TEXT;
            case "int" -> PG_INT;
            case "bool" -> PG_BOOL;
            case "date" -> PG_DATE;
            case "enum" -> PG_STRING;
            default -> throw new IllegalArgumentException("field has unknown type `" + type + "`");
        };
    }

    private static String javaType(String type) {
        return switch (type) {
            case "int" -> "Long";
            case "bool" -> "Boolean";
            case "date" -> "LocalDate";
            default -> "String";
        };
    }

    private static String toolParamType(String type) {
        return switch (type) {
            case "int" -> "NUMBER";
            case "bool" -> "BOOLEAN";
            default -> "STRING";
        };
    }

    /** How a tool turns the incoming argument into the DO's setter argument. */
    private static String convert(String type) {
        return switch (type) {
            case "int" -> "asLong";
            case "bool" -> "asBool";
            default -> "str";
        };
    }

    private static String buildDdl(Spec spec) {
        StringBuilder rows = new StringBuilder();
        rows.append(ddlLine("id", "bigint", "not null"));
        for (Field field : spec.fields()) {
            rows.append(",\n").append(ddlLine(field.name(), pgType(field.type()), field.required() ? "not null" : ""));
        }
        for (String[] column : GOVERNANCE_COLUMNS) {
            rows.append(",\n").append(ddlLine(column[0], column[1], column[2]));
        }
        for (String[] column : BASE_COLUMNS) {
            rows.append(",\n").append(ddlLine(column[0], column[1], column[2]));
        }

        StringBuilder comments = new StringBuilder();
        comments.append("comment on table ").append(spec.module()).append(" is '").append(spec.label()).append("（生成）';");
        comments.append("\n").append("comment on column ").append(spec.module()).append(".id is '编号';");
        for (Field field : spec.fields()) {
            comments.append("\n").append("comment on column ").append(spec.module()).append(".").append(field.name())
                    .append(" is '").append(field.label()).append("';");
        }
        for (String[] column : concat(GOVERNANCE_COLUMNS, BASE_COLUMNS)) {
            comments.append("\n").append("comment on column ").append(spec.module()).append(".").append(column[0])
                    .append(" is '").append(column[3]).append("';");
        }

        return ("""
-- GENERATED by %s — do not edit by hand; re-run the adapter.
-- Our own file, not the host generator's output: it only creates the table, which the host's own
-- generator then imports. The Chinese labels ride in COMMENT ON, because that is where it reads them.
-- PostgreSQL: this line's datastore (D27). Its previous MySQL form does not run here.
-- 由 %s 生成——不要手改；改 Spec 后重跑适配器。这是我们自己的文件、
-- 非宿主生成器的产出：它只建表，之后由宿主自己的生成器导入。中文标签骑在 COMMENT ON 里，因为它就是从那里读的。
-- PostgreSQL：本线的数据存储（D27）。它此前的 MySQL 形态在这里跑不动。
create sequence if not exists %s_seq;

create table if not exists %s (
%s,
    primary key (id)
);

%s
""").formatted(GENERATED_BY, GENERATED_BY, spec.module(), spec.module(), rows, comments);
    }

    /** The host's own convention: `id int8 NOT NULL` plus a `<table>_seq`, whose absence fails on the
     * first insert with `relation "…_seq" does not exist`. / 宿主自己的惯例。 */
    private static String ddlLine(String name, String type, String constraint) {
        return ("    %-14s%-14s%s".formatted(name, type, constraint)).stripTrailing();
    }

    private static List<String[]> concat(List<String[]> first, List<String[]> second) {
        List<String[]> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static String buildToolsJava(Spec spec) {
        String module = spec.module();
        String entity = spec.entity();
        String label = spec.label();
        String pkg = "cn.iocoder.yudao.server.keelbase.generated";
        String modulePkg = "cn.iocoder.yudao.module." + module;
        Cap read = spec.caps().stream().filter(cap -> "read".equals(cap.kind())).findFirst().orElse(null);
        Cap write = spec.caps().stream().filter(cap -> "write".equals(cap.kind())).findFirst().orElse(null);
        String writeRisk = write != null && write.riskLevel() != null ? write.riskLevel() : "R3";

        StringBuilder setters = new StringBuilder();
        for (Field field : spec.fields()) {
            setters.append("            row.set").append(pascal(field.name())).append("(")
                    .append(convert(field.type())).append("(args.get(\"").append(field.name()).append("\")));\n");
        }
        StringBuilder params = new StringBuilder();
        for (int i = 0; i < spec.fields().size(); i++) {
            Field field = spec.fields().get(i);
            params.append(i == 0 ? "" : ",\n")
                    .append("                    ToolParameter.").append(field.required() ? "required" : "optional")
                    .append("(\"").append(field.name()).append("\", ToolParameter.")
                    .append(toolParamType(field.type())).append(", \"").append(field.label()).append("\")");
        }
        StringBuilder routeArgs = new StringBuilder();
        for (Field field : spec.fields()) {
            routeArgs.append("            if (context.get(\"").append(field.name()).append("\") != null) {\n")
                    .append("                args.put(\"").append(field.name()).append("\", context.get(\"")
                    .append(field.name()).append("\"));\n            }\n");
        }
        StringBuilder projection = new StringBuilder();
        List<String> projected = new ArrayList<>();
        projected.add("id");
        spec.fields().forEach(field -> projected.add(field.name()));
        for (int i = 0; i < projected.size(); i++) {
            projection.append(i == 0 ? "" : "\n").append("            safe.put(\"").append(projected.get(i))
                    .append("\", row.get").append(pascal(projected.get(i))).append("());");
        }

        String readTool = read == null ? "" : """
    /** R1 — a read: no confirmation, nothing to revoke. / R1——读：无需确认、无可撤销。 */
    static final class ListTool implements AiTool {

        private final %sService service;

        ListTool(%sService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "list_generated_%s";
        }

        @Override
        public String description() {
            return "List the %s held by the module Yudao's code generator produced (read-only)."
                    + " Returns only the caller's own rows.";
        }

        @Override
        public String riskLevel() {
            return "R1";
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Principal principal) {
            %sPageReqVO query = new %sPageReqVO();
            query.setPageSize(100);
            List<Map<String, Object>> rows = ActingCaller.as(principal.userId(),
                            () -> service.get%sPage(query)).getList().stream()
                    .map(ListTool::project)
                    .toList();
            return ToolResult.ok(rows);
        }

        /** The fields that leave this tool — a projection is where that decision is legible. */
        private static Map<String, Object> project(%sDO row) {
            Map<String, Object> safe = new LinkedHashMap<>();
%s
            return safe;
        }
    }

    /** The phrases this module's read answers to. / 本模块读所回答的短语。 */
    static final class ListRoute implements ModuleRoute {

        @Override
        public String toolName() {
            return "list_generated_%s";
        }

        @Override
        public List<String> triggers() {
            return List.of("列出%s", "生成的%s", "%s列表");
        }

        @Override
        public Map<String, Object> args(Map<String, Object> context) {
            return Map.of();
        }
    }
""".formatted(entity, entity, module, label, entity, entity, entity, entity, projection, module, label, label, label);

        String writeTool = write == null ? "" : """

    /** %s — a write: the engine holds it for a human before it runs. / %s——写：引擎扣下等人。 */
    static final class CreateTool implements AiTool {

        private final %sService service;

        CreateTool(%sService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "create_generated_%s";
        }

        @Override
        public String description() {
            return "Create a %s through the module Yudao's code generator produced.";
        }

        @Override
        public String riskLevel() {
            return "%s";
        }

        @Override
        public String resultType() {
            return "%s";
        }

        @Override
        public List<ToolParameter> parameters() {
            return List.of(
%s);
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Principal principal) {
            %sSaveReqVO row = new %sSaveReqVO();
%s            // The owner is not set here on purpose: %sGovernance stamps it from the caller, the
            // same way it does for the module's own REST path.
            // 属主**刻意不在这里设**：由 %sGovernance 从调用方盖上去，与它对模块自己的 REST 路径一样。
            Long id = ActingCaller.as(principal.userId(), () -> service.create%s(row));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            return ToolResult.ok(out);
        }
    }

    static final class CreateRoute implements ModuleRoute {

        @Override
        public String toolName() {
            return "create_generated_%s";
        }

        @Override
        public List<String> triggers() {
            return List.of("新建%s", "创建%s", "添加%s");
        }

        @Override
        public Map<String, Object> args(Map<String, Object> context) {
            Map<String, Object> args = new LinkedHashMap<>();
%s            // A placeholder for argument extraction, as the host's routes are: when nothing structured
            // arrived, the typed text names the row. / 参数抽取的占位，与宿主的那些路由一样。
            if (args.get("name") == null && context.get("message") instanceof String typed && !typed.isBlank()) {
                args.put("name", typed);
            }
            return args;
        }
    }
""".formatted(writeRisk, writeRisk, entity, entity, module, label, writeRisk, module, params, entity, entity,
                setters, entity, entity, entity, module, label, label, label, routeArgs);

        // Tool beans first, then route beans: the order the checked-in file has, so a regeneration is a
        // no-op rather than a reordering. / 工具 bean 在前、路由 bean 在后——与已提交的那份同序。
        String listToolBean = read == null ? "" : """
    @Bean
    AiTool listGenerated%s(%sService service) {
        return new ListTool(service);
    }

""".formatted(entity, entity);
        String createToolBean = write == null ? "" : """
    @Bean
    AiTool createGenerated%s(%sService service) {
        return new CreateTool(service);
    }

""".formatted(entity, entity);
        String listRouteBean = read == null ? "" : """
    @Bean
    ModuleRoute %sListRoute() {
        return new ListRoute();
    }

""".formatted(module);
        String createRouteBean = write == null ? "" : """
    @Bean
    ModuleRoute %sCreateRoute() {
        return new CreateRoute();
    }

""".formatted(module);

        String asBool = spec.fields().stream().anyMatch(field -> "bool".equals(field.type())) ? """

    private static Boolean asBool(Object value) {
        return value instanceof Boolean flag ? flag : null;
    }
""" : "";

        return """
// SPDX-License-Identifier: Apache-2.0
//
// GENERATED for the Yudao host from specs/%s.json — do not edit by hand; re-run the adapter.
// Our own file, not Yudao's generator output. / 由适配器从 specs/%s.json 为芋道宿主生成——不要手改。
// 我们自己的文件，非芋道生成器的产出。
package %s;

import cn.com.keelbase.runtime.authz.OwnershipGuard;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolParameter;
import cn.com.keelbase.runtime.tool.ToolResult;
import %s.controller.admin.%s.vo.%sPageReqVO;
import %s.controller.admin.%s.vo.%sSaveReqVO;
import %s.dal.dataobject.%s.%sDO;
import %s.service.%s.%sService;
import cn.iocoder.yudao.server.keelbase.ActingCaller;
import cn.iocoder.yudao.server.keelbase.ModuleRoute;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * %s's AI tool face over the module Yudao's own code generator produced: a read and a write,
 * with their routes.
 *
 * <p>It calls the generated <em>service</em>, not the mapper, and it narrows nothing itself. That is the
 * point: the module's row scope lives in {@link %sGovernance}'s pointcuts, so a call through this
 * tool and a call through the module's REST path are held by the same advices. A tool that filtered on
 * its own would govern the tool and leave the REST path open — which is exactly what the first cut did.
 *
 * %s 的 AI 工具面，跑在**芋道自己的代码生成器**产出的模块上：一读一写，各配路由。
 *
 * <p>它调生成的 **service**、不调 mapper，且它**自己一点不收束**。这正是要点：模块的行范围住在
 * {@link %sGovernance} 的切点里，于是经本工具的调用与经模块 REST 路径的调用被**同一批通知**管住。
 * 自己过滤的工具只会治理工具、把 REST 路径敞开——那正是第一版干的事。
 */
@Configuration
public class %sTools {

%s%s%s%s%s%s
    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.valueOf(text.trim());
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
        return null;
    }
%s}
""".formatted(module, module, pkg,
                modulePkg, module, entity, modulePkg, module, entity, modulePkg, module, entity, modulePkg, module, entity,
                module, entity, module, entity, entity,
                listToolBean, createToolBean, listRouteBean, createRouteBean, readTool, writeTool, asBool);
    }

    private static String buildGovernanceJava(Spec spec) {
        String module = spec.module();
        String entity = spec.entity();
        String label = spec.label();
        String pkg = "cn.iocoder.yudao.server.keelbase.generated";
        String modulePkg = "cn.iocoder.yudao.module." + module;
        String svc = modulePkg + ".service." + module + "." + entity + "Service";

        return """
// SPDX-License-Identifier: Apache-2.0
//
// GENERATED for the Yudao host from specs/%s.json — do not edit by hand; re-run the adapter.
// Our own file, not Yudao's generator output: it is named after nothing the generator writes, so a
// regeneration of the module's code leaves it alone.
// 由适配器从 specs/%s.json 为芋道宿主生成——不要手改。它是**我们自己的**文件、非芋道生成器的产出：
// 不按生成器会写的东西命名，故模块代码重生成时它不受影响。
package %s;

import %s.controller.admin.%s.vo.%sPageReqVO;
import %s.controller.admin.%s.vo.%sSaveReqVO;
import %s.dal.dataobject.%s.%sDO;
import %s.dal.mysql.%s.%sMapper;
import cn.iocoder.yudao.server.keelbase.YudaoModuleScope;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * %s's governance at the module's own seam: the pointcut <em>is</em> the list of what is governed
 * (KEELBASE-AI-GUIDE.md §3.2), and it names the module's own service, not a reflective lookup.
 *
 * <p>This is the half the first cut did not port. Without it the narrowing lived inside the AI tool,
 * which governs the tool's own call and nothing else — the module's own REST path stayed ungoverned.
 * With it, both paths are held by the same four advices, because the module is governed where the
 * module is called rather than where one caller happens to sit.
 *
 * <p>One thing is <em>easier</em> here than on RuoYi, and it is worth saying because the reverse is the
 * usual direction: Yudao's page request VO already carries the owner column, and the generated mapper
 * already turns it into an {@code eqIfPresent}. So narrowing a page is one {@code setOwnerUserId} before
 * the call, and the total is narrowed too — no post-filtering, and no page whose total disagrees with
 * its rows.
 *
 * %s 在**模块自己的接缝**上的治理：pointcut **就是**那份被治理的清单（`KEELBASE-AI-GUIDE.md` §3.2），
 * 且点的是模块自己的 service、不是反射查找。
 *
 * <p>这是第一版**没有移植**的那一半。没有它时，收束住在 AI 工具里——它只管得住工具自己那一次调用，模块自己的
 * REST 路径**不受治理**。有了它，两条路径被**同四条通知**管住：因为治理发生在**模块被调用的地方**，而不是
 * 某一个调用方恰好坐着的地方。
 *
 * <p>有一件事在这里比 RuoYi **更容易**，值得写下来，因为通常方向是反的：芋道的分页请求 VO **本身就带属主列**，
 * 而生成的 mapper 也已经把它变成 `eqIfPresent`。于是收束一次分页 = 调用前一句 `setOwnerUserId`，而且**总数也一起
 * 收窄了**——没有事后过滤，也没有哪一页的 total 与它的行不一致。
 */
@Aspect
@Component
public class %sGovernance {

    private final YudaoModuleScope scope;
    private final %sMapper mapper;

    public %sGovernance(YudaoModuleScope scope, %sMapper mapper) {
        this.scope = scope;
        this.mapper = mapper;
    }

    @Around("execution(* %s.get%sPage(..))")
    public Object narrowToCaller(ProceedingJoinPoint call) throws Throwable {
        Long caller = scope.callerToEnforce();
        if (caller != null && call.getArgs()[0] instanceof %sPageReqVO query) {
            query.setOwnerUserId(String.valueOf(caller));
        }
        return call.proceed();
    }

    // The third way into the same rows: a read by id. It cannot be narrowed — there is one row and it is
    // either the caller's or it is not — so another's row is handed back as nothing, which is exactly how
    // a row that is not there answers.
    // 进入同一批行的**第三条路**：按 id 读。它收束不了——只有一行，要么是调用方的、要么不是——故别人的行
    // **什么都不交回**，而不存在的那一行正是这么答的。
    @Around("execution(* %s.get%s(..))")
    public Object withholdOthersRow(ProceedingJoinPoint call) throws Throwable {
        Object row = call.proceed();
        Long caller = scope.callerToEnforce();
        if (caller != null && row instanceof %sDO found
                && !scope.belongsToCaller(found.getOwnerUserId(), caller)) {
            return null;
        }
        return row;
    }

    // The generated save VO carries the owner column and marks it required, so a client could name
    // someone else as owner. Stamping it here is what closes that. / 生成的保存 VO 带属主列、且标为必填，
    // 故客户端可以指认别人为属主。**在这里盖章**正是堵上它的那一下。
    @Around("execution(* %s.create%s(..))")
    public Object stampOwner(ProceedingJoinPoint call) throws Throwable {
        Long caller = scope.creatingCaller();
        if (caller != null && call.getArgs()[0] instanceof %sSaveReqVO row) {
            row.setOwnerUserId(String.valueOf(caller));
        }
        return call.proceed();
    }

    @Around("execution(* %s.update%s(..))"
            + " || execution(* %s.delete%s(..))"
            + " || execution(* %s.delete%sListByIds(..))")
    public Object requireOwnership(ProceedingJoinPoint call) throws Throwable {
        Long caller = scope.callerToEnforce();
        if (caller != null) {
            for (Long id : ids(call)) {
                %sDO row = id == null ? null : mapper.selectById(id);
                scope.requireOwned(row == null ? null : row.getOwnerUserId(), caller);
            }
        }
        return call.proceed();
    }

    private List<Long> ids(ProceedingJoinPoint call) {
        Object[] args = call.getArgs();
        if (args.length != 1 || args[0] == null) {
            return List.of();
        }
        if (args[0] instanceof %sSaveReqVO row) {
            return row.getId() == null ? List.of() : List.of(row.getId());
        }
        return scope.asIds(args[0]);
    }
}
""".formatted(module, module, pkg,
                modulePkg, module, entity, modulePkg, module, entity, modulePkg, module, entity, modulePkg, module, entity,
                module, module, entity, entity, entity, entity,
                svc, entity, entity,
                svc, entity, entity,
                svc, entity, entity,
                svc, entity, svc, entity, svc, entity,
                entity, entity);
    }
}
