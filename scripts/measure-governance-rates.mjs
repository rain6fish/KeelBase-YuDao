#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// Read the four governance rates off this line's database, after a scenario run against it.
//
// The rates are not defined here, and this line did not invent them. They come from the line this one
// took over from, where they were fixed as that line's `RY-19`; what is carried over is the counting
// rule, not a number. The rule has three parts, and each one is a decision rather than an accident:
//
//   * the counting unit is a **logical write** — a `pending confirmation` row opens a unit, and the
//     row that follows it for the same tool closes it;
//   * `pending` and `revoked` are counted as **their own buckets**, never folded into success or
//     refusal;
//   * the denominator for the two tool rates is the units that **reached a conclusion** — success +
//     refusal + still-pending. Revocation is left out of it, because a revocation acts on a write
//     that already happened rather than being a proposal of its own.
//
// Task success (2.1) is deliberately **not** computed here. It is a judgement about intent, and the
// audit rows only carry traces, so it belongs to the scenario script's own closing assertions. There
// is also no time window: this reads the whole table, which is right for the isolated stack a scenario
// run uses and wrong for a long-lived database — a caller that needs a window must supply one.
//
// The criterion this file serves is `D28` red line ⑤: changing the host must not mean giving up the
// reproducibility the previous line had.
//
//   node scripts/measure-governance-rates.mjs
//   PG_CONTAINER=keelbase-yudao-pg PG_DB=ruoyi-vue-pro node scripts/measure-governance-rates.mjs
//
// **Nothing here reads text any more.** A unit is folded per tool and each row's outcome is a word, and
// both are **columns** now (`ai-audit-log-row` v5) — so the fold and every bucket are answered by
// lookups rather than by matching a sentence. The sentence that used to say "one place still reads
// `detail`" is gone with `SPLIT_TOOL`.
//
// **本文件不再有任何一处读文本。** 单位按工具折叠、每行的结局是一个词，而两者**现在都是列**
// （`ai-audit-log-row` v5）——故折叠与每一个桶都由查表回答，而不是去匹配一句话。那句「仍有一处读
// `detail`」随 `SPLIT_TOOL` 一起没了。
//
// ── 中文 ────────────────────────────────────────────────────────────────────────
//
// 从本线的库里把四个治理率读出来（在有一次场景运行之后）。
//
// **率不在这里定义，也不是本线发明的。** 它们来自本线接手的上一条线，在那里被固化为那条线的
// `RY-19`；沿用的是**计数规则**，不是一个数字。规则有三条，每条都是**决定**、不是巧合：
//
//   * 计数单位 = **逻辑写**——一行 `pending confirmation` 开一个单位，紧随其后、同一工具的那一行是它的收束；
//   * `pending` 与 `revoked` **各自成桶**，绝不折进成功或拒绝；
//   * 两个工具率的分母是**有结论的单位**——成功 + 拒绝 + 仍 pending；**撤销不计入**，因为它作用于一次
//     **已经发生的写**，本身不是一次提议。
//
// **任务成功率（2.1）刻意不由这里算**：它是**意图**层面的判断，而审计行只留下痕迹——它属于场景脚本
// 自己的收束断言。这里也**不设时间窗**：读整张表，这对场景运行所用的**隔离栈**是对的、对长期在用的库是错的
// ——需要窗口时由调用方自己给。
//
// 本文件服务的那条判据 = `D28` 红线 ⑤：**换了底座，不等于放弃上一条线已有的可复现性**。
//
// **仍有一处要读文本**：单位是按**工具**折叠的，而工具名是 `detail` 的第一个词——契约今天还没有那一列。
// 其余各格一律按 `action` 计数。等契约加上那一列，要删的就是 `SPLIT_TOOL` 里那一句。
import { execFileSync } from 'node:child_process';

const PG = process.env.PG_CONTAINER || 'keelbase-yudao-pg';
const USER = process.env.PG_USER || 'root';
const PASS = process.env.PG_PASSWORD || 'password';
const DB = process.env.PG_DB || 'ruoyi-vue-pro';

function psql(sql) {
  try {
    return execFileSync(
      'docker',
      ['exec', '-i', '-e', `PGPASSWORD=${PASS}`, PG, 'psql', '-U', USER, '-d', DB, '-v', 'ON_ERROR_STOP=1', '-tAc', sql],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }
    ).trim();
  } catch {
    // The previous line's shell version swallowed psql's stderr for the same reason: an unreachable
    // database and an empty result are both "nothing to report", and the caller distinguishes them by
    // the emptiness, not by the error text.
    return '';
  }
}

// Fold the audit rows into units, then count each bucket. `pending` opens a unit; the row that follows
// it for the same tool closes it and is not itself a unit.
//
// The three words map onto `executed` / `refused` / `pending`; everything else (`blocked`, `failed`,
// `declined`) is `other`, which is what it was before as well — none of those matched the three
// patterns either.
//
// **`attempted` is excluded, and it has to be.** That row is written before the tool runs (so that
// "who tried" is never missing) and it is not a conclusion — but it sits between `pending` and the row
// that concludes it, and that adjacency is exactly what the window below reads. Left in, a `pending`
// row's `next_kind` becomes `attempted` and every approved write stops being counted as one.
//
// 把审计行折成单位、再按桶计数。`pending` 开一个单位；同一工具下紧随它的那一行把它收掉，且自己不算一个
// 单位。
//
// 三个词映到 `executed` / `refused` / `pending`；其余（`blocked` / `failed` / `declined`）是 `other`，
// 与先前一致——它们本来也三个模式都不匹配。
//
// **`attempted` 必须排除。** 那一行写在工具跑之前（为的是「谁试过永不缺行」）、不是一次结论——但它**正好
// 插在 `pending` 与收掉它的那一行之间**，而下面的窗口读的就是这个相邻关系。留着它，`pending` 的
// `next_kind` 会变成 `attempted`，于是**每一次被批准的写都不再被计上**。
const FOLD_SQL = `
with t as (
  select id, tool,
         case outcome when 'executed' then 'ok'
                       when 'refused'  then 'refused'
                       when 'pending'  then 'pending'
                       else 'other' end as kind
  from ai_audit_logs
  where action='tool_call' and outcome is distinct from 'attempted'
), u as (
  select tool, kind,
         lag(kind)  over (partition by tool order by id) as prev_kind,
         lead(kind) over (partition by tool order by id) as next_kind
  from t
)
select
  count(*) filter (where kind='pending' and next_kind='ok')                                 as write_ok,
  count(*) filter (where kind='pending' and next_kind='refused')                            as write_refused,
  count(*) filter (where kind='pending' and coalesce(next_kind,'') not in ('ok','refused')) as write_still_pending,
  count(*) filter (where kind='ok'      and prev_kind is distinct from 'pending')           as read_ok,
  count(*) filter (where kind='refused' and prev_kind is distinct from 'pending')           as read_refused,
  count(*) filter (where kind='other')                                                      as other,
  count(*)                                                                                  as rows_total
from u;`;

// Confirmation rows are a different table and a different unit: one row per confirmation request.
const CONFIRM_SQL = `
select count(*) filter (where status='approved'),
       count(*) filter (where status='declined'),
       count(*) filter (where status='timeout'),
       count(*)
from confirmation_requests;`;

// Revocation is a value of its own in the contract's action vocabulary (`effect_revoke`, v2), not a
// shape a tool_call happens to take — so it is counted by action rather than by reading the text.
const REVOKE_SQL = "select count(*) from ai_audit_logs where action='effect_revoke';";

const RAW = psql(FOLD_SQL);
const CONF = psql(CONFIRM_SQL);
const REV = psql(REVOKE_SQL);

if (!RAW) {
  console.error('no rows: the database is unreachable, or the scenario has not been run against it');
  process.exit(3);
}

const rate = (n, d) => (d === 0 ? '-' : (n / d).toFixed(3));
const num = (s) => Number.parseInt(s ?? '', 10) || 0;

const [W_OK, W_REF, W_PEND, R_OK, R_REF, OTHER, ROWS] = RAW.split('|').map(num);
const [C_APP, C_DEC, C_TIMEOUT, C_ALL] = CONF.split('|').map(num);
const REVOKED = num(REV);

const OK = W_OK + R_OK;
const REF = W_REF + R_REF;
const DENOM = OK + REF + W_PEND;
const UNITS = DENOM + REVOKED + OTHER;

console.log('== tool calls (folded to units) ==');
console.log(`  units = ${UNITS}  (rows = ${ROWS})`);
console.log(`  success  write ${W_OK} + read ${R_OK} = ${OK}`);
console.log(`  refused  write ${W_REF} + read ${R_REF} = ${REF}`);
console.log(`  (separate buckets)  still-pending ${W_PEND} · revoked ${REVOKED} · other ${OTHER}`);
console.log(`  denominator = success + refused + pending = ${DENOM}`);

console.log('== rates (unit = one logical write) ==');
console.log(`  2.2 tool success = ${OK}/${DENOM} = ${rate(OK, DENOM)}`);
console.log(`  2.4 refusal      = ${REF}/${DENOM} = ${rate(REF, DENOM)}`);
console.log(
  `  2.3 confirmation = ${C_APP}/${C_ALL} = ${rate(C_APP, C_ALL)}   ` +
  `(approved ${C_APP} · declined ${C_DEC} · timeout ${C_TIMEOUT})`
);

console.log('== separate buckets, not folded into the rates above ==');
console.log(`  still-pending ${W_PEND} · revoked ${REVOKED}`);
console.log();
console.log("not computed here: task success (2.1) — it belongs to the scenario script's own assertions.");
