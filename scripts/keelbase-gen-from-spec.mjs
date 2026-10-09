#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// Turn a Business Spec into what this host needs for a governed module: the DDL that creates its table,
// and the two files of ours that give it an AI tool face and a row-scope pointcut.
//
//   node scripts/keelbase-gen-from-spec.mjs specs/products.json
//   node scripts/keelbase-gen-from-spec.mjs specs/products.json --check
//
// What this does **not** do: it does not generate the module's own CRUD. That is this host's own code
// generator, driven over its API — a separate step, because it needs a running stack. This half is
// deterministic and offline, which is what makes it checkable: `--check` compares what it would write
// against what is on disk and exits non-zero on any drift.
//
// The DDL is MySQL, not PostgreSQL: this host's own generator reads the table and column comments at
// import, so the Chinese labels ride in `COMMENT` and the naming pass disappears into the DDL.
//
// 把一份 Business Spec 变成这个宿主需要的两样东西：建表的 DDL，以及**我们自己的**两个文件——给模块一个 AI
// 工具面、一个行范围切点。
//
//   node scripts/keelbase-gen-from-spec.mjs specs/products.json
//   node scripts/keelbase-gen-from-spec.mjs specs/products.json --check
//
// **不做**的事：不生成模块自己的 CRUD。那是这个宿主自己的代码生成器干的、经它的 API 驱动——**另一趟**，因为它
// 需要一套跑着的栈。这一半是确定性的、离线的，而正因如此它才**可核**：`--check` 拿「它会写的」比对「盘上的」，
// 有漂移就非零退出。
//
// DDL 是 **MySQL** 而不是 PostgreSQL：这个宿主自己的生成器在导入时读表与列的注释，故中文标签骑在 `COMMENT` 里，
// 命名那一趟就并进了 DDL。
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..');
const GENERATED = join(REPO, 'yudao-server', 'src', 'main', 'java', 'cn', 'iocoder', 'yudao', 'server', 'keelbase', 'generated');
const SQL_DIR = join(REPO, 'sql');

// The host's BaseDO columns, plus ours. Ours are the governance pair: `owner_user_id` is what the row
// scope narrows on and what the write path stamps. The host's are what its generated DO extends.
// 宿主 BaseDO 的列，加上我们的。我们的是治理那对：`owner_user_id` 是行范围收束与写入盖章的依据。
const GOVERNANCE_COLUMNS = [
  ['owner_user_id', 'varchar(255)', 'not null', '属主用户'],
  ['org_id', 'bigint', '', '所属组织'],
  ['dept_id', 'bigint', '', '所属部门']
];
const BASE_COLUMNS = [
  ['create_time', 'timestamp', 'not null default CURRENT_TIMESTAMP', '创建时间'],
  ['update_time', 'timestamp', 'not null default CURRENT_TIMESTAMP', '更新时间'],
  ['creator', 'varchar(64)', "default ''", '创建者'],
  ['updater', 'varchar(64)', "default ''", '更新者'],
  // `smallint`, not `boolean`: the host's own PostgreSQL schema writes `deleted int2 ... default 0`, and
  // MyBatis-Plus's logic delete compares against the numeric 0/1 it is configured with. A `boolean`
  // column here fails at query time with `operator does not exist: boolean = integer` — measured, by the
  // gate, on its first run.
  // **`smallint` 而不是 `boolean`**：宿主自己的 PG schema 写的是 `deleted int2 ... default 0`，而 MyBatis-Plus 的
  // 逻辑删除是按它被配置的**数字 0/1** 去比的。这里用 `boolean` 会在查询时报
  // `operator does not exist: boolean = integer`——**门禁第一次跑**就量到了。
  ['deleted', 'smallint', 'not null default 0', '是否删除']
];

// **PostgreSQL**, because that is where this line put its datastore (D27). The spike's DDL was MySQL —
// written before that decision and runnable there — and it does not run here: `auto_increment` is not
// PostgreSQL, and neither is an inline `comment '…'`. Comments become separate `COMMENT ON` statements,
// which is what the host's generator reads when it imports the table.
// **PostgreSQL**——因为本线的数据存储放在那里（D27）。Spike 那份 DDL 是 **MySQL** 的：写在那条决定之前、
// 在那边跑得动，**在这里跑不动**：`auto_increment` 不是 PostgreSQL 的，行内 `comment '…'` 也不是。注释改写成
// 独立的 `COMMENT ON` 语句——那正是宿主生成器导入这张表时所读的东西。
const PG_TYPE = { string: 'varchar(255)', text: 'text', int: 'bigint', bool: 'boolean', date: 'date', enum: 'varchar(255)' };
const JAVA_TYPE = { string: 'String', text: 'String', int: 'Long', bool: 'Boolean', date: 'LocalDate', enum: 'String' };
const TOOL_PARAM_TYPE = { string: 'STRING', text: 'STRING', int: 'NUMBER', bool: 'BOOLEAN', date: 'STRING', enum: 'STRING' };
// How a tool turns the incoming argument into the DO's setter argument.
const CONVERT = { string: 'str', text: 'str', int: 'asLong', bool: 'asBool', date: 'str', enum: 'str' };

const pascal = (s) => s.charAt(0).toUpperCase() + s.slice(1);
const plural = (s) => (s.endsWith('s') ? s : s.endsWith('y') ? s.slice(0, -1) + 'ies' : s + 's');

function readSpec(path) {
  const spec = JSON.parse(readFileSync(path, 'utf8'));
  const obj = spec.objects && spec.objects[0];
  if (!obj || !Array.isArray(obj.fields) || obj.fields.length === 0) {
    throw new Error('spec has no object with fields');
  }
  const module = obj.module || spec.feature;
  return {
    module,
    entity: pascal(plural(obj.name)),
    label: obj.label || obj.name,
    fields: obj.fields,
    caps: spec.aiCapabilities || []
  };
}

function buildDdl({ module, label, fields }) {
  const width = (s, n) => String(s).padEnd(n);
  const line = (name, type, constraint) => `    ${width(name, 14)}${width(type, 14)}${constraint}`.trimEnd();
  const rows = [['id', 'bigint', 'not null']];
  for (const f of fields) {
    const type = PG_TYPE[f.type];
    if (!type) throw new Error(`field \`${f.name}\` has unknown type \`${f.type}\``);
    rows.push([f.name, type, f.required ? 'not null' : '']);
  }
  rows.push(...GOVERNANCE_COLUMNS, ...BASE_COLUMNS);
  const comments = [
    `comment on table ${module} is '${label}（生成）';`,
    `comment on column ${module}.id is '编号';`,
    ...fields.map((f) => `comment on column ${module}.${f.name} is '${f.label || f.name}';`),
    ...[...GOVERNANCE_COLUMNS, ...BASE_COLUMNS].map(([n, , , c]) => `comment on column ${module}.${n} is '${c}';`)
  ];
  return [
    '-- GENERATED by scripts/keelbase-gen-from-spec.mjs — do not edit by hand; re-run the adapter.',
    '-- Our own file, not the host generator\'s output: it only creates the table, which the host\'s own',
    '-- generator then imports. The Chinese labels ride in COMMENT ON, because that is where it reads them.',
    '-- PostgreSQL: this line\'s datastore (D27). Its previous MySQL form does not run here.',
    '-- 由 scripts/keelbase-gen-from-spec.mjs 生成——不要手改；改 Spec 后重跑适配器。这是我们自己的文件、',
    '-- 非宿主生成器的产出：它只建表，之后由宿主自己的生成器导入。中文标签骑在 COMMENT ON 里，因为它就是从那里读的。',
    '-- PostgreSQL：本线的数据存储（D27）。它此前的 MySQL 形态在这里跑不动。',
    // The id comes from a sequence, not from `identity`: that is the host's own convention
    // (`id int8 NOT NULL` plus a `<table>_seq`), and its generated DO carries
    // `@KeySequence("<table>_seq")`, so a table without the sequence fails on the first insert with
    // `relation "…_seq" does not exist` — measured here too.
    // id 来自**序列**、不是 `identity`：那是宿主自己的惯例（`id int8 NOT NULL` 加一个 `<table>_seq`），而它生成的
    // DO 带着 `@KeySequence("<table>_seq")`，故没有那个序列的表**第一次插入**就会报
    // `relation "…_seq" does not exist`——这里同样量到了。
    `create sequence if not exists ${module}_seq;`,
    '',
    `create table if not exists ${module} (`,
    [...rows.map((r) => line(...r)), '    primary key (id)'].join(',\n'),
    ');',
    '',
    ...comments,
    ''
  ].join('\n');
}

function buildToolsJava({ module, entity, label, fields, caps }) {
  const pkg = 'cn.iocoder.yudao.server.keelbase.generated';
  const modulePkg = `cn.iocoder.yudao.module.${module}`;
  const business = module;
  const read = caps.find((c) => c.kind === 'read');
  const write = caps.find((c) => c.kind === 'write');
  const setters = fields.map((f) => `            row.set${pascal(f.name)}(${CONVERT[f.type]}(args.get("${f.name}")));`).join('\n');
  const params = fields
    .map((f) => `                    ToolParameter.${f.required ? 'required' : 'optional'}("${f.name}", ToolParameter.${TOOL_PARAM_TYPE[f.type]}, "${f.label || f.name}")`)
    .join(',\n');
  const routeArgs = fields
    .map((f) => `            if (context.get("${f.name}") != null) {\n                args.put("${f.name}", context.get("${f.name}"));\n            }`)
    .join('\n');
  const projection = ['id', ...fields.map((f) => f.name)]
    .map((n) => `            safe.put("${n}", row.get${pascal(n)}());`)
    .join('\n');

  const readTool = read
    ? `    /** R1 — a read: no confirmation, nothing to revoke. / R1——读：无需确认、无可撤销。 */
    static final class ListTool implements AiTool {

        private final ${entity}Service service;

        ListTool(${entity}Service service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "list_generated_${module}";
        }

        @Override
        public String description() {
            return "List the ${label} held by the module Yudao's code generator produced (read-only)."
                    + " Returns only the caller's own rows.";
        }

        @Override
        public String riskLevel() {
            return "R1";
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Principal principal) {
            ${entity}PageReqVO query = new ${entity}PageReqVO();
            query.setPageSize(100);
            List<Map<String, Object>> rows = ActingCaller.as(principal.userId(),
                            () -> service.get${entity}Page(query)).getList().stream()
                    .map(ListTool::project)
                    .toList();
            return ToolResult.ok(rows);
        }

        /** The fields that leave this tool — a projection is where that decision is legible. */
        private static Map<String, Object> project(${entity}DO row) {
            Map<String, Object> safe = new LinkedHashMap<>();
${projection}
            return safe;
        }
    }

    /** The phrases this module's read answers to. / 本模块读所回答的短语。 */
    static final class ListRoute implements ModuleRoute {

        @Override
        public String toolName() {
            return "list_generated_${module}";
        }

        @Override
        public List<String> triggers() {
            return List.of("列出${label}", "生成的${label}", "${label}列表");
        }

        @Override
        public Map<String, Object> args(Map<String, Object> context) {
            return Map.of();
        }
    }
`
    : '';

  const writeTool = write
    ? `
    /** ${write.riskLevel || 'R3'} — a write: the engine holds it for a human before it runs. / ${write.riskLevel || 'R3'}——写：引擎扣下等人。 */
    static final class CreateTool implements AiTool {

        private final ${entity}Service service;

        CreateTool(${entity}Service service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "create_generated_${module}";
        }

        @Override
        public String description() {
            return "Create a ${label} through the module Yudao's code generator produced.";
        }

        @Override
        public String riskLevel() {
            return "${write.riskLevel || 'R3'}";
        }

        @Override
        public String resultType() {
            return "${business}";
        }

        @Override
        public List<ToolParameter> parameters() {
            return List.of(
${params});
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Principal principal) {
            ${entity}SaveReqVO row = new ${entity}SaveReqVO();
${setters}
            // The owner is not set here on purpose: ${entity}Governance stamps it from the caller, the
            // same way it does for the module's own REST path.
            // 属主**刻意不在这里设**：由 ${entity}Governance 从调用方盖上去，与它对模块自己的 REST 路径一样。
            Long id = ActingCaller.as(principal.userId(), () -> service.create${entity}(row));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            return ToolResult.ok(out);
        }
    }

    static final class CreateRoute implements ModuleRoute {

        @Override
        public String toolName() {
            return "create_generated_${module}";
        }

        @Override
        public List<String> triggers() {
            return List.of("新建${label}", "创建${label}", "添加${label}");
        }

        @Override
        public Map<String, Object> args(Map<String, Object> context) {
            Map<String, Object> args = new LinkedHashMap<>();
${routeArgs}
            // A placeholder for argument extraction, as the host's routes are: when nothing structured
            // arrived, the typed text names the row. / 参数抽取的占位，与宿主的那些路由一样。
            if (args.get("name") == null && context.get("message") instanceof String typed && !typed.isBlank()) {
                args.put("name", typed);
            }
            return args;
        }
    }
`
    : '';

  // Tool beans first, then route beans: the order the checked-in file has, so a regeneration is a
  // no-op rather than a reordering. / 工具 bean 在前、路由 bean 在后——与已提交的那份同序，好让重生成是**空操作**。
  const listToolBean = read
    ? `    @Bean
    AiTool listGenerated${entity}(${entity}Service service) {
        return new ListTool(service);
    }

`
    : '';
  const createToolBean = write
    ? `    @Bean
    AiTool createGenerated${entity}(${entity}Service service) {
        return new CreateTool(service);
    }

`
    : '';
  const listRouteBean = read
    ? `    @Bean
    ModuleRoute ${module}ListRoute() {
        return new ListRoute();
    }

`
    : '';
  const createRouteBean = write
    ? `    @Bean
    ModuleRoute ${module}CreateRoute() {
        return new CreateRoute();
    }

`
    : '';

  return `// SPDX-License-Identifier: Apache-2.0
//
// GENERATED for the Yudao host from specs/${module}.json — do not edit by hand; re-run the adapter.
// Our own file, not Yudao's generator output. / 由适配器从 specs/${module}.json 为芋道宿主生成——不要手改。
// 我们自己的文件，非芋道生成器的产出。
package ${pkg};

import cn.com.keelbase.runtime.authz.OwnershipGuard;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolParameter;
import cn.com.keelbase.runtime.tool.ToolResult;
import ${modulePkg}.controller.admin.${business}.vo.${entity}PageReqVO;
import ${modulePkg}.controller.admin.${business}.vo.${entity}SaveReqVO;
import ${modulePkg}.dal.dataobject.${business}.${entity}DO;
import ${modulePkg}.service.${business}.${entity}Service;
import cn.iocoder.yudao.server.keelbase.ActingCaller;
import cn.iocoder.yudao.server.keelbase.ModuleRoute;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ${module}'s AI tool face over the module Yudao's own code generator produced: a read and a write,
 * with their routes.
 *
 * <p>It calls the generated <em>service</em>, not the mapper, and it narrows nothing itself. That is the
 * point: the module's row scope lives in {@link ${entity}Governance}'s pointcuts, so a call through this
 * tool and a call through the module's REST path are held by the same advices. A tool that filtered on
 * its own would govern the tool and leave the REST path open — which is exactly what the first cut did.
 *
 * ${module} 的 AI 工具面，跑在**芋道自己的代码生成器**产出的模块上：一读一写，各配路由。
 *
 * <p>它调生成的 **service**、不调 mapper，且它**自己一点不收束**。这正是要点：模块的行范围住在
 * {@link ${entity}Governance} 的切点里，于是经本工具的调用与经模块 REST 路径的调用被**同一批通知**管住。
 * 自己过滤的工具只会治理工具、把 REST 路径敞开——那正是第一版干的事。
 */
@Configuration
public class ${entity}Tools {

${listToolBean}${createToolBean}${listRouteBean}${createRouteBean}${readTool}${writeTool}
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
${fields.some((f) => f.type === 'bool') ? `
    private static Boolean asBool(Object value) {
        return value instanceof Boolean flag ? flag : null;
    }
` : ''}}
`;
}

function buildGovernanceJava({ module, entity, label }) {
  const pkg = 'cn.iocoder.yudao.server.keelbase.generated';
  const modulePkg = `cn.iocoder.yudao.module.${module}`;
  const svc = `${modulePkg}.service.${module}.${entity}Service`;
  return `// SPDX-License-Identifier: Apache-2.0
//
// GENERATED for the Yudao host from specs/${module}.json — do not edit by hand; re-run the adapter.
// Our own file, not Yudao's generator output: it is named after nothing the generator writes, so a
// regeneration of the module's code leaves it alone.
// 由适配器从 specs/${module}.json 为芋道宿主生成——不要手改。它是**我们自己的**文件、非芋道生成器的产出：
// 不按生成器会写的东西命名，故模块代码重生成时它不受影响。
package ${pkg};

import ${modulePkg}.controller.admin.${module}.vo.${entity}PageReqVO;
import ${modulePkg}.controller.admin.${module}.vo.${entity}SaveReqVO;
import ${modulePkg}.dal.dataobject.${module}.${entity}DO;
import ${modulePkg}.dal.mysql.${module}.${entity}Mapper;
import cn.iocoder.yudao.server.keelbase.YudaoModuleScope;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * ${module}'s governance at the module's own seam: the pointcut <em>is</em> the list of what is governed
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
 * ${module} 在**模块自己的接缝**上的治理：pointcut **就是**那份被治理的清单（\`KEELBASE-AI-GUIDE.md\` §3.2），
 * 且点的是模块自己的 service、不是反射查找。
 *
 * <p>这是第一版**没有移植**的那一半。没有它时，收束住在 AI 工具里——它只管得住工具自己那一次调用，模块自己的
 * REST 路径**不受治理**。有了它，两条路径被**同四条通知**管住：因为治理发生在**模块被调用的地方**，而不是
 * 某一个调用方恰好坐着的地方。
 *
 * <p>有一件事在这里比 RuoYi **更容易**，值得写下来，因为通常方向是反的：芋道的分页请求 VO **本身就带属主列**，
 * 而生成的 mapper 也已经把它变成 \`eqIfPresent\`。于是收束一次分页 = 调用前一句 \`setOwnerUserId\`，而且**总数也一起
 * 收窄了**——没有事后过滤，也没有哪一页的 total 与它的行不一致。
 */
@Aspect
@Component
public class ${entity}Governance {

    private final YudaoModuleScope scope;
    private final ${entity}Mapper mapper;

    public ${entity}Governance(YudaoModuleScope scope, ${entity}Mapper mapper) {
        this.scope = scope;
        this.mapper = mapper;
    }

    @Around("execution(* ${svc}.get${entity}Page(..))")
    public Object narrowToCaller(ProceedingJoinPoint call) throws Throwable {
        Long caller = scope.callerToEnforce();
        if (caller != null && call.getArgs()[0] instanceof ${entity}PageReqVO query) {
            query.setOwnerUserId(String.valueOf(caller));
        }
        return call.proceed();
    }

    // The third way into the same rows: a read by id. It cannot be narrowed — there is one row and it is
    // either the caller's or it is not — so another's row is handed back as nothing, which is exactly how
    // a row that is not there answers.
    // 进入同一批行的**第三条路**：按 id 读。它收束不了——只有一行，要么是调用方的、要么不是——故别人的行
    // **什么都不交回**，而不存在的那一行正是这么答的。
    @Around("execution(* ${svc}.get${entity}(..))")
    public Object withholdOthersRow(ProceedingJoinPoint call) throws Throwable {
        Object row = call.proceed();
        Long caller = scope.callerToEnforce();
        if (caller != null && row instanceof ${entity}DO found
                && !scope.belongsToCaller(found.getOwnerUserId(), caller)) {
            return null;
        }
        return row;
    }

    // The generated save VO carries the owner column and marks it required, so a client could name
    // someone else as owner. Stamping it here is what closes that. / 生成的保存 VO 带属主列、且标为必填，
    // 故客户端可以指认别人为属主。**在这里盖章**正是堵上它的那一下。
    @Around("execution(* ${svc}.create${entity}(..))")
    public Object stampOwner(ProceedingJoinPoint call) throws Throwable {
        Long caller = scope.creatingCaller();
        if (caller != null && call.getArgs()[0] instanceof ${entity}SaveReqVO row) {
            row.setOwnerUserId(String.valueOf(caller));
        }
        return call.proceed();
    }

    @Around("execution(* ${svc}.update${entity}(..))"
            + " || execution(* ${svc}.delete${entity}(..))"
            + " || execution(* ${svc}.delete${entity}ListByIds(..))")
    public Object requireOwnership(ProceedingJoinPoint call) throws Throwable {
        Long caller = scope.callerToEnforce();
        if (caller != null) {
            for (Long id : ids(call)) {
                ${entity}DO row = id == null ? null : mapper.selectById(id);
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
        if (args[0] instanceof ${entity}SaveReqVO row) {
            return row.getId() == null ? List.of() : List.of(row.getId());
        }
        return scope.asIds(args[0]);
    }
}
`;
}

// Compare as text with line endings normalised: this repository is checked out on Windows, so what is
// on disk is CRLF while what is committed — and what this writes — is LF. Comparing raw would report a
// drift that is not one.
// 按**归一化行尾**的文本比较：本仓在 Windows 上检出，故盘上是 CRLF、而**已提交的**（以及本脚本写的）是 LF。
// 直接比会报出一个并不存在的漂移。
const normalise = (s) => s.split('\r\n').join('\n');

function main() {
  const args = process.argv.slice(2);
  const check = args.includes('--check');
  const specPath = args.find((a) => !a.startsWith('--'));
  if (!specPath) {
    console.error('usage: keelbase-gen-from-spec.mjs <spec.json> [--check]');
    process.exit(2);
  }
  const spec = readSpec(specPath);
  const outputs = [
    [join(SQL_DIR, `keelbase-${spec.module}.sql`), buildDdl(spec)],
    [join(GENERATED, `${spec.entity}Tools.java`), buildToolsJava(spec)],
    [join(GENERATED, `${spec.entity}Governance.java`), buildGovernanceJava(spec)]
  ];

  let drift = 0;
  for (const [path, content] of outputs) {
    const rel = path.slice(REPO.length + 1).split('\\').join('/');
    if (check) {
      if (!existsSync(path)) {
        console.error(`  MISSING  ${rel}`);
        drift += 1;
      } else if (normalise(readFileSync(path, 'utf8')) !== content) {
        console.error(`  DRIFT    ${rel}`);
        drift += 1;
      } else {
        console.log(`  ok       ${rel}`);
      }
    } else {
      mkdirSync(dirname(path), { recursive: true });
      writeFileSync(path, content);
      console.log(`  wrote    ${rel}`);
    }
  }
  if (check && drift > 0) {
    console.error(`\n${drift} file(s) differ from the spec — re-run without --check to regenerate.`);
    process.exit(1);
  }
  if (check) console.log('\nno drift.');
}

main();
