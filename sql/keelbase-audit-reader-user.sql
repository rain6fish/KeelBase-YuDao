-- SPDX-License-Identifier: Apache-2.0
--
-- 审计读者账号：给已有的 `audit_reader` 角色一个**可登录的账号** `m7audit`。
--
-- **为什么需要它。** `keelbase-audit-reader.sql` 只种了**角色**与它在治理读面上的菜单授予，没有账号；
-- 而 `YudaoAuditReaderRules` 认人是按 `system_user_role` → `system_role.code = 'audit_reader'` 找的，
-- 所以「没有账号」不是一个可以直接在界面里绕过的小事：部署完的库里根本没有人能以审计读者身份登录。
-- M7 非技术读者核验为此卡住过一次（2026-10-25 演练，见 `KeelBase4TS/M7-读者单页与主持卡_2026-10-25.md`）——
-- 读者要看到的「换一个账号、左侧菜单换一整套」这一步，在种子齐备的部署上无法发生。
--
-- **只加账号，不改角色。** 授予仍旧全部来自 `keelbase-audit-reader.sql`（六条治理读面 + 边界页），
-- 本文件不写 `system_role_menu` 一行：角色是身份，账号只是这个身份的一次登录。
--
-- **账号。** `m7audit` / `admin123`（复用上游 `admin` 的 bcrypt 哈希，与 `m7reader` 同一口径，便于走查
-- 主持人交付）。它只持有 `audit_reader` 一个角色，**不是**管理员；口令是上游默认值，真实部署应改。
--
-- **数据范围 5（仅本人）** 沿用角色定义；审计读者读的是治理记录，不按业务数据范围收窄。
--
-- 只插、幂等：用户按 `username + tenant` 匹配，关系按「用户 + 角色」对匹配。
-- 依赖 `keelbase-audit-reader.sql` 先落下角色，故在 `seed-manifest.txt` 里紧随其后。

-- The audit reader's *account*: gives the existing `audit_reader` role a **login** — `m7audit`.
--
-- **Why it exists.** `keelbase-audit-reader.sql` seeds the **role** and its grants on the governance
-- read faces, but no account; and `YudaoAuditReaderRules` identifies the reader through
-- `system_user_role` → `system_role.code = 'audit_reader'`. So "there is no account" is not something a
-- deployment can route around in the UI: in a freshly seeded database nobody can sign in *as* an audit
-- reader at all. The M7 non-technical reader check stalled on exactly this (2026-10-25 rehearsal, see
-- `KeelBase4TS/M7-读者单页与主持卡_2026-10-25.md`): the step where the reader switches accounts and
-- sees a completely different menu could not happen on a seed-complete deployment.
--
-- **Account only, role untouched.** Every grant still comes from `keelbase-audit-reader.sql` (the six
-- governance read faces plus the boundary page); this file writes no `system_role_menu` row. A role is an
-- identity; an account is one login of that identity.
--
-- **The account.** `m7audit` / `admin123` (reusing upstream `admin`'s bcrypt hash, the same convention as
-- `m7reader`, so a moderator can hand it over). It holds `audit_reader` and nothing else — **not** an
-- administrator; the password is upstream's default, so a real deployment should change it.
--
-- **Data scope 5 (own rows)** stays as the role defines it; an audit reader reads governance records and
-- is not narrowed by business data scope.
--
-- Inserts only, idempotent: the user matches on `username + tenant`, the relationship on the
-- user-and-role pair. It depends on `keelbase-audit-reader.sql` having created the role first, so it runs
-- immediately after it in `seed-manifest.txt`.

-- 1) 账号本身。口令哈希与上游 `admin` 相同（即上游默认口令 `admin123`）；只作审计读者身份使用。
-- 1) The account itself. The password hash is upstream `admin`'s (i.e. upstream's default `admin123`);
--    it is used only as the audit reader identity.
INSERT INTO system_users (id, username, password, nickname, remark, dept_id, post_ids, email, mobile,
                          sex, avatar, status, login_ip, login_date, creator, create_time, updater,
                          update_time, deleted, tenant_id)
SELECT 9002, 'm7audit', '$2a$04$.vd8nPeLwxt6hnSzmAoAyul8BOLX7Cib6QhcxRe30rfvrIPQHH1OG',
       '审计读者', '审计读者专用非管理员账号（只读治理记录）', 100, '[]', '', '', 0, '', 0, '', NULL,
       'keelbase', now(), 'keelbase', now(), 0, 1
WHERE NOT EXISTS (SELECT 1 FROM system_users WHERE username = 'm7audit' AND tenant_id = 1 AND deleted = 0);

-- 2) 账号 ↔ 角色。只挂 `audit_reader` 一个角色。
-- 2) Account ↔ role. `audit_reader` is the only role it carries.
INSERT INTO system_user_role (id, user_id, role_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 20071, u.id, r.id, 'keelbase', now(), 'keelbase', now(), 0, 1
FROM system_users u, system_role r
WHERE u.username = 'm7audit' AND u.tenant_id = 1 AND u.deleted = 0
  AND r.code = 'audit_reader' AND r.tenant_id = 1 AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM system_user_role ur
                  WHERE ur.user_id = u.id AND ur.role_id = r.id AND ur.deleted = 0);
