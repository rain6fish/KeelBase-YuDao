#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// 中文在前、英文在后 —— 本仓的块顺序与其它仓相反。
//
// CI 的最终判决器：驱动应用级符合性套件，然后判断**四率到底是不是读数**、以及**跳过的栏是不是恰好
// 只有那两栏**。它不重新定义任何率：2.1 来自各场景套件自己的收束断言，2.2 / 2.3 / 2.4 来自
// `measure-governance-rates.mjs` 按 `RY-19` 的计数规则；这里只做「读到了没有」的判断。
//
// 判红的两条，都是**结构**判据，不是新定的数值阈值：
//
//   1. **四率都必须是数值**，形状 `[01].[0-9]{3}`。`-`（分母为 0）与 `0/0` **不是读数** ⇒ 红。
//      `measure-governance-rates.mjs:147` 在分母为 0 时打印一个裸 `-`；它会被原样带进 `RATES` 行，
//      而旧的正则 `[0-9.-]+` 恰好也接受 `-` —— 于是一栏从未被驱动也能绿。这一条堵的就是那个洞。
//   2. **SKIP 不是禁止，是钉死。** CI 不接真模型 key（`CONFORMANCE_NO_MODEL_KEY=1`），恰好两栏的
//      主语是模型那条路：`Tool path (MCP)`（两个真模型步）与 `Confirmation`（抽屉的真会话）。故观测到的
//      SKIP 集合必须**恰好等于**这两栏：多一栏 ⇒ 红；少一栏（它突然跑了却没给出读数）⇒ 红。
//      名单见下面的 `EXPECTED_SKIPS`，只有一处。
//
// 用法：
//
//   CONFORMANCE_NO_MODEL_KEY=1 KEEP_CONFIRMATIONS=1 node scripts/ci/run-governance-gate.mjs
//   node scripts/ci/run-governance-gate.mjs --report conformance/reports/app-conformance-<ts>.json
//
// `--report` 不跑套件、只校验一份既有记录 —— 那是反向测试的门：把 2.3 改成 `0/0=-`，本闸门必须红。
//
// ── English ─────────────────────────────────────────────────────────────────────
//
// The CI verdict: drive the application conformance suite, then judge whether the four rates are
// actually readings and whether the skipped columns are exactly the two that are allowed to skip. It
// does not define any rate: 2.1 comes from the scenario suites' own closing assertions, and 2.2 / 2.3 /
// 2.4 come from `measure-governance-rates.mjs` under `RY-19`'s counting rule; this file only answers
// "was it read".
//
// Two structural rules turn it red — no new numeric thresholds:
//
//   1. **all four rates must be numeric**, in the shape `[01].[0-9]{3}`. A bare `-` (denominator 0) and
//      `0/0` are **not readings** ⇒ red. `measure-governance-rates.mjs:147` prints a bare `-` when the
//      denominator is 0; it is carried verbatim into the `RATES` line, and the old regex `[0-9.-]+`
//      accepted `-` too — so a column that was never driven could still go green. This closes that hole.
//   2. **SKIP is pinned, not banned.** CI carries no model key (`CONFORMANCE_NO_MODEL_KEY=1`), and
//      exactly two columns have the model path as their subject: `Tool path (MCP)` (two real model
//      steps) and `Confirmation` (the drawer's real session). The observed SKIP set must be **exactly**
//      those two: one extra ⇒ red; one missing (it ran but produced no reading) ⇒ red. The set lives in
//      `EXPECTED_SKIPS` below, in one place.
//
// Usage:
//
//   CONFORMANCE_NO_MODEL_KEY=1 KEEP_CONFIRMATIONS=1 node scripts/ci/run-governance-gate.mjs
//   node scripts/ci/run-governance-gate.mjs --report conformance/reports/app-conformance-<ts>.json
//
// `--report` does not run the suite; it validates an existing record. That is the reverse test's door:
// change 2.3 to `0/0=-` and this gate must go red.
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const REPORTS = join(ROOT, 'conformance', 'reports');

// The only two columns that may be SKIP in the deterministic CI mode. Both have the model path as their
// subject and need a real model key this job deliberately does not carry. A third SKIP is a missing
// prerequisite; a missing one of these two means the suite changed shape.
//
// 确定性 CI 模式下**只允许**这两栏 SKIP。两栏的主语都是模型那条路，而这道 job 刻意不接模型 key。
// 多一栏 = 缺前置；这两栏少一栏 = 套件形状变了。
const EXPECTED_SKIPS = new Set(['Tool path (MCP)', 'Confirmation']);

const REQUIRED_COLUMNS = [
  'Build',
  'Schema',
  'Permission',
  'Tool Governance',
  'Tool path (MCP)',
  'Tenant',
  'Audit',
  'Confirmation',
  'Revoke',
  'Rates'
];

function die(message) {
  console.error(`\nGOVERNANCE-GATE: ${message}`);
  process.exit(1);
}

// The four rates as one machine-readable line. The conformance runner writes it as `ratesLine` and
// prints it; `--report` reads it from the record, so a crafted record is enough for the reverse test.
// 四率的那一行机器可读文本。套件把它写成 `ratesLine` 并打印；`--report` 从记录里读它，故反向测试
// 只需要一份改过的记录。
function readRates(report) {
  const line = String(report.ratesLine || '');
  const m = line.match(
    /^RATES\s+2\.1=(\d+)\/(\d+)\s+2\.2=(\d+)\/(\d+)=(\S+)\s+2\.3=(\d+)\/(\d+)=(\S+)\s+2\.4=(\d+)\/(\d+)=(\S+)\s*$/
  );
  if (!m) return null;
  const [, n1, d1, n2, d2, v2, n3, d3, v3, n4, d4, v4] = m;
  return [
    { id: '2.1', name: 'task-success', num: Number(n1), den: Number(d1), value: d1 ? (Number(n1) / Number(d1)).toFixed(3) : null, raw: `${n1}/${d1}` },
    { id: '2.2', name: 'tool-success', num: Number(n2), den: Number(d2), value: v2, raw: `${n2}/${d2}=${v2}` },
    { id: '2.3', name: 'confirmation', num: Number(n3), den: Number(d3), value: v3, raw: `${n3}/${d3}=${v3}` },
    { id: '2.4', name: 'refusal', num: Number(n4), den: Number(d4), value: v4, raw: `${n4}/${d4}=${v4}` }
  ];
}

function validate(reportPath) {
  if (!existsSync(reportPath)) die(`no conformance record at ${reportPath}`);
  const report = JSON.parse(readFileSync(reportPath, 'utf8'));
  const cases = Array.isArray(report.cases) ? report.cases : [];

  const missingColumns = REQUIRED_COLUMNS.filter((col) => !cases.some((c) => c.column === col));
  if (missingColumns.length) die(`the record is missing columns: ${missingColumns.join(', ')}`);

  const failed = cases.filter((c) => c.result === 'FAIL');
  if (failed.length) {
    const named = failed.map((c) => `${c.column}: ${c.assertion} — ${c.detail}`);
    die(`the suite recorded FAIL:\n  ${named.join('\n  ')}`);
  }

  const skipped = cases.filter((c) => c.result === 'SKIP').map((c) => c.column);
  const observed = [...new Set(skipped)].sort();
  const expected = [...EXPECTED_SKIPS].sort();
  if (observed.length !== expected.length || observed.some((col, i) => col !== expected[i])) {
    die(
      `SKIP set is [${observed.join(', ') || 'empty'}]; this gate expects exactly [${expected.join(', ')}]. ` +
      'A third skip is a missing prerequisite; a missing one means the suite changed shape.'
    );
  }

  const rates = readRates(report);
  if (!rates) die('the record carries no parsable RATES line');

  console.log('\n== four governance rates ==');
  console.log(`  record: ${reportPath}`);
  for (const c of cases.filter((x) => x.result === 'SKIP')) {
    console.log(`  pinned SKIP: ${c.column} — ${c.detail}`);
  }

  let bad = 0;
  for (const r of rates) {
    // A denominator of 0 is not a reading, whatever the value column says.
    // 分母为 0 不是读数，不管等号右边写的是什么。
    if (!r.den) {
      bad += 1;
      console.error(`  ${r.id} ${r.name.padEnd(13)} ${r.raw} — denominator 0 is not a reading`);
      continue;
    }
    // The shape is the contract: `[01].[0-9]{3}`. `-`, `0/0` and anything non-numeric are red.
    // 形状就是契约：`[01].[0-9]{3}`。`-`、`0/0` 以及任何非数值一律判红。
    if (!/^[01]\.[0-9]{3}$/.test(String(r.value))) {
      bad += 1;
      console.error(`  ${r.id} ${r.name.padEnd(13)} ${r.raw} — not a numeric rate in [01].[0-9]{3}`);
      continue;
    }
    // The printed value must be the ratio, to three decimals. A crafted line that says `1.000` over
    // `0/6` is not a reading either.
    // 打印出来的值必须就是这个比值（三位小数）。`0/6` 却写 `1.000` 的伪造行同样不是读数。
    const ratio = (r.num / r.den).toFixed(3);
    if (ratio !== r.value) {
      bad += 1;
      console.error(`  ${r.id} ${r.name.padEnd(13)} ${r.raw} — value ${r.value} does not match ${r.num}/${r.den} = ${ratio}`);
      continue;
    }
    console.log(`  ${r.id} ${r.name.padEnd(13)} = ${r.raw.split('=')[0]} = ${r.value}`);
    console.log(`GOVERNANCE-RATE ${r.id} ${r.name} ${r.raw.split('=')[0]} ${r.value}`);
  }

  if (bad) die(`${bad} of the four rates are not readings; the lines above name them`);
  console.log(`\nGOVERNANCE-GATE: PASS — ${report.pass} passed, 0 failed, ${observed.length} pinned skip(s), four numeric rates`);
}

// `--report <path>` validates a record instead of running the suite.
// `--report <path>` 只校验一份既有记录，不跑套件。
let reportArg = null;
for (let i = 2; i < process.argv.length; i += 1) {
  if (process.argv[i] === '--report') reportArg = process.argv[i + 1];
}
if (reportArg) {
  validate(resolve(ROOT, reportArg));
  process.exit(0);
}

// The default path is the CI one. The expected SKIP set is defined for the mode that has no model key;
// running without this flag would make the two model columns actually run (and the pinned set wrong), so
// the gate refuses rather than silently judging a different shape.
// 默认路径就是 CI 那条。期望的 SKIP 集合是为「没有模型 key」那个模式定的；不带这个开关时两栏会真的去跑，
// 钉死的集合就不对了，故闸门直接拒绝，而不是悄悄按另一种形状判。
if (process.env.CONFORMANCE_NO_MODEL_KEY !== '1') {
  die('this gate is defined for the deterministic CI mode; set CONFORMANCE_NO_MODEL_KEY=1 (and KEEP_CONFIRMATIONS=1 on an ephemeral stack)');
}

const run = spawnSync(process.execPath, [join(ROOT, 'scripts', 'run-app-conformance.mjs')], {
  cwd: ROOT,
  encoding: 'utf8',
  env: process.env,
  maxBuffer: 32 * 1024 * 1024
});
process.stdout.write(run.stdout || '');
process.stderr.write(run.stderr || '');
if (run.error) die(`could not start run-app-conformance.mjs: ${run.error.message}`);
if (run.status !== 0) die(`run-app-conformance.mjs exited ${run.status}; the FAIL lines above name what went red`);

// The suite prints `record: <path>.json`; that path is authoritative. If a future cut stops printing
// it, fall back to the newest report, so this gate does not silently read an older run.
// 套件会打印 `record: <path>.json`；那个路径是权威来源。若将来它不再打印，就退回「最新的记录」，
// 以免本闸门悄悄读到更早的一轮。
const printed = (run.stdout || '').match(/^record:\s+(.+\.json)\s*$/m);
let reportPath = printed ? resolve(ROOT, printed[1]) : null;
if (!reportPath || !existsSync(reportPath)) {
  const files = existsSync(REPORTS)
    ? readdirSync(REPORTS).filter((f) => /^app-conformance-.*\.json$/.test(f))
      .map((f) => join(REPORTS, f))
      .sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs)
    : [];
  if (!files.length) die('no conformance report was written');
  reportPath = files[0];
}
validate(reportPath);
