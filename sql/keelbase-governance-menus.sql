-- SPDX-License-Identifier: Apache-2.0
--
-- The governance console's four menu rows: one directory and the three pages under it.
--
-- Rows, not routes. This host builds its menu tree from `system_menu`, so a page nobody can reach is
-- a page without a row here — adding the .vue files alone would leave them compiled and unlisted.
-- The rows carry the component path the router resolves against `src/views/**`, which is how the
-- overlay's files are found.
--
-- Only inserts, and only into a range upstream does not use (upstream's highest id in this table is
-- 12939). Not one existing row is touched: the reference host re-parents and disables menus it does
-- not want, and this file does neither — the governance pages are additions, not corrections.
--
-- Idempotent: each statement inserts only when its own component path is absent, so a second run
-- changes nothing and a re-run after upstream moves its ids still finds the same four rows.
--
-- No permission string is attached. The pages' authority is enforced by the runtime, not by menu
-- visibility: the audit and ledger routes answer only an administrator, and a caller who is not one
-- gets a refusal from the API rather than a hidden menu item. That keeps the boundary where it can
-- be tested — and it means an operator who is not an administrator may see a menu that answers with
-- a refusal, which is the honest rendering of "this deployment will not show you that".
--
-- 治理控制台的四条菜单行：一个目录，与它下面的三页。
--
-- 是**行**，不是路由。本宿主的菜单树由 `system_menu` 生出来，故没人到得了的页面就等于没有这里
-- 的一行——只把 `.vue` 文件放进去，它们会被编译、却不出现在任何地方。这些行带着组件路径，路由据此
-- 到 `src/views/**` 里解析——overlay 的文件就是这样被找到的。
--
-- 只插入，且只插进上游不用的号段（这张表里上游最大的 id 是 12939）。**一行既有记录都不动**：
-- 参照实现的宿主会改挂与停用自己不想要的菜单，本文件两者都不做——治理这几页是**新增**，不是**更正**。
--
-- 幂等：每条语句只在自己的组件路径不存在时插入，故重跑不改动任何东西，上游挪了 id 之后重跑仍找到
-- 同样这四行。
--
-- **不挂权限串**。这几页的权由**运行时**强制，不由菜单可见性强制：审计与台账那两条路由只答管理员，
-- 不是管理员的人拿到的是 API 的**拒绝**，而不是一个被藏起来的菜单项。边界因此留在**可被测**的地方
-- ——代价是：非管理员的操作者可能看见一个点开只有拒绝的菜单，而那就是「本部署不给你看这个」的诚实写法。

-- 1) The directory. `path` is absolute because it is a top-level entry.
-- 1) 目录。`path` 是绝对路径，因为它是顶层项。
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 20001, '治理', '', 1, 90, 0, '/keelbase-governance', 'ep:lock', NULL, NULL, 0, '1', '1', '1', 'keelbase', now(), 'keelbase', now(), '0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 20001);

-- 2) The three pages. `path` is relative because the parent supplies the prefix.
-- 2) 三页。`path` 是相对的，前缀由父项给出。
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 20002, '审计链', '', 2, 1, 20001, 'audit-chain', 'ep:link', 'keelbase/governance/AuditChainPage', 'KeelbaseGovernanceAuditChain', 0, '1', '1', '1', 'keelbase', now(), 'keelbase', now(), '0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE component = 'keelbase/governance/AuditChainPage');

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 20003, 'AI 行动中心', '', 2, 2, 20001, 'action-center', 'ep:bell', 'keelbase/governance/ActionCenterPage', 'KeelbaseGovernanceActionCenter', 0, '1', '1', '1', 'keelbase', now(), 'keelbase', now(), '0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE component = 'keelbase/governance/ActionCenterPage');

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 20004, 'AI 写入台账', '', 2, 3, 20001, 'write-ledger', 'ep:document-checked', 'keelbase/governance/ToolsPage', 'KeelbaseGovernanceTools', 0, '1', '1', '1', 'keelbase', now(), 'keelbase', now(), '0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE component = 'keelbase/governance/ToolsPage');
