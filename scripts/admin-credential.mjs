// SPDX-License-Identifier: Apache-2.0
//
// Which password the `admin` row holds — read off the row rather than assumed.
//
// The seed `sql/keelbase-demo-accounts.sql` rewrites `admin`'s password **when the row still carries
// upstream's default**, so that a database bootstrapped from `seed-manifest.txt` can be entered with the
// same account the main repo's sign-in page names. Two states are therefore legitimate, and a database
// built from zero lands in the second one:
//
//   1. `admin123` — upstream's default. Every database seeded before the demo accounts landed.
//   2. `Admin@2026$KeelBase` — what the seed writes. Every database bootstrapped from zero since.
//
// A gate that hardcodes either one cannot run against the other: that is what a literal `admin123` did
// to `run-crm-customer-scenarios.mjs` on a freshly seeded database, where sign-in failed and the gate
// produced no readings at all. Trying both in turn would be worse, not better — it would pass on a
// database where the seed silently did not apply, which is the one thing these gates exist to notice.
//
// So nothing here is guessed. The hash in `system_users` names the state, the table below turns that
// hash back into the password, and a hash that is in neither state **stops the gate** with a message
// saying which knob changes it. `scripts/check-admin-credential.mjs` holds this table against the seed
// file, so the two cannot drift apart unnoticed.
//
// ---
//
// `admin` 那一行装的是哪个口令 —— **从行里读**，不是假设。
//
// 种子 `sql/keelbase-demo-accounts.sql` 会在 `admin` **仍是上游默认口令**时改写它，好让一个照
// `seed-manifest.txt` 从零灌出来的库，能用主仓登录页上写的那个账号进得去。于是两种状态都是合法的，而**从零建的库
// 落在第二种**：
//
//   1. `admin123` —— 上游默认。演示账号落地之前灌的每一个库。
//   2. `Admin@2026$KeelBase` —— 种子写下的那个。此后从零灌的每一个库。
//
// 把任何一个写死的门禁，都跑不了另一种：写死 `admin123` 的 `run-crm-customer-scenarios.mjs` 在一个新灌的库上
// 就是这样 —— 登录失败、一条读数也出不来。**两个轮流试**更坏而不是更好：它会在「种子其实没生效」的库上照样通过，
// 而那恰恰是这些门禁存在的理由。
//
// 故这里**什么都不猜**。`system_users` 里的哈希点名状态，下面这张表把哈希换回口令，而两种状态都不是的哈希会
// **让门禁停下**，并说出改它的那个旋钮。`scripts/check-admin-credential.mjs` 拿这张表与种子文件对账，两者不会
// 悄悄漂开。

/** The account every scenario gate signs in as. Overridable for a host whose administrator is named otherwise. */
export const ADMIN_USER = process.env.KEELBASE_ADMIN_USER || 'admin';

// The two states, keyed by the bcrypt hash the row holds. The hashes are the seed file's own: the second
// is the `SET` value, the first is the `WHERE` precondition that decides whether that `SET` applies.
// 两种状态，以行里装的 bcrypt 哈希为键。两个哈希都来自种子文件本身：第二个是 `SET` 的值，第一个是决定那条
// `SET` 适不适用的 `WHERE` 前置条件。
const CREDENTIALS = [
  {
    hash: '$2a$04$JYSak0bHskc5uLm2LEDXoeXpGczBqVoPgaCkwwkZ6pv4RjtUtYRzK',
    password: 'Admin@2026$KeelBase',
    state: 'the demo seed rewrote it'
  },
  {
    hash: '$2a$04$.vd8nPeLwxt6hnSzmAoAyul8BOLX7Cib6QhcxRe30rfvrIPQHH1OG',
    password: 'admin123',
    state: "upstream's default"
  }
];

/**
 * The password for `admin`, for a `psql` that runs one statement and returns its result.
 *
 * `KEELBASE_ADMIN_PASS` wins outright: a host whose administrator was given a password by hand is not a
 * state this table can name, and the person running the gate knows what it is.
 *
 * 取 `admin` 的口令，入参是一个「跑一条语句、返回结果」的 `psql`。
 *
 * `KEELBASE_ADMIN_PASS` 优先：被人**手动**改过口令的宿主不在这张表能点名的状态里，而跑门禁的人知道它是什么。
 */
export function resolveAdminPassword(psql) {
  if (process.env.KEELBASE_ADMIN_PASS) {
    return process.env.KEELBASE_ADMIN_PASS;
  }
  const hash = psql(`select password from system_users where username = '${ADMIN_USER}' and deleted = 0 order by id limit 1`);
  const hit = CREDENTIALS.find((c) => c.hash === hash);
  if (!hit) {
    throw new Error(
      `admin 的口令不在本线认识的两种状态里（哈希 ${hash || '<空>'}）—— 被人改过就设 KEELBASE_ADMIN_PASS / `
      + `the admin row holds a password this line does not know (hash ${hash || '<empty>'}) — set KEELBASE_ADMIN_PASS if it was changed by hand`
    );
  }
  return hit.password;
}

/** Exposed for `scripts/check-admin-credential.mjs`, which holds these against the seed file. */
export const KNOWN_ADMIN_HASHES = CREDENTIALS.map((c) => ({ hash: c.hash, state: c.state }));
