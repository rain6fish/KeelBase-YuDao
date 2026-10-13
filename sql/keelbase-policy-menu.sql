-- SPDX-License-Identifier: Apache-2.0
--
-- The policy centre's menu row — and deliberately no grant beside it.
--
-- This is the one page in the console that writes, and what it writes changes what every tool's gate
-- does for everyone. The runtime refuses both of its faces to anyone who is not an administrator; a
-- menu row is a route, and a role grant would advertise a reach the API does not have.
--
-- Idempotent, and only inserts. No role_menu row: nothing here grants it to anyone.
--
-- 策略中心那条菜单行 —— 而它旁边**刻意没有**授予。
--
-- 这是控制台里**唯一会写**的一页，而它写的东西会改变**每个工具**的闸对**每个人**的行为。运行时对**非
-- 管理员**拒绝它的两条面；菜单行是**路由**，而一次角色授予等于在宣传一个 API 并不具备的可达面。
--
-- 幂等，且只插入。没有 `role_menu` 行：这里**不把**它授予任何人。

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 20009, '策略中心', '', 2, 8, 20001, 'policy-center', 'ep:setting', 'keelbase/governance/PolicyCenterPage', 'KeelbaseGovernancePolicyCenter', 0, '1', '1', '1', 'keelbase', now(), 'keelbase', now(), '0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE component = 'keelbase/governance/PolicyCenterPage');
