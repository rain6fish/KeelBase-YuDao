-- SPDX-License-Identifier: Apache-2.0
--
-- The operations audit's menu row — and deliberately no grant beside it.
--
-- The other two caller-scoped pages were handed to the ordinary role because the server answers them
-- with the caller's own rows, so the grant added no reach. This one is not: the runtime refuses
-- `/audit/operations/logs` to anyone who is not an administrator, the same way it refuses the audit
-- chain. A menu row is a route, but the enforceable line is the API's, and that line is closed.
--
-- Idempotent, and only inserts. No role_menu row: nothing here grants it to anyone.
--
-- 操作审计那条菜单行 —— 而它旁边**刻意没有**授予。
--
-- 另外两页交给普通角色，是因为服务端答的是调用方**自己**的行，故那次授予不增加可达面。这一页不是：
-- 运行时对**非管理员**拒绝 `/audit/operations/logs`，与它拒绝审计链是同一个方式。菜单行是**路由**，
-- 但能强制的界线在 API 那边，而那条线是关着的。
--
-- 幂等，且只插入。没有 `role_menu` 行：这里**不把**它授予任何人。

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 20008, '操作审计', '', 2, 7, 20001, 'operation-audit', 'ep:notebook', 'keelbase/governance/OperationAuditPage', 'KeelbaseGovernanceOperationAudit', 0, '1', '1', '1', 'keelbase', now(), 'keelbase', now(), '0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE component = 'keelbase/governance/OperationAuditPage');
