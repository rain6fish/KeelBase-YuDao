// SPDX-License-Identifier: Apache-2.0
//
// 消息里不写标记。治理页把词条原样交给 `{{ }}` 打印，故写进消息的 `**` 与反引号会以字面字符出现在页面上 ——
// 2026-10-25 走查在决策轨迹的覆盖声明里读到 `**没有会话**`、在边界页的警示与说明里读到 `**一个**` 和反引号。
// 根因是强调被写进了消息，而消息只是数据；决定怎么显示的是渲染它的那一层。
//
// 这条测试读宿主补丁里中英两套词条（构建真正应用的就是它们），逐条走完每个叶子字符串：带强调标记或反引号的
// 就红，并报出是哪一套、哪个键。另有一条守 `@`：vue-i18n 把裸 `@` 当链接消息的语法，整页会抛 SyntaxError
// 渲染成空白，故它必须保持 `{'@'}` 的转义写法 —— 这条同时挡住「顺手把转义也删了」的改法。
//
// 只停在源码层：本仓不带 Vue 测试运行器，构建所依的 UI 检出是一次性的（`target/`），故没有 `t()` 可调。
// 但这里要证的那件事不需要运行期 —— 页面是纯文本插值，它从不解析 Markdown，所以「消息不含标记」就是
// 「页面上不出现标记」。
//
// No markup in a message. The governance pages hand these strings straight to `{{ }}`, so a `**` or a
// backtick typed into one prints as itself — the 2026-10-25 walk read `**没有会话**` in the trace
// coverage line and `**一个**` plus backticks in the boundary warning and notes. The cause is emphasis
// written into the *message*, which is only data; the template that renders it is the layer that
// decides how it looks.
//
// This test reads both message bundles out of the host patch — the ones the build applies — walks every
// leaf string, and fails on an emphasis marker or a backtick, naming the bundle and the key. A second
// test guards `@`: vue-i18n reads a bare `@` as linked-message syntax, the page throws and renders
// blank, so it must stay escaped as `{'@'}` — which also refuses a fix that drops the escape along
// with the backticks.
//
// Source-level on purpose: this repository ships no Vue test runner and builds from a disposable UI
// checkout (`target/`), so there is no `t()` to call. The invariant needs no runtime — the pages
// interpolate plain text and never interpret Markdown, so "the message carries no marker" *is*
// "the page shows no marker".
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const PATCH = 'keelbase-ui/host-patches/0002-governance-ui.patch';
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
 * 补丁给每一行都加了 `+` 前缀；只剥掉这一个 diff 标记，切片就是构建真正应用的对象。词句的真源留在补丁里 ——
 * 测试不复述它们。
 *
 * The patch adds every line with a leading `+`; strip that one diff marker so the slice is the object
 * the build actually applies. Values stay source-of-truth in the patch — this test does not restate them.
 */
function messageBundles() {
  const patch = read(PATCH);
  const marker = '+  keelbase: {';
  const bundles = [];
  for (let at = patch.indexOf(marker); at !== -1; at = patch.indexOf(marker, at + marker.length)) {
    const raw = objectAt(patch, patch.indexOf('{', at));
    const js = raw
      .split('\n')
      .map((line) => line.replace(/^\+/, ''))
      .join('\n');
    bundles.push(new Function(`return (${js})`)());
  }
  assert.equal(bundles.length, 2, 'the patch carries exactly two keelbase message bundles (English + Chinese)');
  const en = bundles.find((b) => b.common.refresh === 'Refresh');
  const zh = bundles.find((b) => b.common.refresh === '刷新');
  assert.ok(en, 'the English bundle exists');
  assert.ok(zh, 'the Chinese bundle exists');
  return { en, zh };
}

/** 每个叶子词条连同它的键路径。 / every leaf message with its key path. */
function leaves(node, prefix = '', out = []) {
  if (typeof node === 'string') {
    out.push([prefix, node]);
  } else {
    for (const [key, value] of Object.entries(node)) leaves(value, prefix ? `${prefix}.${key}` : key, out);
  }
  return out;
}

test('no message in either language carries a marker the pages would print', () => {
  const bundles = messageBundles();
  for (const [language, bundle] of Object.entries(bundles)) {
    const offending = leaves(bundle).filter(([, text]) => text.includes('**') || text.includes('`'));
    assert.deepEqual(
      offending,
      [],
      `the ${language} bundle carries Markdown that the page would print as itself — ` +
        'state the emphasis in the wording instead (the messages are data, not markup)',
    );
  }
});

test('a literal @ stays escaped, the way vue-i18n requires', () => {
  const bundles = messageBundles();
  for (const [language, bundle] of Object.entries(bundles)) {
    const offending = leaves(bundle)
      .filter(([, text]) => text.replaceAll("{'@'}", '').includes('@'))
      .map(([key, text]) => [key, text]);
    assert.deepEqual(
      offending,
      [],
      `the ${language} bundle carries a bare @ — vue-i18n reads it as linked-message syntax and the page throws; write it as {'@'}`,
    );
  }
});
