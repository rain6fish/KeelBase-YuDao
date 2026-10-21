// SPDX-License-Identifier: Apache-2.0
//
// Unit tests for the psql-shaped rendering. Run with: node --test scripts/psql-helper.test.mjs
// These pin the two behaviours the recipe relies on: `-tA` shape (one row per line, `|` between
// columns, NULL as an empty field) and positional reading, so duplicate column names do not collapse
// (`select 21, 79` must print `21|79`, not `79|79`).
//
// psql 形状输出的单元测试。运行：node --test scripts/psql-helper.test.mjs
// 钉住 recipe 依赖的两个行为：`-tA` 形状（一行一记录、列间 `|`、NULL 为空字段）与按列序号取值 ——
// 同名列不互相覆盖（`select 21, 79` 必须打印 `21|79`，不是 `79|79`）。
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { render, preprocess, parseArgs, quoteSqlLiteral } from './psql-helper.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const seed = (name) => readFileSync(resolve(here, '..', 'sql', name), 'utf8');

const duplicateNameResult = {
  fields: [{ name: '?column?' }, { name: '?column?' }],
  rows: [[21, 79]],
};

test('tuples-only reads by ordinal: duplicate column names stay separate', () => {
  assert.equal(render(duplicateNameResult, true), '21|79');
});

test('single column output is unchanged', () => {
  const result = { fields: [{ name: '?column?' }], rows: [[1], [42]] };
  assert.equal(render(result, true), '1\n42');
});

test('header uses field names, values stay positional', () => {
  assert.equal(render(duplicateNameResult, false), '?column?|?column?\n21|79');
});

test('NULL and undefined render as empty fields', () => {
  const result = { fields: [{ name: 'a' }, { name: 'b' }, { name: 'c' }], rows: [[null, undefined, 0]] };
  assert.equal(render(result, true), '||0');
});

test('multi-statement result uses the last statement', () => {
  const result = [
    { fields: [{ name: 'x' }], rows: [[1]] },
    { fields: [{ name: 'y' }, { name: 'z' }], rows: [[2, 3]] },
  ];
  assert.equal(render(result, true), '2|3');
});

// 以下钉住 YD-41 冷启动的两份 `separate` 种子文件实际用到的 psql 构造。
// These pin the psql constructs the two YD-41 `separate` seed files actually use.

test('parseArgs collects repeated -v assignments', () => {
  const options = parseArgs(['-h', 'db', '-v', 'ON_ERROR_STOP=1', '-v', 'origin=https://x/', '-tAc', 'select 1']);
  assert.deepEqual(options.variables, { ON_ERROR_STOP: '1', origin: 'https://x/' });
  assert.equal(options.sql, 'select 1');
  assert.equal(options.tuplesOnly, true);
});

test("preprocess renders :'name' as a SQL literal and doubles embedded quotes", () => {
  assert.equal(preprocess("select :'origin'", { origin: 'https://x/a' }).sql, "select 'https://x/a'");
  assert.equal(preprocess("select :'origin'", { origin: "it's" }).sql, "select 'it''s'");
  assert.equal(quoteSqlLiteral("it's"), "'it''s'");
});

test("preprocess leaves :'name' alone inside SQL strings, identifiers and comments", () => {
  const text = [
    "select 'keep :''origin'' here', \"col:'origin'\" -- :'origin'",
    "/* :'origin' */ select 1::int;",
  ].join('\n');
  assert.equal(preprocess(text, {}).sql, text);
});

test('\\if takes the then branch when the variable is defined', () => {
  const script = ['\\if :{?origin}', "select 'taken';", '\\else', "select 'skipped';", '\\endif'].join('\n');
  const result = preprocess(script, { origin: 'x' });
  assert.equal(result.quit, null);
  assert.match(result.sql, /select 'taken';/);
  assert.doesNotMatch(result.sql, /skipped/);
});

test('\\if takes the else branch when the variable is not defined', () => {
  const script = ['\\if :{?origin}', "select 'skipped';", '\\else', "select 'fallback';", '\\endif'].join('\n');
  const result = preprocess(script, {});
  assert.equal(result.quit, null);
  assert.match(result.sql, /select 'fallback';/);
  assert.doesNotMatch(result.sql, /skipped/);
});

test('\\echo records the message and \\quit stops before any SQL runs', () => {
  const script = [
    '\\if :{?origin}',
    '\\else',
    "  \\echo 'origin is required'",
    '  \\quit 1',
    '\\endif',
    'select 1;',
  ].join('\n');
  const result = preprocess(script, {});
  assert.deepEqual(result.echoes, ['origin is required']);
  assert.deepEqual(result.quit, { code: 1 });
  assert.doesNotMatch(result.sql, /select 1/);
});

test('\\quit 0 also stops execution', () => {
  const result = preprocess('\\quit 0\nselect 1;', {});
  assert.deepEqual(result.quit, { code: 0 });
  assert.doesNotMatch(result.sql, /select 1/);
});

test('unsupported meta-commands fail loudly', () => {
  assert.throws(() => preprocess('\\set x 1\nselect 1;', {}), /Unsupported psql meta-command: \\set/);
  assert.throws(() => preprocess('\\connect other\n', {}), /Unsupported psql meta-command: \\connect/);
});

test('unsupported \\if expressions fail loudly', () => {
  assert.throws(
    () => preprocess('\\if :{?a} and :{?b}\n\\endif', { a: '1', b: '1' }),
    /Unsupported \\if condition/,
  );
});

test('unsupported variable forms and undefined variables fail loudly', () => {
  assert.throws(() => preprocess('select :origin;', { origin: 'x' }), /Unsupported psql variable form/);
  assert.throws(() => preprocess('select :"origin";', { origin: 'x' }), /Unsupported psql variable form/);
  assert.throws(() => preprocess("select :'origin';", {}), /Undefined psql variable "origin"/);
});

test('unbalanced \\if fails loudly', () => {
  assert.throws(() => preprocess('\\if true\nselect 1;', {}), /\\if without matching \\endif/);
  assert.throws(() => preprocess('\\else\n', {}), /\\else without matching \\if/);
});

for (const name of ['keelbase-local-images.sql', 'keelbase-file-storage.sql']) {
  test(`${name}: dry run with origin yields SQL and no psql meta-commands`, () => {
    const result = preprocess(seed(name), { origin: 'https://demo.example' });
    assert.equal(result.quit, null);
    assert.deepEqual(result.echoes, []);
    assert.doesNotMatch(result.sql, /^\s*\\/m);
    assert.doesNotMatch(result.sql, /:'origin'/);
    assert.match(result.sql, /'https:\/\/demo\.example'/);
  });

  test(`${name}: dry run without origin quits 1 before any SQL`, () => {
    const result = preprocess(seed(name), {});
    assert.deepEqual(result.quit, { code: 1 });
    assert.equal(result.echoes.length, 1);
    assert.match(result.echoes[0], /^origin is required: psql -v origin=/);
    assert.doesNotMatch(result.sql, /^\s*(update|UPDATE|BEGIN;)/m);
  });
}

test('the two real seed files keep their expected statements after substitution', () => {
  const images = preprocess(seed('keelbase-local-images.sql'), { origin: 'https://o.example' }).sql;
  assert.match(images, /set avatar = 'https:\/\/o\.example' \|\| '\/keelbase-avatar\.svg'/);
  assert.match(images, /update system_oauth2_client/);

  const storage = preprocess(seed('keelbase-file-storage.sql'), { origin: 'https://o.example' }).sql;
  assert.match(storage, /BEGIN;/);
  assert.ok(storage.includes(`"domain":"' || 'https://o.example' || '"}'`));
  assert.match(storage, /COMMIT;/);
});
