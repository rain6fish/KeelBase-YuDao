#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// Drive this host's own code generator over its API: take a table that
// `keelbase-gen-from-spec.mjs` created and produce the module's CRUD from it.
//
//   node scripts/run-host-generator.mjs --module products
//
// The two halves of P1 are deliberately split. The spec adapter is offline and deterministic; this half
// needs a **running stack**, because this host generates code from a live table by querying it. Neither
// half generates the other's output: the adapter writes only our own files (the DDL, the tool face, the
// row-scope pointcuts), and this writes only the host's (DO, mapper, service, controller, VOs).
//
// What it does, in order:
//   1. apply `sql/keelbase-<module>.sql`        — the table must exist before the generator can import it
//   2. ensure an `infra_data_source_config` row  — created **through the API**, because the password is
//      stored encrypted and a hand-written row fails at read time (measured)
//   3. POST /codegen/create-list                 — import the table; returns its id
//   4. name it (business_name / class_name)      — without this the generated file names come out empty
//   5. GET  /codegen/table/list + /codegen/preview — read every file the generator produced
//   6. write the Java and mapper XML into the app tree; archive the rest
//
// 把这台宿主**自己的**代码生成器经它的 API 驱动起来：拿 `keelbase-gen-from-spec.mjs` 建好的表，产出模块的 CRUD。
//
//   node scripts/run-host-generator.mjs --module products
//
// P1 的两半是**刻意分开**的。Spec 适配器离线、确定性；这一半需要**一套跑着的栈**，因为这个宿主是**对着活表查出来**
// 再生成代码的。两半谁也不生成对方的产出：适配器只写**我们自己的**文件（DDL、工具面、行范围切点），这一半只写**宿主的**
// （DO、mapper、service、controller、VO）。
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..');

const arg = (name, fallback) => {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? process.argv[i + 1] : fallback;
};
const MODULE = arg('module', 'products');
const HOST = arg('host', 'http://127.0.0.1:48080');
const USER = arg('user', 'admin');
const PASS = arg('password', 'admin123');
const TENANT = arg('tenant', '1');
const PG = arg('pg-container', 'keelbase-yudao-pg');
const DB = arg('pg-db', 'ruoyi-vue-pro');
const DS_URL = arg('ds-url', 'jdbc:postgresql://127.0.0.1:54329/ruoyi-vue-pro');
const DS_USER = arg('ds-user', 'root');
const DS_PASS = arg('ds-password', 'password');

const APP_JAVA = join(REPO, 'yudao-server', 'src', 'main', 'java');
const APP_RES = join(REPO, 'yudao-server', 'src', 'main', 'resources');
const OTHER = join(REPO, '.generated-other'); // host UI / api.ts / sql — kept, not compiled

const psql = (sql) =>
  execFileSync('docker', ['exec', '-i', '-e', 'PGPASSWORD=password', PG, 'psql', '-U', 'root', '-d', DB, '-v', 'ON_ERROR_STOP=1', '-tAc', sql], {
    encoding: 'utf8'
  }).trim();

async function api(path, { method = 'GET', token, body } = {}) {
  const res = await fetch(`${HOST}/admin-api${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      'tenant-id': TENANT,
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    ...(body ? { body: JSON.stringify(body) } : {})
  });
  const json = await res.json().catch(() => ({}));
  if (json.code !== 0) {
    throw new Error(`${method} ${path} -> code ${json.code}: ${json.msg}`);
  }
  return json.data;
}

console.log(`== 1) 建表：sql/keelbase-${MODULE}.sql ==`);
const ddl = join(REPO, 'sql', `keelbase-${MODULE}.sql`);
if (!existsSync(ddl)) throw new Error(`missing ${ddl} — run keelbase-gen-from-spec.mjs first`);
execFileSync('docker', ['exec', '-i', '-e', 'PGPASSWORD=password', PG, 'psql', '-U', 'root', '-d', DB, '-v', 'ON_ERROR_STOP=1'], {
  input: readFileSync(ddl),
  stdio: ['pipe', 'inherit', 'inherit']
});
console.log(`   ${psql(`select 'table ' || to_regclass('public.${MODULE}')`)}`);

console.log('== 2) 数据源配置（密码加密存储 ⇒ 必须走 API 建）==');
const token = (await api('/system/auth/login', { method: 'POST', body: { username: USER, password: PASS } })).accessToken;
let dsId = psql(`select id from infra_data_source_config where deleted = 0 order by id limit 1`);
if (dsId) {
  console.log(`   复用已有配置 id=${dsId}`);
} else {
  dsId = await api('/infra/data-source-config/create', {
    method: 'POST',
    token,
    body: { name: 'keelbase-yudao', url: DS_URL, username: DS_USER, password: DS_PASS }
  });
  console.log(`   新建配置 id=${dsId}`);
}

console.log('== 3) 导入表 ==');
const tableName = psql(`select table_name from information_schema.tables where table_schema='public' and table_name='${MODULE}'`);
const existing = psql(`select id from infra_codegen_table where table_name = '${MODULE}' and deleted = 0 limit 1`);
let tableId;
if (existing) {
  tableId = existing;
  console.log(`   已导入过，复用 tableId=${tableId}`);
} else {
  const ids = await api('/infra/codegen/create-list', { method: 'POST', token, body: { dataSourceConfigId: Number(dsId), tableNames: [tableName] } });
  tableId = ids[0];
  console.log(`   导入 tableId=${tableId}`);
}

console.log('== 4) 命名（否则生成的文件名是空的）==');
const entity = MODULE.charAt(0).toUpperCase() + MODULE.slice(1);
psql(
  `update infra_codegen_table set business_name='${MODULE}', class_name='${entity}', table_comment='${MODULE}（生成）' where id = ${tableId}`
);
console.log(`   business_name=${MODULE} class_name=${entity}`);

console.log('== 5) 生成 ==');
const files = await api(`/infra/codegen/preview?tableId=${tableId}`, { token });
console.log(`   ${files.length} 个文件`);

console.log('== 6) 落盘 ==');
let java = 0;
let rest = 0;
for (const f of files) {
  const p = f.filePath.split('\\').join('/');
  // `…_手动操作.java` is a **paste snippet**, not a compilation unit: it has `TODO 补充编号` where a number
  // goes and its class name does not match its file name. Writing it into the tree breaks the build, which
  // is how this was found. The real error-code class is named after the module and checked in already;
  // for a new module the adapter must supply it, and this says so rather than pretending it is generated.
  // `…_手动操作.java` 是**粘贴片段**、不是编译单元：该填编号的地方写着 `TODO 补充编号`，且类名与文件名不符。
  // 把它写进树里会**让构建断掉**——这条就是这么被发现的。真正的错误码类以模块命名、已在仓里；新模块要由适配器补上，
  // 这里**说出来**，而不是假装它是生成出来的。
  if (/_手动操作\.java$/.test(p)) {
    console.log(`   skipped   ${p}  （手工粘贴片段；错误码类须另补）`);
    continue;
  }
  // A regex *literal* does not interpolate — `${MODULE}` there is literal text and matches nothing.
  // 正则**字面量**不会插值——写进去的 `${MODULE}` 就是那几个字符，什么也匹配不到。
  const m = p.match(new RegExp(`^yudao-module-${MODULE}/src/main/(java|resources)/(.*)$`));
  let dest;
  if (m) {
    dest = join(m[1] === 'java' ? APP_JAVA : APP_RES, m[2]);
    java += 1;
  } else {
    dest = join(OTHER, p);
    rest += 1;
  }
  mkdirSync(dirname(dest), { recursive: true });
  writeFileSync(dest, f.code);
  console.log(`   ${m ? 'compiled' : 'archived'}  ${dest.slice(REPO.length + 1)}`);
}
console.log(`\n${java} 个进编译树，${rest} 个归档到 .generated-other/`);
console.log('⚠ 生成物没有被编译：跑一次构建再起服务。');
