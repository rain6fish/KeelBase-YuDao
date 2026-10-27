// SPDX-License-Identifier: Apache-2.0
//
// 「待确认」卡要写出**这次写入要用的业务事实**。2026-10-25 的 M7 读者在这一步只能说「入参不在这里显示」，
// 于是「要建的到底是谁」只能凭工具名去猜。运行时**早已**在 `arguments` 里供出这些事实，缺的是页面把它们画出来。
// 运行：
//
//     node --test scripts/assistant-panel.test.mjs
//
// 与 `boundary-page.test.mjs` 同一条理由停在源码层：本仓不带 Vue 测试运行器，构建所依的 UI 检出是一次性的。
//
// The confirmation card must draw **the business facts this write will use**. The 2026-10-25 M7 reader
// could only say "the arguments are not shown here", so "who is about to be created" was a guess from the
// tool's name. The runtime already serves those facts in `arguments`; the page was the missing half. Run:
//
//     node --test scripts/assistant-panel.test.mjs
//
// Source-level for the same reason as `boundary-page.test.mjs`: this repository ships no Vue test runner
// and the UI checkout it builds from is disposable.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const PAGE = 'keelbase-ui/host-overlay/src/views/keelbase/assistant/AssistantPanel.vue'
const PATCH = 'keelbase-ui/host-patches/0002-governance-ui.patch'

const page = readFileSync(join(ROOT, PAGE), 'utf8')
const template = page.slice(page.indexOf('<template>'), page.indexOf('<script'))
const script = page.slice(page.indexOf('<script'), page.lastIndexOf('</script>'))

/** 入参名要给读者语言的词；控制台不认识的键原样显示，故这张表只需覆盖本部署会出现的那些。 */
const KNOWN_ARGS = [
  'name', 'id', 'customerId', 'content', 'note', 'mobile', 'level', 'source', 'remark', 'requestedAt',
]

function objectAt(text, start) {
  let depth = 0
  let quote = ''
  for (let i = start; i < text.length; i += 1) {
    const ch = text[i]
    if (quote) {
      if (ch === '\\') i += 1
      else if (ch === quote) quote = ''
      continue
    }
    if (ch === "'" || ch === '"') quote = ch
    else if (ch === '{') depth += 1
    else if (ch === '}') {
      depth -= 1
      if (depth === 0) return text.slice(start, i + 1)
    }
  }
  throw new Error('unbalanced object literal')
}

function assistantCardMessages() {
  const patch = readFileSync(join(ROOT, PATCH), 'utf8')
  const marker = '+    assistantCard: {'
  const blocks = []
  for (let at = patch.indexOf(marker); at !== -1; at = patch.indexOf(marker, at + marker.length)) {
    const raw = objectAt(patch, patch.indexOf('{', at))
    const js = raw
      .split('\n')
      .map((line) => line.replace(/^\+/, ''))
      .join('\n')
    blocks.push(new Function(`return (${js})`)())
  }
  assert.equal(blocks.length, 2, 'the patch carries exactly two assistantCard message blocks (English + Chinese)')
  const en = blocks.find((b) => b.pending === 'Waiting for you')
  const zh = blocks.find((b) => b.pending === '待确认')
  assert.ok(en && zh, 'the English and Chinese assistantCard blocks exist')
  return { en, zh }
}

test('F-1: the card draws the arguments through the reader-language helpers', () => {
  assert.match(template, /t\('keelbase\.assistantCard\.argsTitle'\)/, 'the card no longer titles the arguments')
  assert.match(template, /argEntries\(c\)/, 'the card no longer lists the arguments')
  assert.match(template, /argLabel\(a\.key\)/, 'an argument key no longer goes through argLabel')
  assert.doesNotMatch(
    template,
    /入参不在这里显示/,
    'the old note is back: it tells the reader the facts are withheld when the runtime serves them',
  )
  assert.match(script, /const i18nKey = `keelbase\.assistantCard\.arg\.\$\{key\}`/, 'argLabel no longer keys off the argument name')
  assert.match(script, /te\(i18nKey\) \? t\(i18nKey\) : key/, 'argLabel no longer falls back to the raw key')
})

test('F-1: both languages name every argument this deployment sends', () => {
  const { en, zh } = assistantCardMessages()
  for (const [lang, block] of [['en', en], ['zh', zh]]) {
    assert.ok(block.argsTitle && block.noArgs, `${lang} is missing the arguments copy`)
    for (const key of KNOWN_ARGS) {
      assert.ok(block.arg[key], `${lang} has no name for the argument \`${key}\``)
    }
  }
  assert.deepEqual(Object.keys(zh.arg).sort(), Object.keys(en.arg).sort(), 'the two languages disagree on the argument list')
  // 卡片的四个按钮同样两种语言都有 —— 这张卡不应半翻译。
  for (const key of ['pending', 'decline', 'approve', 'impact']) {
    assert.ok(en[key] && zh[key], `the card's \`${key}\` is missing in one language`)
  }
})
