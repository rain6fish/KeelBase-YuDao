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
-- No permission string is attached, and no role grant is added either — so what a caller sees is
-- decided by the host's own rule, not by this file. `super_admin` sees every menu; anyone else sees
-- only what their roles were granted, and no role was granted these. Measured on the running host:
-- an administrator gets the 治理 group and the pages answer; a caller with no roles sees neither the
-- group nor the route (a direct navigation lands on the host's 404), and the runtime refuses the
-- audit chain to them with 403 if they call it anyway. Two layers, two refusals; the enforceable one
-- is the API's.
--
-- Whether these pages should be granted to a role is a separate question and is left open. Two of
-- them are scoped to the caller by the server and would be harmless to hand out; the audit chain is
-- administrator-only by the runtime's own rule.
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
-- **不挂权限串，也不加角色授予**——故谁能看见由**宿主自己的规矩**决定，不由本文件决定。`super_admin`
-- 看得见每一条菜单；其余人只看得见角色被授予的那些，而**没有任何角色**被授予这几条。在跑起来的宿主上
-- 实测：管理员拿到「治理」组、页面能答；无角色的调用方既看不到那个组、也**没有那条路由**（直接导航落到
-- 宿主的 404），而万一他直接调审计链，运行时以 403 回绝。两层、两种拒绝；能强制的那个是 API 的。
--
-- 这几页该不该授予某个角色，是另一个问题，**留待决定**：其中两页由服务端按调用方限定范围、发出去并无害；
-- 审计链则按运行时自己的规矩只给管理员。

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
