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

    /**
     * The seam files a host line writes <b>once</b>, which no Spec produces — declared so that the
     * boundary is something the build can check rather than a claim in a document, and so that a file
     * belonging to neither list fails instead of quietly becoming a second source.
     *
     * <p>This is the answer to the boundary question `RY-38` closed with: the adapter owns the
     * <em>module-level</em> surface (the table, the tool face, the row-scope pointcut), and a host line
     * writes its <em>host-level</em> pieces by hand exactly once. Within these two namespaces that is
     * now enforced rather than described — and the enforcement is worth having because the namespaces
     * do not distinguish the two by name: `sql/keelbase-products.sql` is generated and
     * `sql/keelbase-crm.sql` is hand-written, side by side.
     *
     * 一条宿主线**只写一次**、而任何 Spec 都不产出的接缝文件——声明在此，好让这条边界成为**构建能检查**的东西，
     * 而不是文档里的一句主张；也好让「两边都不属于」的文件**报错**，而不是悄悄变成第二个源。
     *
     * <p>这是 `RY-38` 收口时留下的那个边界问题的答案：适配器拥有**模块级**面（表 · 工具面 · 行范围切点），
     * 宿主级件由宿主线**手写一次**。在这两个命名空间之内，这一点现在**是被强制**的、不是被描述的——而这条强制值得
     * 有，因为命名空间**并不靠名字区分两者**：`sql/keelbase-products.sql` 是生成的、`sql/keelbase-crm.sql` 是手写的，
     * 并排放在一起。
     */
    private static final List<String> HAND_WRITTEN = List.of(
            // YD-2: an upstream module whose schema upstream does not publish, so it was derived once.
            // YD-2：一个上游不发布其 schema 的上游模块，故它被推导了一次。
            "sql/keelbase-crm.sql",
            // ai1: enabled and compiled with no schema of its own, so its endpoints answered 500 until
            // this file existed. Upstream publishes no DDL for it either.
            // ai1：被启用、被编译，却没有自己的 schema，故在本文件存在之前它的端点一律答 500。上游同样不发布它的 DDL。
            "sql/keelbase-ai1.sql",
            // The deployment's own names, replacing the platform vendor's — a file of ours rather than
            // edits scattered through upstream's several-thousand-line, multi-dialect seed.
            // 本部署自己的名字，替换掉平台厂商的——写成我们自己的一个文件，而不是在上游那份几千行、多方言的种子里散改。
            "sql/keelbase-brand-names.sql",
            // The host writes NULL for "nobody is logged in"; MySQL coerced it, PostgreSQL refuses.
            // Relaxing the column is what keeps that row rather than dropping it.
            // 宿主对「没有登录用户」写 NULL；MySQL 会强转，PostgreSQL 拒绝。放宽该列，那一行才留得住而不是丢掉。
            "sql/keelbase-operate-log-nullable.sql",
            // Upstream's seed carries pictures hosted on the vendor's own file server. A deployment
            // that keeps them asks every visitor to fetch from a host we do not control, so the rows
            // are pointed at what this host serves.
            // 上游种子里带着挂在厂商自己文件服务器上的图片。沿用它们的部署会让每个访问者去一个我们并不
            // 控制的主机取图，故把这些行指向本机自己提供的东西。
            "sql/keelbase-local-images.sql",
            // The modules this line enables wholesale. Upstream ships a test schema per module — in a
            // mix of dialects, and not always covering the module's own data objects — so each was
            // converted (and, where the schema fell short, derived) once, by hand, into these files.
            // 本线整体启用的那些模块。上游给每个模块带一份测试 schema——方言混杂，而且未必覆盖该模块自己的
            // 数据对象——故逐份转换（schema 不够的地方则推导）成这些文件，一次性做完。
            "sql/keelbase-bpm.sql",
            "sql/keelbase-report.sql",
            "sql/keelbase-oa.sql",
            "sql/keelbase-hrm.sql",
            "sql/keelbase-fms.sql",
            "sql/keelbase-pms.sql",
            "sql/keelbase-ai.sql",
            // What MySQL has and PostgreSQL does not: some date-part function names (`MONTH(...)` is
            // built in Java by a few of the enabled modules) and one operator (`smallint = boolean`,
            // which two callers need at once on the same column). Supplied here rather than by editing
            // upstream's Java or by retyping a column.
            // MySQL 有、PostgreSQL 没有的东西：几个日期分量函数名（少数启用的模块在 Java 里拼
            // `MONTH(...)`），以及**一个算符**（`smallint = boolean`——两个调用方在同一列上同时需要它）。
            // 补在这里，而不是去改上游的 Java 或把某一列改型。
            "sql/keelbase-mysql-compat.sql",
            // A seeded menu routes to something that was never a page; its four `type = 3` children
            // are the button permissions the dialog beside it checks, so they are re-parented to the
            // page that uses them before the bogus row is switched off.
            // 一条种子菜单路由到一个**从来不是页面**的东西上；它那四个 `type = 3` 子行是旁边那个弹窗要检查的
            // 按钮权限，故先把它们改挂到真正使用它们的页面上，再把那行关掉。
            "sql/keelbase-menus-without-pages.sql",
            // The governance console's own menu rows: one directory and the three pages under it.
            // Inserts only, because this host builds its tree from `system_menu` and a .vue file nobody
            // lists is a page nobody can reach. No permission string and no role grant, so who sees them
            // stays the host's own rule rather than this file's.
            // 治理控制台自己的菜单行：一个目录与它下面那三页。**只有插入**，因为本宿主的树是从 `system_menu`
            // 建的，而一个没人登记的 .vue 就是一个没人到得了的页面。不挂权限串、也不授角色，故谁能看到它们由
            // **宿主自己的规则**决定、不由本文件决定。
            "sql/keelbase-governance-menus.sql",
            // ...and who may see them. The directory and two of the three pages go to the ordinary
            // role, because the server already scopes each of those to its caller — so handing them out
            // adds no reach. The audit chain is granted to nobody: the runtime refuses it to anyone who
            // is not an administrator, and a menu is not a permission.
            // ……以及**谁看得到它们**。那个目录与三页里的两页交给普通角色，因为服务端**本来就**把这两页按调用方
            // 限了范围 —— 于是把它们发出去**一点射程也没加**。审计链不授给任何人：运行时把它拒给任何不是管理员的
            // 人，而**菜单不是权限**。
            "sql/keelbase-governance-menu-grants.sql",
            // Where a deployment's uploads go. Upstream's seed makes an *example* store master with
            // the vendor's own domain in it, so uploads belong on a server we do not control. Takes
            // the origin as a parameter, so one file serves every host running this stack.
            // 一次部署的上传落到哪里。上游种子把一份**示例**存储设成 master、里面写着厂商自己的域名，
            // 于是上传属于一个我们并不控制的服务器。它把 origin 作参数收下，故一个文件伺候所有跑这套栈的机器。
            // The console's per-page menu rows, one file each. Each is a hand-written addition to the
            // host's own menu tree rather than a Spec's output, so each has to be declared here or the
            // boundary check calls it UNDECLARED — which is what it did for the three added before this
            // one, and the check had been red since.
            // 控制台逐页的菜单行，一份一个文件。每一份都是对**宿主自己**菜单树的手写新增、不是 Spec 的产出，
            // 故每一份都必须在此声明，否则边界检查会把它判成 UNDECLARED —— 在本次之前加的那三份正是如此，
            // 而那道检查从那时起一直是红的。
            "sql/keelbase-showcase-menu.sql",
            "sql/keelbase-tool-inventory-menu.sql",
            "sql/keelbase-trace-menu.sql",
            "sql/keelbase-operation-audit-menu.sql",
            "sql/keelbase-policy-menu.sql",
            "sql/keelbase-file-storage.sql",
            // 助手那一对：它的常驻 agent（模型链 + 系统提示词）写成**种子**、而不是手敲的三步，故
            // 「改了可评审、部署可复现」；密钥不进仓——provider 的 `api_key` 是嵌套默认值，落到本部署
            // 已经在传的那个模型 key 上。它的授权把 `ai1:session:my` 给普通角色：ai1 从来不带界面，
            // 故这条权限此前**一行都不存在**，而宿主的权限清单是从被授予的菜单行上读的。
            //
            // The assistant pair: its resident agent — the model chain and the system prompt — as a seed
            // rather than a hand-typed three-step, so a change to it is reviewable and a deployment
            // reproducible; the key stays out of the repository, because the provider's `api_key` is a
            // nested default that falls back to the model key this deployment already passes. The grant
            // hands `ai1:session:my` to the ordinary role: `ai1` ships no menus, so that permission had
            // no row at all before it, and the host reads its permission list off granted menu rows.
            "sql/keelbase-assistant-agent.sql",
            "sql/keelbase-assistant-role-grants.sql",
            // 本部署自己的名字与状态，写成文件而不是散改上游的种子：自带 AI 模块的**应用面**在菜单层
            // 关停（只关菜单，不删代码、不删表），以及「MySQL 监控」改名「数据库监控」、那一格随 Hikari 写。
            //
            // This deployment's own names and state, as files rather than edits scattered through
            // upstream's seed: the built-in AI module's application surface switched off at the menu
            // layer (menus only — no code and no table is deleted), and the monitor page renamed with
            // that cell following Hikari.
            "sql/keelbase-ai-app-surface-off.sql",
            "sql/keelbase-monitor-rename.sql",
            // 本部署的**样例业务数据**（六家客户、三位联系人、三条跟进、六个产品）。**带外灌入**：它不属于
            // 「表 + 菜单」的部署引导，而是给某一套开发/演示环境看的样例。固定 id（9xxx 段）+ 缺了才插，故重跑
            // 不翻倍 —— 正是这一点让它值得留在仓里，而不是某一次手敲的 INSERT。
            //
            // This deployment's **sample business data** — six customers, three contacts, three follow-up
            // records and six products. Applied **out of band**: it is not part of the tables-and-menus
            // bootstrap every deployment runs, but sample data for one development/demo environment. Fixed
            // ids in the 9xxx band and inserted only when absent, so rerunning it does not double the rows —
            // which is exactly why it belongs in the repository rather than in a one-off hand-typed INSERT.
            "sql/keelbase-demo-data.sql");

    private SpecCodegen() {
    }

    public static void main(String[] args) throws IOException {
        boolean check = false;
        boolean boundary = false;
        String specArg = null;
        for (String arg : args) {
            if ("--check".equals(arg)) {
                check = true;
            } else if ("--check-boundary".equals(arg)) {
                boundary = true;
            } else if (!arg.startsWith("--")) {
                specArg = arg;
            }
        }
        Path root = repoRoot();
        if (boundary) {
            checkBoundary(root);
            return;
        }
        if (specArg == null) {
            System.err.println("usage: gen-from-spec.sh <spec.json> [--check] | --check-boundary");
            System.exit(2);
        }
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

    /**
     * Ask whether the boundary is where this file says it is: every file the adapter owns is either
     * something a Spec produces, or something declared hand-written, and nothing else.
     *
     * <p>It fails in <b>both</b> directions. A generated file that is missing means the Spec stopped
     * being the source; a file belonging to neither list means a second source appeared — which is the
     * failure this whole check exists to make loud.
     *
     * 问这条边界是不是在它自己说的地方：适配器拥有的每个文件，要么是某个 Spec 产出的、要么是声明为手写的，
     * **没有第三种**。它**两个方向都报错**：生成的缺了 ⇒ Spec 不再是源；两边都不属于 ⇒ 出现了**第二个源**——
     * 那正是这道检查存在的理由。
     */
    private static void checkBoundary(Path root) throws IOException {
        String genDir = String.join("/", GENERATED_DIR);
        List<String> generated = new ArrayList<>();
        try (var specs = Files.list(root.resolve("specs"))) {
            for (Path spec : specs.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                Spec s = readSpec(spec);
                generated.add("sql/keelbase-" + s.module() + ".sql");
                generated.add(genDir + "/" + s.entity() + "Tools.java");
                generated.add(genDir + "/" + s.entity() + "Governance.java");
            }
        }
        List<String> actual = new ArrayList<>();
        try (var sql = Files.list(root.resolve("sql"))) {
            sql.filter(p -> p.getFileName().toString().startsWith("keelbase-")
                            && p.getFileName().toString().endsWith(".sql"))
                    .sorted().forEach(p -> actual.add("sql/" + p.getFileName()));
        }
        try (var gen = Files.list(root.resolve(genDir))) {
            gen.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted().forEach(p -> actual.add(genDir + "/" + p.getFileName()));
        }

        int bad = 0;
        for (String rel : actual) {
            if (generated.contains(rel)) {
                System.out.println("  generated     " + rel);
            } else if (HAND_WRITTEN.contains(rel)) {
                System.out.println("  hand-written  " + rel);
            } else {
                System.err.println("  UNDECLARED    " + rel
                        + " - neither a Spec's output nor declared hand-written");
                bad += 1;
            }
        }
        for (String rel : generated) {
            if (!actual.contains(rel)) {
                System.err.println("  MISSING       " + rel);
                bad += 1;
            }
        }
        for (String rel : HAND_WRITTEN) {
            if (!actual.contains(rel)) {
                System.err.println("  MISSING       " + rel + " (declared hand-written)");
                bad += 1;
            }
        }
        System.out.println();
        if (bad == 0) {
            System.out.println("boundary holds: " + generated.size() + " generated, "
                    + HAND_WRITTEN.size() + " hand-written, nothing else.");
        } else {
            System.err.println(bad + " file(s) outside the boundary - a Spec is not the source,"
                    + " or a second one appeared.");
            System.exit(1);
        }
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
import cn.com.keelbase.runtime.identity.ActingCaller;
import cn.com.keelbase.runtime.pipeline.ModuleRoute;
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
import cn.iocoder.yudao.server.keelbase.GovernedModule;
import cn.iocoder.yudao.server.keelbase.YudaoModuleScope;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * %s's governance at the module's own seam: the pointcut <em>is</em> the list of what is governed
 * (keelbase-docs/KEELBASE-MODULE-ONBOARDING.md §2.1), and it names the module's own service, not a
 * reflective lookup.
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
 * %s 在**模块自己的接缝**上的治理：pointcut **就是**那份被治理的清单
 * （`keelbase-docs/KEELBASE-MODULE-ONBOARDING.zh-CN.md` §2.1），且点的是模块自己的 service、不是反射查找。
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
public class %sGovernance implements GovernedModule {

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

    /**
     * Declares this module governed, so the set this host holds is countable rather than only readable
     * in a pointcut (`YD-13`). The name is the segment the check reads out of the pointcuts above.
     *
     * 声明本模块受治理，使本宿主持有的那个集合**数得出来**，而不是只能在上面的 pointcut 里读（`YD-13`）。
     * 这个名字就是那道检查从上面那些 pointcut 里读出来的那一段。
     */
    @Override
    public String module() {
        return "%s";
    }

    @Override
    public String ownerField() {
        return "owner_user_id";
    }
}
""".formatted(module, module, pkg,
                modulePkg, module, entity, modulePkg, module, entity, modulePkg, module, entity, modulePkg, module, entity,
                module, module, entity, entity, entity, entity,
                svc, entity, entity,
                svc, entity, entity,
                svc, entity, entity,
                svc, entity, svc, entity, svc, entity,
                entity, entity, module);
    }
}
