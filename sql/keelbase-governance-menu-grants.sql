-- SPDX-License-Identifier: Apache-2.0
--
-- Who may see the governance console: the two pages the server scopes to the caller, and not the
-- one it does not.
--
-- `keelbase-governance-menus.sql` inserts the rows and grants them to nobody, which leaves them
-- visible to `super_admin` alone (it bypasses menu filtering) and to no one else. That is too narrow
-- for two of the three pages. The Action Center answers with the caller's own pending decisions and
-- their own writes; the ledger answers with the caller's own effects, whatever the caller asks for.
-- Both are already scoped by the server — a rule that holds no matter which menu got them here — so
-- handing them out adds no reach. The audit chain is different: the runtime refuses it to anyone who
-- is not an administrator, so it is granted to nobody and stays administrator-only.
--
-- The parent directory is granted with them. A child whose parent is not granted has nothing to hang
-- from: the host builds the tree out of the granted set, so the two pages would be reachable by URL
-- and absent from the sidebar.
--
-- `common` is the host's own 普通角色 (role id 2), the role ordinary users carry. This is a grant per
-- tenant — `system_role_menu` carries a tenant — and this file writes the one this deployment runs
-- in. Another tenant's `common` is its own row and is not touched.
--
-- Only inserts, and idempotent: each statement matches on the pair it would add.
--
-- 谁能看见治理控制台：服务端按调用方限范围的那两页可以，不限范围的那一页不行。
--
-- `keelbase-governance-menus.sql` 插了行、却**谁也没授予**，于是它们只对 `super_admin` 可见（它绕过菜单
-- 过滤）、对别人一个都不见。这对三页里的两页太窄了。行动中心答的是调用方**自己**待决的决定与他自己的
-- 写入；台账答的是调用方**自己**的副作用，无论他请求什么。两者都**已经**由服务端限定范围——那条规矩
-- 成立与否跟谁把它带到这里无关——故把它们发出去并不增加任何可达面。审计链不同：运行时不答任何非管理员，
-- 故它谁也不发，保持只有管理员能看。
--
-- 父目录与它们一起发。父项没被授予的子项**没有可挂的地方**：宿主是按**被授予的集合**建树的，于是那两页
-- 会「按 URL 到得了、却不出现在侧栏」。
--
-- `common` 是宿主自己的**普通角色**（role id 2），普通用户所持的那个。这是**按租户**的授予——
-- `system_role_menu` 带租户——本文件写的是**本部署所跑的那个**。别的租户的 `common` 是它自己的行，不动。
--
-- 只插入，且幂等：每条语句按它将要加入的那一对匹配。

-- The directory first, so the two children have somewhere to hang.
-- 先目录，好让两个子项有地方可挂。
INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 20011, 2, 20001, 'keelbase', now(), 'keelbase', now(), 0, 1
WHERE NOT EXISTS (SELECT 1 FROM system_role_menu WHERE role_id = 2 AND menu_id = 20001 AND deleted = 0);

-- AI 行动中心 — the caller's own pending decisions and their own writes.
-- AI 行动中心 —— 调用方自己的待决决定与他自己的写入。
INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 20012, 2, 20003, 'keelbase', now(), 'keelbase', now(), 0, 1
WHERE NOT EXISTS (SELECT 1 FROM system_role_menu WHERE role_id = 2 AND menu_id = 20003 AND deleted = 0);

-- AI 写入台账 — the caller's own effects; the server scopes the list whoever asks.
-- AI 写入台账 —— 调用方自己的副作用；无论谁问，列表都由服务端限定范围。
INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 20013, 2, 20004, 'keelbase', now(), 'keelbase', now(), 0, 1
WHERE NOT EXISTS (SELECT 1 FROM system_role_menu WHERE role_id = 2 AND menu_id = 20004 AND deleted = 0);
