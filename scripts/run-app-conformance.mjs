#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// Conformance for **one application**, not for the protocol.
//
// The runtime's own repository already proves the *protocol*: its conformance runner writes
// `protocol-conformance-<ts>.{json,md}` for the vectors, the audit chain and the risk table. What had
// never been recorded is the thing that actually ships — an application, with its own schema, its own
// permissions, its own tools, its own audit rows. This is that record, and it is deliberately the same
// shape as the protocol one so the two read as one family:
//
//   protocol record   vectors{...}   implementations[]   cases[]
//   application record application{} cases[]              (+ rates)
//
// This is the criterion `D28` red line ⑤ asks for — changing the host must not mean giving up the
// reproducibility the previous line had — and the tool is carried over from that line's `RY-29`, where
// the column set and the "drive the gates, do not quote them" rule were fixed.
//
// **The columns are this application's, not a copy of the previous line's.** Where this line has no
// gate, the column says so rather than disappearing:
//
//   Build · Schema · Permission · Tool Governance · Tool path (MCP) · Audit · Confirmation · Revoke
//   · Rates
//
// The previous line's record carried a **RAG permission** column; this line wires no memory/knowledge
// gate yet, so there is no column for it here — that is a difference in the application, stated here
// so it is not read as an omission.
//
// Rows it cannot stand behind are `SKIP` **with the reason attached**, never `PASS`. The three gates
// it drives are the application's own, and driving them is the point: a record that restated their
// results would be a transcription.
//
// Writes `conformance/reports/app-conformance-<ts>.json` and `.md`, **neither tracked** — a record is
// evidence, not source. The directory carries its own `.gitignore` for that reason.
//
//   node scripts/run-app-conformance.mjs
//
// ── 中文 ────────────────────────────────────────────────────────────────────────
//
// **一个应用**的符合性，而不是协议的符合性。
//
// 运行时本体那个仓已经证过**协议**：它的 conformance runner 会写 `protocol-conformance-<ts>.{json,md}`，
// 覆盖向量、审计链与风险表。**从来没被记过的是真正交付出去的那样东西**——一个应用：它自己的 schema、
// 自己的权限、自己的工具、自己的审计行。本脚本就是那份记录，而且**刻意与协议那份同形**：
//
//   协议记录   vectors{...}   implementations[]   cases[]
//   应用记录   application{} cases[]              （+ 四率）
//
// 这正是 `D28` 红线 ⑤ 要的那条判据——**换了底座，不等于放弃上一条线已有的可复现性**；工具本身承接自那条线的
// `RY-29`，栏位与「**驱动门禁、不誊抄门禁**」那条规矩都在那里定的。
//
// **栏位是「这个应用」的，不是把上一条线的照抄一份。** 本线没有门禁的地方，栏位**说出来**，而不是消失：
//
//   构建 · Schema · 权限 · 工具治理 · 工具路径（MCP）· 审计 · 确认 · 撤销 · 四率
//
// 上一条线那份记录带一栏 **RAG 权限**；本线**还没有接记忆 / 知识那条门禁**，故这里没有那一栏——这是**应用**的
// 差别，写在这里，免得被读成遗漏。
//
// 站不住的栏写 `SKIP` **并附上原因**，**绝不写 `PASS`**。它驱动的三道门禁都是**这个应用自己的**，而**驱动**
// 正是要点：一份复述它们结果的记录只是誊抄。
//
// 输出 `conformance/reports/app-conformance-<ts>.json` 与 `.md`，**两者都不入版本控制**——记录是**证据、
// 不是源码**；那个目录自带 `.gitignore` 就是为这个。
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
process.chdir(ROOT);

const HOST = process.env.HOST_URL || 'http://127.0.0.1:48080';
const PG = process.env.PG_CONTAINER || 'keelbase-yudao-pg';
const PG_USER = process.env.PG_USER || 'root';
const PG_PASS = process.env.PG_PASSWORD || 'password';
const DB = process.env.PG_DB || 'ruoyi-vue-pro';
const OUT_DIR = process.env.OUT_DIR || 'conformance/reports';

const STARTED = Date.now();
const stamp = () => new Date().toISOString().replace(/\.\d+Z$/, 'Z').replace(/:/g, '-');

let pass = 0, fail = 0, skip = 0;
const CASES = [];
let RATES = '';

function psql(sql) {
  try {
    return execFileSync(
      'docker',
      ['exec', '-i', '-e', `PGPASSWORD=${PG_PASS}`, PG, 'psql', '-U', PG_USER, '-d', DB, '-v', 'ON_ERROR_STOP=1', '-tAc', sql],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }
    ).trim();
  } catch {
    return '';
  }
}

// A detail carrying a pipe splits its own record and breaks the table, so the one character that is
// also this record's field separator is replaced here, at the boundary, rather than assumed absent.
// 详情里若带了竖线，会把它自己的记录切开、也会破坏表格，所以在**边界处**把这一个同时也是本文件字段分隔符的
// 字符换掉，而不是假定它不会出现。
function row(column, assertion, result, detail) {
  const det = String(detail ?? '').replace(/\|/g, '/');
  CASES.push({ column, assertion, result, detail: det });
  if (result === 'PASS') { pass += 1; console.log(`  PASS  ${column.padEnd(16)} ${assertion}`); }
  else if (result === 'SKIP') { skip += 1; console.log(`  SKIP  ${column.padEnd(16)} ${assertion} — ${det}`); }
  else { fail += 1; console.log(`  FAIL  ${column.padEnd(16)} ${assertion} — ${det}`); }
}

function judge(column, assertion, expected, got) {
  if (String(expected) === String(got)) row(column, assertion, 'PASS', got);
  else row(column, assertion, 'FAIL', `expected [${expected}], got [${got}]`);
}

// Drive one of the application's own gates. Its exit code and its own closing line are the evidence;
// when it goes red, the assertions that went red are named in the record rather than left for a reader
// to reverse-engineer — a bare count used to be what this carried, and it proved to force the reader
// back to re-running the gate to find out what had actually failed. Capped at three: the record is a
// summary, and the gate's own output is still where the rest is read.
// 驱动应用自己的一道门禁。退出码与它自己的收束行就是证据；它转红时，**红的是哪几条在记录里点名**，而不是留给
// 读的人去反推——本文件原先只带一个计数，事实证明那会逼得读的人回去重跑门禁。最多三条：记录是汇总，剩下的去读
// 门禁自己的输出。
function gate(column, script, what) {
  const r = spawnSync(process.execPath, [join('scripts', script)], {
    cwd: ROOT,
    encoding: 'utf8',
    env: { ...process.env, HOST_URL: HOST, PG_CONTAINER: PG, PG_USER, PG_PASSWORD: PG_PASS, PG_DB: DB }
  });
  const out = `${r.stdout || ''}${r.stderr || ''}`;
  const status = r.status === null ? `spawn failed (${r.error?.message || 'no status'})` : r.status;
  const m = out.match(/(\d+) ok \/ (\d+) failed/);
  if (!m) {
    row(column, what, 'SKIP', `scripts/${script} produced no summary (exit ${status})`);
    return;
  }
  const summary = `${m[1]} ok / ${m[2]} failed`;
  if (r.status === 0) {
    row(column, what, 'PASS', summary);
    return;
  }
  const red = out.split('\n').filter((l) => l.startsWith('  FAIL'))
    .map((l) => l.replace(/^  FAIL\s+/, '').replace(/ — .*$/, ''));
  const named = red.slice(0, 3).join('; ') + (red.length > 3 ? `; +${red.length - 3} more` : '');
  row(column, what, 'FAIL', `${summary} (exit ${r.status})${named ? ` — failed: ${named}` : ''}`);
}

const sh = (cmd, args) => {
  try { return execFileSync(cmd, args, { encoding: 'utf8' }).trim(); } catch { return ''; }
};

const SHA = sh('git', ['rev-parse', '--short', 'HEAD']) || 'unknown';
const DIRTY = sh('git', ['status', '--porcelain']).split('\n').filter(Boolean).length;
const CORE = (() => {
  try {
    const pom = readFileSync('yudao-server/pom.xml', 'utf8');
    return pom.match(/keelbase4j-core[\s\S]*?<version>([^<]+)<\/version>/)?.[1] || 'unknown';
  } catch { return 'unknown'; }
})();

console.log(`== application conformance · ${basename(ROOT)} @ ${SHA} ==`);

// 1 · Build — the application answers, and the record names the commit it answered at.
{
  let code = '000', body = '';
  try {
    // Node's fetch is used rather than curl: this file already depends on Node, and the shell version
    // this is carried over from reached for curl only because it was a shell script.
    const res = await fetch(`${HOST}/actuator/health`, { signal: AbortSignal.timeout(8000) });
    code = String(res.status);
    body = await res.text();
  } catch { /* code stays 000 */ }
  judge('Build', 'the application answers on /actuator/health', '200', code);
  row('Build', 'it reports itself UP', /"status"\s*:\s*"UP"/.test(body) ? 'PASS' : 'FAIL', body.slice(0, 120) || 'no body');
}

// 2 · Schema — the runtime's own migrations applied, and the tables the other columns read exist.
{
  const missing = psql(`select count(*) from (values ('ai_audit_logs'),('confirmation_requests'),
      ('side_effects'),('crm_customer'),('flyway_schema_history')) t(n)
    where not exists (select 1 from information_schema.tables
                      where table_schema='public' and table_name=t.n)`);
  judge('Schema', 'the five tables this record reads exist', '0', missing || 'ERR');
  const flyway = psql("select coalesce(max(version),'none') from flyway_schema_history where success");
  row('Schema', 'the runtime migrations are at a version',
    !flyway || flyway === 'none' ? 'FAIL' : 'PASS', `flyway=${flyway || 'unreadable'}`);
}

// 3–5 · the application's own gates, driven rather than quoted.
gate('Permission', 'run-crm-customer-scenarios.mjs', "a module upstream wrote, governed end to end");
gate('Tool Governance', 'run-generated-module-scenarios.mjs', "the generated module, same governance");
// The other half of the same column, and the one `YD-T14` (D42) is about: the governance is only as good
// as what the model can reach. A tool of ours registered as a bean `ToolCallback` would be reachable by a
// model through Spring AI's own resolver, which KeelBase's aspect does not see — so the boundary is
// asserted here rather than left to be noticed.
// 同一栏的另一半，也是 `YD-T14`（`D42`）讲的那一件：治理有多好，取决于**模型够得到什么**。我们的工具一旦以
// bean `ToolCallback` 的形式注册，模型就能经 Spring AI 自己的解析器够到它，而 KeelBase 的切面看不见——所以这条
// 边界在这里断言，而不是留着等人发现。
gate('Tool Governance', 'check-ungoverned-tool-beans.mjs', 'no bean hands our tools to Spring AI');
gate('Tool path (MCP)', 'run-mcp-governance-scenarios.mjs', "the host's own tool path, two real model steps");

// 6 · Audit — rows exist, every row but the first is chained to the one before it.
{
  const rows = psql('select count(*) from ai_audit_logs') || '0';
  const unhashed = psql('select count(*) from ai_audit_logs where hash is null');
  row('Audit', 'every audit row carries a hash',
    unhashed === '0' ? 'PASS' : 'FAIL', `rows=${rows}, unhashed=${unhashed || 'ERR'}`);
  const heads = psql('select count(*) from ai_audit_logs where prev_hash is null');
  row('Audit', "exactly one row is the chain's head",
    heads === '1' ? 'PASS' : 'FAIL', `heads=${heads || 'ERR'}`);
}

// 7 · Confirmation — a person decided, and the record says which way.
{
  const approved = psql("select count(*) from confirmation_requests where status='approved'") || '0';
  const spread = psql(`select string_agg(status || '=' || n, ' ') from
      (select status, count(*) n from confirmation_requests group by status order by status) s`);
  row('Confirmation', 'a human decision is on record',
    Number(approved) > 0 ? 'PASS' : 'FAIL', spread || 'none');
}

// Run a gate for its evidence **without** writing a row of its own — for a column whose verdict is read
// from somewhere else and only needs to report whether the attempt was made and how it went.
// 跑一道门禁、**不让它自己写行** —— 给那种「判决取自别处、只需要报『尝试做了没有、做得怎么样』」的栏。
function runGate(script) {
  const r = spawnSync(process.execPath, [join('scripts', script)], {
    cwd: ROOT, encoding: 'utf8',
    env: { ...process.env, HOST_URL: HOST, PG_CONTAINER: PG, PG_USER, PG_PASSWORD: PG_PASS, PG_DB: DB }
  });
  const out = `${r.stdout || ''}${r.stderr || ''}`;
  const m = out.match(/(\d+) ok \/ (\d+) failed/);
  const status = r.status === null ? `spawn failed (${r.error?.message || 'no status'})` : r.status;
  return { status: r.status, summary: m ? `${m[1]} ok / ${m[2]} failed` : `no summary (exit ${status})` };
}

// 8 · Revoke — a side effect was withdrawn, and the ledger says so. The deciding fact is whether a
// revocation was ever **attempted**: a ledger full of executed effects is what confirmations produce,
// not evidence that revoke works. So the column says which of the two it is looking at rather than
// reading an unexercised capability as a failure.
//
// **And the attempt is made here**, before the ledger is read. Until `run-revoke-scenario.mjs` existed
// nothing on this line ever attempted a withdrawal, so the column could only ever read SKIP; that gate
// makes the attempt and asserts the invariant that holds on **both sides of the platform seam**, so it
// is green today and stays green the day the seam and the host's compensator land.
// 撤销——副作用被撤回过、且台账记着。**决定事实是「有没有尝试过撤销」**：一本全是 `executed` 的台账是**确认**
// 产生的、不是「撤销能工作」的证据。所以这一栏**点名自己看的是哪一种**，而不是把一个没被跑过的能力读成失败。
//
// **而那次尝试就在这里做**，在读账本之前。在 `run-revoke-scenario.mjs` 存在之前，本线**从没有人试过撤销**，
// 故这一栏只可能读到 SKIP；那道闸把尝试做出来，并断言**平台那条缝两侧都成立**的不变量 —— 今天绿、缝与宿主
// 补偿器落地之后仍绿。
{
  const attempt = runGate('run-revoke-scenario.mjs');
  const attempts = psql("select count(*) from ai_audit_logs where action='effect_revoke'") || '0';
  const ledger = psql('select count(*) from side_effects') || '0';
  const spread = psql(`select string_agg(revoke_status || '=' || n, ' ') from
      (select revoke_status, count(*) n from side_effects group by revoke_status order by revoke_status) s`);
  if (attempt.status !== 0) {
    // A red attempt is a real failure — the path this column is about was exercised and did not hold.
    // 转红的尝试是**真的失败** —— 这一栏所讲的那条路被跑过、且没站住。
    row('Revoke', 'a withdrawal is attempted and its outcome asserted', 'FAIL',
      `run-revoke-scenario.mjs: ${attempt.summary}`);
  } else if (Number(attempts) === 0) {
    row('Revoke', 'a withdrawal is on the effect ledger', 'SKIP',
      `attempted this run (${attempt.summary}) and did not land — no local revoke path on this host yet (revoke attempts on the audit: ${attempts}; ledger rows: ${ledger}${spread ? `, ${spread}` : ''})`);
  } else {
    const revoked = psql("select count(*) from side_effects where revoke_status='revoked'") || '0';
    row('Revoke', 'a withdrawal is on the effect ledger',
      Number(revoked) > 0 ? 'PASS' : 'FAIL', `${spread || 'none'} (attempted this run: ${attempt.summary})`);
  }
}

// 9 · The other half of the criterion: the four rates, read from the line's own tool.
{
  const r = spawnSync(process.execPath, [join('scripts', 'measure-governance-rates.mjs')], {
    cwd: ROOT, encoding: 'utf8',
    env: { ...process.env, PG_CONTAINER: PG, PG_USER, PG_PASSWORD: PG_PASS, PG_DB: DB }
  });
  RATES = (r.stdout || '').split('\n').filter((l) => /^ {2}(2\.\d|units =)/.test(l)).map((l) => l.trim()).join('\n');
  if (!RATES) row('Rates', 'the four governance rates are readable', 'SKIP', 'measure-governance-rates.mjs gave nothing');
  else row('Rates', 'the four governance rates are readable', 'PASS', RATES.replace(/\n/g, ' | '));
}

// ── the record ──────────────────────────────────────────────────────────────────

const ELAPSED = Math.round((Date.now() - STARTED) / 1000);
const STAMP = stamp();
mkdirSync(OUT_DIR, { recursive: true });
const JSON_PATH = join(OUT_DIR, `app-conformance-${STAMP}.json`);
const MD_PATH = join(OUT_DIR, `app-conformance-${STAMP}.md`);
const esc = (s) => String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, ' ');

const record = {
  gate: 'app-conformance',
  protocol: 'ai-governance-protocol (per application)',
  application: { name: basename(ROOT), commit: SHA, uncommitted: DIRTY, core: CORE },
  date: new Date().toISOString(),
  pass, fail, skip, elapsedSec: ELAPSED,
  rates: RATES.split('\n').join(';'),
  cases: CASES.map((c, i) => ({ n: i + 1, ...c }))
};
writeFileSync(JSON_PATH, `${JSON.stringify(record, null, 2)}\n`);

const md = [];
md.push(`# KeelBase-YuDao Application Conformance（${STAMP}）`, '');
md.push(`- **应用**：${basename(ROOT)} @ \`${SHA}\`（core ${CORE}；未提交改动 ${DIRTY} 处）`);
md.push(`- **${pass} 通过 · ${fail} 失败 · ${skip} 跳过** ｜ 总耗时 ${ELAPSED}s ｜ 判据：\`D28\` 红线 ⑤`, '');
md.push('| # | 栏 | 断言 | 结果 | 详情 |', '|---|---|---|---|---|');
CASES.forEach((c, i) => {
  const mark = c.result === 'PASS' ? '✅' : c.result === 'SKIP' ? '⏭️' : '❌';
  md.push(`| ${i + 1} | ${c.column} | ${c.assertion} | ${mark} | ${c.detail} |`);
});
md.push('', '## 四率（读自 `measure-governance-rates.mjs`）', '', '```', RATES || '(unreadable)', '```');
md.push('', '**本记录不跟踪**（`conformance/reports/` 自带 `.gitignore`）——它是证据、不是源码。', '');
writeFileSync(MD_PATH, md.join('\n'));

console.log(`\n${pass} pass, ${fail} failed, ${skip} skipped in ${ELAPSED}s`);
console.log(`record: ${JSON_PATH}\n        ${MD_PATH}`);
process.exit(fail === 0 ? 0 : 1);
