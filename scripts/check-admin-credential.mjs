#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// The administrator's password has **one** owner, and no gate carries a second copy of it.
//
//   node scripts/check-admin-credential.mjs
//
// `sql/keelbase-demo-accounts.sql` rewrites `admin`'s password when the row still holds upstream's
// default, so a database bootstrapped from `seed-manifest.txt` and one seeded before the demo accounts
// landed accept **different** passwords. A gate that hardcodes either one runs on one database family
// and cannot sign in on the other: that is how `run-crm-customer-scenarios.mjs` produced no readings at
// all on a freshly seeded database, while the same gate had been green for weeks on an older one.
//
// The fix is not "try both" — a gate that tries both would pass on a database where the seed silently
// did not apply, which is exactly what these gates are for. The password is read off the `admin` row
// (`scripts/admin-credential.mjs`), and this check holds the two halves together:
//
//   ① **the seed's hashes are the table's hashes.** Every bcrypt hash in `keelbase-demo-accounts.sql` —
//      the one it writes and the one it expects to find — must be a state `admin-credential.mjs` can
//      name. Change the password in the seed and forget the table ⇒ red, rather than a gate that fails
//      at sign-in on the next fresh database.
//   ② **no gate writes the password itself.** No `scripts/run-*.mjs` may sign in as the administrator
//      with a string literal; each must take the password from the resolver. Put a literal back ⇒ red.
//
// Files only — no database, no stack. The from-scratch rebuild is what finally proves the seed applies;
// this pins the half that can be decided statically.
//
// ---
//
// 管理员口令**只有一个**属主，没有一道门禁另存一份。
//
//   node scripts/check-admin-credential.mjs
//
// `sql/keelbase-demo-accounts.sql` 在 `admin` 仍装上游默认口令时改写它，故「照 `seed-manifest.txt` 从零灌的库」
// 与「演示账号落地之前灌的库」认**不同**的口令。把任何一个写死的门禁，能跑一套库、在另一套上登不进去：
// `run-crm-customer-scenarios.mjs` 就是这样在一个新灌的库上一条读数也出不来，而同一道门禁在旧库上绿了几周。
//
// 修法**不是**「两个都试」—— 两个都试的门禁会在「种子其实没生效」的库上照样通过，而那恰恰是这些门禁存在的理由。
// 口令从 `admin` 那一行读（`scripts/admin-credential.mjs`），本检查把两半钉在一起：
//
//   ① **种子的哈希就是表里的哈希。** `keelbase-demo-accounts.sql` 里出现的每一个 bcrypt 哈希 —— 它写的那个、
//      以及它期望找到的那个 —— 都必须是 `admin-credential.mjs` 点得出名的一种状态。改了种子里的口令却忘了改表
//      ⇒ 红，而不是等到下一个新库上、门禁在登录处失败。
//   ② **门禁自己不许写口令。** 任何 `scripts/run-*.mjs` 都不得用字符串字面量以管理员身份登录，必须从解析器取。
//      把字面量写回去 ⇒ 红。
//
// 只读**文件**——不连库、不起栈。从零重建才是「种子确实生效」的终判；这里钉住能静态判定的那一半。
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { KNOWN_ADMIN_HASHES } from './admin-credential.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SEED = join(ROOT, 'sql', 'keelbase-demo-accounts.sql');
const SCRIPTS = join(ROOT, 'scripts');

let bad = 0;
const fail = (msg) => { console.error(`  FAIL  ${msg}`); bad += 1; };
const ok = (msg) => console.log(`  ok    ${msg}`);

// ① the hashes the seed settles on `admin` ⊆ the table's hashes. Only the `UPDATE` statements count: the
// `INSERT`s in the same file seed `alex`, a different account whose password no gate reads.
const seed = readFileSync(SEED, 'utf8');
const adminStatements = seed.split(';').filter((s) => /^\s*UPDATE\s+system_users/im.test(s));
const seedHashes = [...new Set(adminStatements.join(';').match(/\$2[aby]\$\d\d\$[./A-Za-z0-9]{53}/g) || [])];
if (seedHashes.length < 2) {
  fail(`${SEED} settles fewer than two hashes on admin — the check cannot tell "what it writes" from "what it expects"`);
}
const known = new Set(KNOWN_ADMIN_HASHES.map((c) => c.hash));
for (const hash of seedHashes) {
  if (known.has(hash)) ok(`seed hash known to admin-credential.mjs (${hash.slice(0, 14)}…, ${KNOWN_ADMIN_HASHES.find((c) => c.hash === hash).state})`);
  else fail(`seed hash ${hash} is a state admin-credential.mjs cannot name — it cannot resolve that password`);
}

// ② no gate hardcodes the administrator's password.
const LITERAL = /login\(\s*'admin'\s*,\s*['"`]/;
const INLINE = /username:\s*'admin'\s*,\s*password:\s*['"`]/;
const RESOLVED = /login\(\s*(?:ADMIN_USER|adminUser)\s*,\s*resolveAdminPassword\(/;
const INLINE_RESOLVED = /username:\s*ADMIN_USER\s*,\s*password:\s*resolveAdminPassword\(/;
const gates = readdirSync(SCRIPTS).filter((f) => /^run-.*\.mjs$/.test(f)).sort();
for (const file of gates) {
  const text = readFileSync(join(SCRIPTS, file), 'utf8');
  if (LITERAL.test(text)) {
    fail(`${file} signs in as admin with a literal password — take it from resolveAdminPassword(psql)`);
    continue;
  }
  if (INLINE.test(text)) {
    fail(`${file} sends admin's password as a literal in the request body — take it from resolveAdminPassword(psql)`);
    continue;
  }
  if (RESOLVED.test(text) || INLINE_RESOLVED.test(text)) ok(`${file} takes admin's password from the resolver`);
}

console.log();
if (bad > 0) {
  console.error(`${bad} problem(s): the administrator's password has a second owner, or the gate that reads it was reverted.`);
  process.exit(1);
}
console.log(`admin credential holds: ${seedHashes.length} seed hash(es) known, ${gates.length} scenario gate(s) scanned.`);
