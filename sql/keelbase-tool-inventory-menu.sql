-- SPDX-License-Identifier: Apache-2.0
--
-- The tool inventory's menu row, under the same 治理 directory as the other console pages.
--
-- No grant: the route behind it answers an administrator and refuses everyone else, so handing the
-- menu to the ordinary role would advertise a page that can only refuse. Same choice the audit chain
-- and the showcase got.
--
-- Idempotent, and only inserts: matches on its own component path, and uses an id outside the range
-- upstream uses.
--
-- 工具清单那条菜单行，挂在与其余控制台页面**同一个**「治理」目录下。
--
-- **不授予**：它后面的路由只答管理员、对别人一律拒绝，故把它发给普通角色等于**推荐一个只会拒绝的
-- 页面**。与审计链、对抗证明拿到的处置相同。
--
-- 幂等，且只插入：按它自己的组件路径匹配，并用一个上游不用的号段。

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 20006, '工具清单', '', 2, 5, 20001, 'tool-inventory', 'ep:tools', 'keelbase/governance/ToolInventoryPage', 'KeelbaseGovernanceToolInventory', 0, '1', '1', '1', 'keelbase', now(), 'keelbase', now(), '0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE component = 'keelbase/governance/ToolInventoryPage');
