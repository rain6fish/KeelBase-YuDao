-- SPDX-License-Identifier: Apache-2.0
--
-- The conversation trace's menu row, and its grant.
--
-- Granted, unlike the audit chain and the tool inventory: this page answers the caller's **own**
-- conversations and refuses another user's — the server decides both — so handing it to the ordinary
-- role adds no reach. That is the same line the Action Center and the write ledger sit on.
--
-- The parent directory is not re-granted here: an earlier file already gave it to the same role, and
-- repeating the insert would change nothing (it is idempotent) while implying otherwise.
--
-- Idempotent, and only inserts.
--
-- 决策轨迹那条菜单行，连同它的授予。
--
-- **发了**，与审计链、工具清单不同：这一页答的是调用方**自己**的会话、拒绝别人的——两者都由服务端定——
-- 故把它交给普通角色**不增加任何可达面**。行动中心与写入台账就在同一条线上。
--
-- 父目录不在这里重复授予：更早那份文件已经把它给了同一个角色，重复插入什么也改不了（它是幂等的），
-- 却会让人以为改了。
--
-- 幂等，且只插入。

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 20007, '决策轨迹', '', 2, 6, 20001, 'conversation-trace', 'ep:connection', 'keelbase/governance/ConversationTracePage', 'KeelbaseGovernanceConversationTrace', 0, '1', '1', '1', 'keelbase', now(), 'keelbase', now(), '0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE component = 'keelbase/governance/ConversationTracePage');

INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 20017, 2, 20007, 'keelbase', now(), 'keelbase', now(), 0, 1
WHERE NOT EXISTS (SELECT 1 FROM system_role_menu WHERE role_id = 2 AND menu_id = 20007 AND deleted = 0);
