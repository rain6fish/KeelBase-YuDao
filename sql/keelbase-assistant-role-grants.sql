-- SPDX-License-Identifier: Apache-2.0
--
-- 谁可以用控制台的助手：`common` 角色拿到 `ai1:session:my`。
--
-- **为什么需要先造两行菜单。** `ai1` 模块从来不带界面，故它的权限在这台库里**一行都没有**（实测：
-- `system_menu` 里 `permission like 'ai1:%'` 的行数为 0）。而 yudao 的权限清单是从**被授予的菜单行**上读的
-- —— `@ss.hasPermission('ai1:session:my')` → 角色 → `system_role_menu` → `system_menu.permission` —— 于是
-- **没有行，就除超管（它绕过检查）以外谁都没有**。助手抽屉调的四条接口（`/ai1/session/create-my` ·
-- `session/my-list` · `session/message/my-list` · `session/message/send-stream`）要的**正是这一条**权限，
-- 故它就是那行缺掉的东西。
--
-- **形状照 `sql/keelbase-menus-without-pages.sql` 那条教训**：**按钮权限必须挂在一个启用着的父级下**——父级一被
-- 停用，它的子行权限会被一并带出清单。父级是一条**隐藏**菜单（`visible = 0`，故侧栏看不见它），但它**有真页面**：
-- `keelbase/assistant/AssistantPanel` 就是抽屉里那块面板本身，所以这不是一条「路由到空」的菜单。
--
-- **授权不增加任何可达面。** 这四条接口都是**按调用方**的（`my-` = 调用方自己的会话与消息），服务端按调用方
-- 收束；把它发给普通角色，只是让每个人用得上**自己的**那一个助手会话。与
-- `keelbase-governance-menu-grants.sql` 把「行动中心 / 写入台账」发给同一角色是同一个理由。
--
-- 只插、幂等：菜单按**路径**与**权限串**匹配，授权按「角色 + 菜单」对匹配。
--
-- Who may use the console's assistant: the `common` role gets `ai1:session:my`.
--
-- **Why two menu rows have to be created first.** The `ai1` module never shipped a surface, so its
-- permissions have no row in this database at all (measured: zero rows whose `permission` starts with
-- `ai1:`). And yudao reads a permission list off the **granted menu rows** — `@ss.hasPermission(…)` →
-- role → `system_role_menu` → `system_menu.permission` — so with no row, nobody but the super
-- administrator (who bypasses the check) holds it. The four routes the assistant drawer calls all
-- require exactly this one permission, which is what makes it the row that is missing.
--
-- **The shape follows the lesson in `sql/keelbase-menus-without-pages.sql`**: a button permission must
-- hang under an enabled parent, because a disabled parent takes its children's permissions out of the
-- list with it. The parent here is a **hidden** menu (`visible = 0`, so the sidebar never shows it) but
-- it **has a real page** — `keelbase/assistant/AssistantPanel` is the panel the drawer draws — so it is
-- not a route to nothing.
--
-- **The grant widens nothing.** All four routes are caller-scoped (`my-` = the caller's own sessions and
-- messages), so handing this to the ordinary role only lets each person use **their own** assistant,
-- the same reason `keelbase-governance-menu-grants.sql` hands the action centre and the write ledger to
-- that role.
--
-- Inserts only, and idempotent: the menus match on their path and on the permission string, the grants
-- on the role-and-menu pair.

-- 1) 父级：一条隐藏的「AI 助手」，页面就是抽屉里那块面板。
-- 1) The parent: a hidden "AI 助手" whose page is the panel the drawer draws.
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 20010, 'AI 助手', '', 2, 90, 0, '/ai-assistant', 'ri:robot-2-line', 'keelbase/assistant/AssistantPanel', 'KeelbaseAssistantPanel',
       0, '0', '1', '1', 'keelbase', now(), 'keelbase', now(), '0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE path = '/ai-assistant' AND deleted = 0);

-- 2) 那条权限本身，挂在它下面（`type = 3` 即按钮，永不渲染）。
-- 2) The permission itself, under it (`type = 3`, a button, which never renders).
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 20019, '助手会话', 'ai1:session:my', 3, 1,
       (SELECT id FROM system_menu WHERE path = '/ai-assistant' AND deleted = 0),
       '', '', '', NULL, 0, '0', '1', '1', 'keelbase', now(), 'keelbase', now(), '0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE permission = 'ai1:session:my' AND deleted = 0);

-- 3) 把父级与那条按钮都发给 `common`（role 2，本部署的普通角色），按租户。
-- 3) Grant both the parent and the button to `common` (role 2, this deployment's ordinary role), per tenant.
INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 20014, 2, m.id, 'keelbase', now(), 'keelbase', now(), 0, 1
FROM system_menu m
WHERE m.deleted = 0 AND m.path = '/ai-assistant'
  AND NOT EXISTS (SELECT 1 FROM system_role_menu WHERE role_id = 2 AND menu_id = m.id AND deleted = 0);

INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 20015, 2, m.id, 'keelbase', now(), 'keelbase', now(), 0, 1
FROM system_menu m
WHERE m.deleted = 0 AND m.permission = 'ai1:session:my'
  AND NOT EXISTS (SELECT 1 FROM system_role_menu WHERE role_id = 2 AND menu_id = m.id AND deleted = 0);
