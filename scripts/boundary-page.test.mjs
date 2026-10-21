// SPDX-License-Identifier: Apache-2.0
//
// 边界页必须把「它扣不住的」作为独立区块画出来，中英都要有，且内容取自门禁记录 —— 不是模板里手打的一句话。
// 运行：
//
//     node --test scripts/boundary-page.test.mjs
//
// 这些断言刻意停在源码层：本仓不带 Vue 测试运行器，而它构建所依的 UI 检出是一次性的（`target/`），
// 故挂载 SFC 的测试只会在本机绿、下次取树就没了。这里钉的是让这个块成立的三份文件之间的契约 ——
// 组件模板、宿主补丁里的中英词条、页面所读的门禁记录。删掉块、漏掉一条词条、或改掉记录里的 id，都会红。
//
// The boundary page must draw the "not held by it" block as its own section, in both languages, from
// the gate's record — not from a sentence typed into the template. Run with:
//
//     node --test scripts/boundary-page.test.mjs
//
// These assertions are deliberately source-level: this repository ships no Vue test runner, and the
// UI checkout it builds from is disposable (`target/`), so a test that mounted the SFC would pass here
// and vanish on the next fetch. What is pinned instead is the contract between the three files that
// make the block real — the component template, the bilingual messages in the host patch, and the
// gate record the page reads. Removing the block, dropping a message, or changing the record's id
// turns this red.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const PAGE = 'keelbase-ui/host-overlay/src/views/keelbase/governance/BoundaryPage.vue';
const PATCH = 'keelbase-ui/host-patches/0002-governance-ui.patch';
const GATE = 'scripts/check-ungoverned-tool-beans.mjs';

const read = (rel) => readFileSync(join(ROOT, rel), 'utf8');

/** 取从 `start` 起的那个对象字面量，跳过引号里的花括号。 / take the object literal at `start`, ignoring braces inside quoted strings. */
function objectAt(text, start) {
  let depth = 0;
  let quote = '';
  for (let i = start; i < text.length; i += 1) {
    const ch = text[i];
    if (quote) {
      if (ch === '\\') {
        i += 1;
      } else if (ch === quote) {
        quote = '';
      }
      continue;
    }
    if (ch === "'" || ch === '"') {
      quote = ch;
    } else if (ch === '{') {
      depth += 1;
    } else if (ch === '}') {
      depth -= 1;
      if (depth === 0) return text.slice(start, i + 1);
    }
  }
  throw new Error('unbalanced object literal');
}

/**
 * 补丁给每一行都加了 `+` 前缀；只剥掉这一个 diff 标记，切片就是构建真正应用的对象。词句的真源留在这里 ——
 * 测试不复述它们。
 *
 * The patch adds every line with a leading `+`; strip that one diff marker so the slice is the object
 * the build actually applies. Values stay source-of-truth here — this test does not restate them.
 */
function boundaryMessages() {
  const patch = read(PATCH);
  const marker = '+    boundary: {';
  const blocks = [];
  for (let at = patch.indexOf(marker); at !== -1; at = patch.indexOf(marker, at + marker.length)) {
    const raw = objectAt(patch, patch.indexOf('{', at));
    const js = raw
      .split('\n')
      .map((line) => line.replace(/^\+/, ''))
      .join('\n');
    blocks.push(new Function(`return (${js})`)());
  }
  assert.equal(blocks.length, 2, 'the patch carries exactly two boundary message blocks (English + Chinese)');
  const en = blocks.find((b) => b.title === 'Governance boundary');
  const zh = blocks.find((b) => b.title === '治理边界');
  assert.ok(en, 'the English boundary block exists');
  assert.ok(zh, 'the Chinese boundary block exists');
  return { en, zh };
}

function templateOf(vue) {
  const start = vue.indexOf('<template>');
  const end = vue.lastIndexOf('</template>', vue.indexOf('\n<script'));
  assert.ok(start >= 0 && end > start, 'the page has a template before its script');
  return vue.slice(start, end);
}

test('the page draws the not-held block as its own section, fed by stated.notHeld', () => {
  const tpl = templateOf(read(PAGE));

  for (const key of ['notHeldTitle', 'notHeldWhat', 'notHeldWhy', 'notHeldOneBean']) {
    assert.match(
      tpl,
      new RegExp(`t\\('keelbase\\.boundary\\.${key}'\\)`),
      `the template renders keelbase.boundary.${key}`,
    );
  }

  // 告诫由记录自己的标志位决定，路径名由它的 id 推出 —— 「是哪条路」没有一个字是页面手打的。
  // The warning is conditional on the record's own flag, and the path name is derived from its id —
  // nothing about "which path" is typed into the page.
  assert.match(tpl, /boundary\.stated\.notHeld\.oneBeanAway/, 'the warning follows stated.notHeld.oneBeanAway');
  assert.match(
    tpl,
    /t\(`keelbase\.boundary\.path\.\$\{boundary\.stated\.notHeld\.id\}`\)/,
    'the path name comes from stated.notHeld.id through the path.* message family',
  );
});

test('the block reads the same in both languages (snapshot)', () => {
  const { en, zh } = boundaryMessages();

  const rendered = (m) => ({
    title: m.notHeldTitle,
    what: m.notHeldWhat,
    upstreamPath: m.path['upstream-resolver'],
    why: m.notHeldWhy,
    oneBeanAway: m.notHeldOneBean,
  });

  assert.deepEqual(rendered(zh), {
    title: '它扣不住的',
    what: '上游自己那条路：chat 取角色的 toolIds、经 Spring AI 自己的解析器把 callback 交给模型 —— 本线治理看不见它。',
    upstreamPath: '上游模型→工具解析器',
    why: '今天无害：本构建里唯一的 `ToolCallback` bean 是**上游自己的示例**，故解析器找不到我们的东西。',
    oneBeanAway:
      '只差**一个**我们自己的 `{\'@\'}Bean` 交出 `ToolCallback` —— 模型就够得到它：不经引擎、不经身份、不经确认、不留审计行。',
  });

  assert.deepEqual(rendered(en), {
    title: 'Not held by it',
    what: "Upstream's own path: chat resolves a role's tool ids through Spring AI's own resolver and hands the callbacks to the model. This line's governance does not see it.",
    upstreamPath: 'Upstream model→tool resolver',
    why: 'Harmless today: the only ToolCallback bean in this build is upstream’s own sample, so the resolver has nothing of ours to find.',
    oneBeanAway:
      "One {'@'}Bean of ours handing out a ToolCallback is all it takes: the model would reach it with no engine, no identity, no confirmation and no audit row.",
  });
});

test('both languages carry the same boundary keys, so neither can render a blank block', () => {
  const { en, zh } = boundaryMessages();
  const shape = (o) =>
    Object.fromEntries(
      Object.entries(o).map(([k, v]) => [k, v && typeof v === 'object' && !Array.isArray(v) ? shape(v) : typeof v]),
    );
  assert.deepEqual(shape(zh), shape(en));
});

test('the gate record feeds the block: upstream-resolver, one bean away', () => {
  const gate = read(GATE);
  const start = gate.indexOf('notHeld: {');
  assert.ok(start >= 0, 'the gate record has a notHeld entry');
  const notHeld = gate.slice(start, gate.indexOf('}', start));
  assert.match(notHeld, /id:\s*'upstream-resolver'/, 'notHeld names the upstream resolver path');
  assert.match(notHeld, /oneBeanAway:\s*true/, 'notHeld says one bean is enough');

  // 同一条路在 `stated.paths` 里是「未纳入治理 + 只差一个 bean」—— 表与块说的是同一条事实，不是两条。
  // The same path appears in `stated.paths` as not governed with the one-bean gap — the table and the
  // block describe one fact, not two.
  assert.match(gate, /id:\s*'upstream-resolver',\s*posture:\s*'not-governed'/, 'the paths row is not-governed');
  assert.match(gate, /id:\s*'upstream-resolver',[\s\S]*?gap:\s*'one-bean-away'/, 'the paths row carries the one-bean gap');
});
