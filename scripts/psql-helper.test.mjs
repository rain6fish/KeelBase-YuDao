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
import { render } from './psql-helper.mjs';

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
