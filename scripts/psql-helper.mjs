#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// A psql-shaped helper for machines that have no psql client. The gates call it through
// `PSQL_HELPER_SCRIPT` with the same arguments they would pass to psql:
//
//   node scripts/psql-helper.mjs -h 127.0.0.1 -p 55449 -U root -d ruoyi-vue-pro -tAc 'select 1'
//
// Beyond the gate shape (`-h -p -U -d -tAc <sql>`) it also accepts `-f <file>`, so the same helper can
// create a database and load a schema dump in recipe §3.1 when neither createdb nor psql exists:
//
//   node scripts/psql-helper.mjs -h 127.0.0.1 -p 55449 -U root -d postgres -tAc 'create database "x"'
//   node scripts/psql-helper.mjs -h 127.0.0.1 -p 55449 -U root -d x -f sql/postgresql/ruoyi-vue-pro.sql
//
// It needs the `pg` package, which this repository does not depend on. Resolution order:
//   1. PSQL_HELPER_PG_MODULE        — path to the `pg` package directory itself;
//   2. PSQL_HELPER_PG_NODE_MODULES  — path to a `node_modules` directory that contains `pg`;
//   3. require('pg')                — normal resolution, honouring NODE_PATH;
//   4. <repo>/../.yd39-infra/node_modules/pg and <repo>/.yd39-infra/node_modules/pg — the layout the
//      recipe documents for the embedded stack;
//   5. D:/Rain6fish/.yd39-infra/node_modules/pg — the same embedded stack at its absolute host path, so
//      a clone anywhere on that host (not only beside .yd39-infra) still finds it with no configuration.
// Nothing is installed; if none of these finds `pg` the helper fails loudly and names the variables.
//
// Output follows `-tA` (tuples only, unaligned): one row per line, columns joined by `|`, NULL as an
// empty field — the same shape the gates' `psql()` consumes. Values are read by column **ordinal**
// (node-postgres `rowMode: 'array'`), so two selected columns with the same name stay two fields:
// `select 21, 79` prints `21|79`, not `79|79`. Without `-t` a header line is printed first; this is
// deliberately minimal and not a byte-for-byte psql emulation.
//
// 给没有 psql 客户端的机器用的 psql 形状助手。门禁经 `PSQL_HELPER_SCRIPT` 调用它，实参与 psql 相同：
//
//   node scripts/psql-helper.mjs -h 127.0.0.1 -p 55449 -U root -d ruoyi-vue-pro -tAc 'select 1'
//
// 除门禁形状（`-h -p -U -d -tAc <sql>`）外，它也认 `-f <file>`，故 recipe §3.1 在没有 createdb / psql
// 时可以用同一个助手建库并灌 schema：
//
//   node scripts/psql-helper.mjs -h 127.0.0.1 -p 55449 -U root -d postgres -tAc 'create database "x"'
//   node scripts/psql-helper.mjs -h 127.0.0.1 -p 55449 -U root -d x -f sql/postgresql/ruoyi-vue-pro.sql
//
// 它需要 `pg` 包，而本仓并不依赖它。解析顺序：
//   1. PSQL_HELPER_PG_MODULE        —— `pg` 包目录本身的路径；
//   2. PSQL_HELPER_PG_NODE_MODULES  —— 含 `pg` 的 `node_modules` 目录路径；
//   3. require('pg')                —— 常规解析，认 NODE_PATH；
//   4. <repo>/../.yd39-infra/node_modules/pg 与 <repo>/.yd39-infra/node_modules/pg —— recipe 写明的
//      embedded 栈布局；
//   5. D:/Rain6fish/.yd39-infra/node_modules/pg —— 同一个 embedded 栈的绝对路径；克隆在该机的任何位置
//      （不必与 .yd39-infra 相邻）都零配置找得到。
// 不安装任何东西；五处都找不到 `pg` 时助手大声失败，并点名该设哪个变量。
//
// 输出照 `-tA`（只出元组、不对齐）：一行一记录，列以 `|` 连接，NULL 为空字段 —— 正是门禁 `psql()` 消费的
// 形状。取值按列的**序号**（node-postgres `rowMode: 'array'`），故两个同名列仍是两个字段：
// `select 21, 79` 打印 `21|79`，不再是 `79|79`。不带 `-t` 时先打一行表头；这是刻意的最小实现，不是逐字节复刻 psql。
import { createRequire } from 'node:module';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const require = createRequire(import.meta.url);
const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..');

function loadPg() {
  const tried = [];
  const attempt = (label, fn) => {
    tried.push(label);
    try {
      return fn();
    } catch {
      return null;
    }
  };

  const explicit = process.env.PSQL_HELPER_PG_MODULE;
  if (explicit) {
    const mod = attempt(`PSQL_HELPER_PG_MODULE=${explicit}`, () => require(resolve(explicit)));
    if (mod) return mod;
    throw new Error(
      `PSQL_HELPER_PG_MODULE points at "${explicit}" but it could not be loaded as a module.\n` +
        `Set it to the "pg" package directory (the one containing package.json), e.g. ` +
        `D:/Rain6fish/.yd39-infra/node_modules/pg`,
    );
  }

  const nodeModules = process.env.PSQL_HELPER_PG_NODE_MODULES;
  if (nodeModules) {
    const mod = attempt(
      `PSQL_HELPER_PG_NODE_MODULES=${nodeModules}`,
      () => require(resolve(nodeModules, 'pg')),
    );
    if (mod) return mod;
    throw new Error(
      `PSQL_HELPER_PG_NODE_MODULES points at "${nodeModules}" but "${resolve(nodeModules, 'pg')}" ` +
        `could not be loaded.\nSet it to a node_modules directory that contains pg, e.g. ` +
        `D:/Rain6fish/.yd39-infra/node_modules`,
    );
  }

  const fromPath = attempt("require('pg')", () => require('pg'));
  if (fromPath) return fromPath;

  for (const candidate of [
    resolve(repoRoot, '..', '.yd39-infra', 'node_modules', 'pg'),
    resolve(repoRoot, '.yd39-infra', 'node_modules', 'pg'),
    // Absolute host path of the embedded stack the recipe documents: a clone can live anywhere (for
    // example D:/Rain6fish/.recipe-final-cold/KeelBase-YuDao) and still find pg without configuration.
    // 绝对路径的 embedded 栈：克隆可以在任意位置（如 D:/Rain6fish/.recipe-final-cold/...），仍零配置。
    'D:/Rain6fish/.yd39-infra/node_modules/pg',
  ]) {
    const mod = attempt(candidate, () => require(candidate));
    if (mod) return mod;
  }

  throw new Error(
    'Cannot find the "pg" package. Nothing was installed.\n' +
      `Tried: ${tried.join(' · ')}\n` +
      'Set PSQL_HELPER_PG_NODE_MODULES to a node_modules directory containing pg ' +
      '(the recipe uses D:/Rain6fish/.yd39-infra/node_modules), or NODE_PATH to the same place.',
  );
}

function usage(message) {
  const text = [
    message,
    'Usage: psql-helper.mjs [-h host] [-p port] [-U user] [-d db] [-t] [-A] [-c sql | -f file]',
    'Only the psql options listed above are supported; -v and -w are accepted and ignored.',
  ].join('\n');
  throw new Error(text);
}

function parseArgs(argv) {
  const options = {
    host: process.env.PGHOST || '127.0.0.1',
    port: Number(process.env.PGPORT || 5432),
    user: process.env.PGUSER || process.env.USER || 'postgres',
    database: process.env.PGDATABASE || process.env.PG_DB || 'postgres',
    tuplesOnly: false,
    sql: '',
    file: '',
  };
  const positional = [];

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith('-') || arg === '-') {
      positional.push(arg);
      continue;
    }
    if (arg === '--') {
      positional.push(...argv.slice(i + 1));
      break;
    }
    if (arg.startsWith('--')) {
      const body = arg.slice(2);
      const eq = body.indexOf('=');
      const name = eq === -1 ? body : body.slice(0, eq);
      const value = eq === -1 ? undefined : body.slice(eq + 1);
      if (name === 'host') options.host = value ?? argv[++i];
      else if (name === 'port') options.port = Number(value ?? argv[++i]);
      else if (name === 'username') options.user = value ?? argv[++i];
      else if (name === 'dbname') options.database = value ?? argv[++i];
      else if (name === 'file') options.file = value ?? argv[++i];
      else if (name === 'command') options.sql = value ?? argv[++i];
      else if (name === 'tuples-only') options.tuplesOnly = true;
      else if (name === 'no-align') options.noAlign = true;
      else if (name === 'set') { if (value === undefined) i += 1; } // ON_ERROR_STOP: already fail loud
      else if (name === 'no-password') { /* prompting is out of scope */ }
      else usage(`Unsupported option: ${arg}`);
      continue;
    }

    // Short options can be bundled the way psql accepts them (`-tAc`, `-Atc`, ...). Options that take
    // a value consume the rest of the bundle or the next argument.
    for (let j = 1; j < arg.length; j += 1) {
      const flag = arg[j];
      if (flag === 'h') { options.host = arg.slice(j + 1) || argv[++i]; break; }
      if (flag === 'p') { options.port = Number(arg.slice(j + 1) || argv[++i]); break; }
      if (flag === 'U') { options.user = arg.slice(j + 1) || argv[++i]; break; }
      if (flag === 'd') { options.database = arg.slice(j + 1) || argv[++i]; break; }
      if (flag === 'f') { options.file = arg.slice(j + 1) || argv[++i]; break; }
      if (flag === 'c') { options.sql = arg.slice(j + 1) || argv[++i]; break; }
      if (flag === 'v') { if (!arg.slice(j + 1)) i += 1; break; } // -v ON_ERROR_STOP=1: errors already fail loud
      if (flag === 'w' || flag === 'W') continue; // password prompting is out of scope
      if (flag === 't') { options.tuplesOnly = true; continue; }
      if (flag === 'A') { options.noAlign = true; continue; }
      usage(`Unsupported option: -${flag}`);
    }
  }

  if (positional.length) options.sql = positional.join(' ');
  if (!options.sql && !options.file) usage('No command given: pass -c <sql>, -f <file>, or an SQL argument.');
  if (options.sql && options.file) usage('Pass either -c <sql> or -f <file>, not both.');
  return options;
}

export function render(result, tuplesOnly) {
  const lines = [];
  const results = Array.isArray(result) ? result : [result];
  const last = results[results.length - 1];
  if (!last || !last.rows) return '';
  if (!tuplesOnly && last.fields && last.fields.length) {
    lines.push(last.fields.map((field) => field.name).join('|'));
  }
  for (const row of last.rows) {
    // Rows arrive as arrays (rowMode: 'array') so duplicate column names keep their own position.
    // 行以数组返回（rowMode: 'array'），同名列各占各位，不会互相覆盖。
    const values = Array.isArray(row) ? row : last.fields.map((field) => row[field.name]);
    lines.push(values.map((value) => (value === null || value === undefined ? '' : String(value))).join('|'));
  }
  return lines.join('\n');
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  let sql = options.sql;
  if (options.file) {
    const file = resolve(options.file);
    if (!existsSync(file)) throw new Error(`File not found: ${options.file} (resolved ${file})`);
    sql = readFileSync(file, 'utf8');
  }

  const { Client } = loadPg();
  const client = new Client({
    host: options.host,
    port: options.port,
    user: options.user,
    database: options.database,
    password: process.env.PGPASSWORD,
  });
  await client.connect();
  try {
    // rowMode: 'array' makes each row positional, which is what fixes `select 21, 79` (same column
    // name) and matches psql's unaligned output. / rowMode: 'array' 让行按位置返回，修同名列，贴合 psql。
    const result = await client.query({ text: sql, rowMode: 'array' });
    const output = render(result, options.tuplesOnly);
    if (output) process.stdout.write(`${output}\n`);
  } finally {
    await client.end();
  }
}

// Run only when invoked as a script; the test imports `render` from here. 仅直接执行时跑 main；测试会 import render。
const invokedDirectly =
  process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
if (invokedDirectly) {
  main().catch((error) => {
    process.stderr.write(`${error && error.message ? error.message : error}\n`);
    process.exitCode = 1;
  });
}
