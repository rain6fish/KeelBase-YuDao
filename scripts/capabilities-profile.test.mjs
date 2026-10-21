// SPDX-License-Identifier: Apache-2.0
//
// `/app/capabilities` 的 `businessModules` 是**声明**，不是运行时探测：运行时把 `keelbase.app.*`
// 原样发给前端（`ApplicationProfileProperties` 的类注释写着「declared, not inferred」），前端据此
// 显隐导航。于是一份与部署不符的清单不是「候选预设」，而是一句会被助手转述给用户的假话。
//
// 本测试把这份声明与部署自己的两处事实对账，不连库：
//   - `deploy/demo-ecs/seed-manifest.txt` 的 `load` / `skip` —— 哪些业务种子会被灌进去；
//   - `deploy/demo-ecs/module-scope.sql` 的保留清单 —— 哪些模块根菜单会留着。
//
// 运行：`node --test scripts/capabilities-profile.test.mjs`
//
// `/app/capabilities`'s `businessModules` is a **declaration**, not a runtime probe: the runtime hands
// `keelbase.app.*` to the frontend verbatim (`ApplicationProfileProperties` says "declared, not
// inferred"), and the frontend shows or hides navigation from it. A list that disagrees with the
// deployment is therefore not a "candidate preset" — it is a falsehood an assistant repeats to a user.
//
// This test reconciles that declaration with two deployment facts, without a database:
//   - `deploy/demo-ecs/seed-manifest.txt`'s `load` / `skip` — which business seeds get applied;
//   - the keep list in `deploy/demo-ecs/module-scope.sql` — which module root menus stay on.
//
// Run: `node --test scripts/capabilities-profile.test.mjs`
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const PROFILE = join('yudao-server', 'src', 'main', 'resources', 'application-keelbase.yaml');
const MANIFEST = join('deploy', 'demo-ecs', 'seed-manifest.txt');
const SCOPE = join('deploy', 'demo-ecs', 'module-scope.sql');

const read = (rel) => readFileSync(join(ROOT, rel), 'utf8');

// 本部署的业务模块根路径（`module-scope.sql` 保留清单里的那些）。`products` 没有菜单根：它是生成
// 模块，只有表与 AI 工具，故单列，不参与根路径比较。
// The business module roots of this deployment (the ones in `module-scope.sql`'s keep list). `products`
// has no menu root — it is a generated module with a table and AI tools only — so it is listed apart
// and takes no part in the root-path comparison.
const NAV_ROOTS = ['crm', 'bpm', 'oa', 'hrm', 'fms', 'report'];
const BUSINESS_FACES_WITHOUT_NAV_ROOT = ['products'];

// 只读 `keelbase:` → `app:` 这一段，够这份文件的形状用；结构一变就抛，而不是静默读成空。
// Reads only the `keelbase:` → `app:` block, enough for this file's shape; a structural change throws
// rather than being read silently as empty.
export function parseProfile(text) {
  const lines = text.split(/\r?\n/);
  const keelbase = lines.findIndex((line) => /^keelbase:\s*$/.test(line));
  const app = lines.findIndex((line, i) => i > keelbase && /^  app:\s*$/.test(line));
  assert.ok(keelbase >= 0 && app > keelbase, `${PROFILE}: no keelbase.app block`);

  const profile = { preset: null, features: {}, businessModules: [] };
  let section = null;
  for (let i = app + 1; i < lines.length; i += 1) {
    const line = lines[i];
    if (!line.trim() || line.trim().startsWith('#')) continue;
    const indent = line.match(/^ */)[0].length;
    if (indent <= 2) break; // 离开 `app:` / left the `app:` block

    const trimmed = line.trim();
    if (indent === 4) {
      const match = trimmed.match(/^([A-Za-z0-9_-]+):\s*(.*)$/);
      assert.ok(match, `${PROFILE}: cannot read "${trimmed}"`);
      const [, key, value] = match;
      section = key === 'features' || key === 'businessModules' ? key : null;
      if (key === 'preset') profile.preset = value;
    } else if (indent === 6 && section === 'features') {
      const match = trimmed.match(/^([A-Za-z0-9_-]+):\s*(true|false)\s*$/);
      assert.ok(match, `${PROFILE}: features.${trimmed} is not a boolean`);
      profile.features[match[1]] = match[2] === 'true';
    } else if (indent === 6 && section === 'businessModules') {
      const match = trimmed.match(/^- id:\s*(\S+)\s*$/);
      assert.ok(match, `${PROFILE}: businessModules entry without an id: "${trimmed}"`);
      profile.businessModules.push({ id: match[1] });
    } else if (indent === 8 && section === 'businessModules') {
      const match = trimmed.match(/^(label|description):\s*(.*\S)\s*$/);
      if (match) profile.businessModules.at(-1)[match[1]] = match[2];
    }
  }
  return profile;
}

// 清单：`<load|separate|skip> <file> [# reason]`。
// The manifest: `<load|separate|skip> <file> [# reason]`.
export function parseManifest(text) {
  const kinds = new Map();
  for (const line of text.split(/\r?\n/)) {
    const match = line.trim().match(/^(load|separate|skip)\s+(\S+\.sql)\b/);
    if (match) kinds.set(match[2], match[1]);
  }
  return kinds;
}

// `module-scope.sql` 的保留清单：`coalesce(path, '') not in ('', '/system', …)`。
// The keep list in `module-scope.sql`: `coalesce(path, '') not in ('', '/system', …)`.
export function parseKeepList(text) {
  const match = text.match(/not in\s*\(([\s\S]*?)\)/);
  assert.ok(match, `${SCOPE}: could not find the keep list`);
  return new Set([...match[1].matchAll(/'([^']*)'/g)].map((m) => m[1]));
}

const profile = parseProfile(read(PROFILE));
const manifest = parseManifest(read(MANIFEST));
const keep = parseKeepList(read(SCOPE));
const advertised = profile.businessModules.map((module) => module.id);
const seedKind = (id) => manifest.get(`keelbase-${id}.sql`);

// 模块 id 的取值范围：已知的根路径、无根路径的业务面，以及 `features` 里出现的键。`features` 的键集
// 是开放的（可能含 push / sms 这类非模块开关），但只有同时有 `sql/keelbase-<id>.sql` 的键才可能被算成
// 业务模块，故加进来不会误报。
// The module-id universe: the known roots, the business faces without a root, and whatever keys
// `features` carries. That key set is open (push / sms are not modules), but only a key that also has
// `sql/keelbase-<id>.sql` can be counted as a business module, so adding them cannot over-report.
const BUSINESS_IDS = new Set([
  ...NAV_ROOTS,
  ...BUSINESS_FACES_WITHOUT_NAV_ROOT,
  ...Object.keys(profile.features),
]);

test('the profile has the frozen shape', () => {
  assert.ok(['full', 'small', 'lite'].includes(profile.preset), `preset is ${profile.preset}`);
  assert.ok(advertised.length > 0, 'businessModules is empty');
  assert.equal(new Set(advertised).size, advertised.length, 'businessModules has a duplicate id');
  for (const module of profile.businessModules) {
    assert.ok(module.id, 'an entry has no id');
    assert.ok(module.label, `${module.id} has no label`);
    assert.ok(module.description, `${module.id} has no description`);
  }
});

test('an advertised module is switched on in features', () => {
  for (const id of advertised) {
    assert.equal(profile.features[id], true, `${id} is advertised but features.${id} is not true`);
  }
});

test('the advertised modules are exactly the business seeds this deployment loads', () => {
  const loaded = [...manifest.keys()]
    .filter((file) => manifest.get(file) === 'load')
    .map((file) => file.replace(/^keelbase-/, '').replace(/\.sql$/, ''))
    .filter((id) => BUSINESS_IDS.has(id));
  assert.deepEqual([...advertised].sort(), loaded.sort(),
    'businessModules must equal the business seeds classified `load` in seed-manifest.txt');
});

test('no skipped module is advertised or switched on', () => {
  for (const id of NAV_ROOTS) {
    if (seedKind(id) !== 'skip') continue;
    assert.ok(!advertised.includes(id), `${id}'s seed is skipped but it is advertised`);
    assert.notEqual(profile.features[id], true, `${id}'s seed is skipped but features.${id} is true`);
  }
});

test('the module roots kept by module-scope are exactly the advertised roots', () => {
  for (const id of NAV_ROOTS) {
    assert.equal(keep.has(`/${id}`), advertised.includes(id),
      `module-scope ${keep.has(`/${id}`) ? 'keeps' : 'drops'} /${id} but businessModules `
      + `${advertised.includes(id) ? 'advertises' : 'does not advertise'} ${id}`);
  }
});
