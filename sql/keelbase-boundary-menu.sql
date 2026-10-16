-- SPDX-License-Identifier: Apache-2.0
--
-- 治理边界那条菜单行 —— 与审计链不同，它旁边**有**一条授予。
--
-- 这一页画的是**这个构建**的一条事实：本部署的治理扣住哪些 AI 路径、哪些不扣。它不含任何调用方的数据，也不向
-- 服务端要什么 —— 它渲染的那份记录，是**界面构建时**就被铺进产物的 —— 故把它发给普通角色**一点可达面都不
-- 增加**。这与 `keelbase-governance-menu-grants.sql` 用的是**同一条判据、只是方向相反**：服务端按调用方限
-- 范围的页可以发，服务端对非管理员一律拒绝的页不能发。
--
-- 父目录（`20001`）已由那份授予文件发给同一角色；没有它，一个被授予的子项**没有可挂的地方**，会「按 URL 到
-- 得了、却不出现在侧栏」。
--
-- 幂等，且只插入：按它自己的组件路径匹配，用的 id 在本线已用号段之上（此前最高到 `20028`）。
--
-- The boundary page's menu row — and, unlike the audit chain, a grant beside it.
--
-- What the page draws is a fact about *the build*: which AI paths this deployment's governance holds and
-- which it does not. It carries no caller's data and asks the server for nothing — the record it renders
-- was laid into the artefact when the UI was built — so handing it to the ordinary role adds no reach at
-- all. That is the test `keelbase-governance-menu-grants.sql` applies, in the other direction: a page the
-- server scopes to the caller can be handed out; one the server refuses to a non-administrator cannot.
--
-- The parent directory (`20001`) is already granted to the same role; without it a granted child has
-- nothing to hang from and would be reachable by URL while absent from the sidebar.
--
-- Idempotent, and only inserts: matches on its own component path, and uses ids above the range this line
-- has spent already (the highest so far is `20028`).

-- The row first. / 先菜单行。
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 20029, '治理边界', '', 2, 9, 20001, 'boundary', 'ep:guide', 'keelbase/governance/BoundaryPage', 'KeelbaseGovernanceBoundary', 0, '1', '1', '1', 'keelbase', now(), 'keelbase', now(), '0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE component = 'keelbase/governance/BoundaryPage');

-- Then the grant, to the host's own 普通角色 (role id 2), tenant 1 — the same pair the grants file writes.
-- 再是授予：宿主自己的**普通角色**（role id 2）、租户 1 —— 与那份授予文件写的同一对。
INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 20030, 2, 20029, 'keelbase', now(), 'keelbase', now(), 0, 1
WHERE NOT EXISTS (SELECT 1 FROM system_role_menu WHERE role_id = 2 AND menu_id = 20029 AND deleted = 0);
