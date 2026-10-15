-- SPDX-License-Identifier: Apache-2.0
--
-- AI 助手（`yudao-module-ai1-springai`）的管理台：一个目录，加它下面**七页**。
--
-- **「模型」不在这七页里，是有意的。** 上游把模型放在**某条供应商之下**（`provider/model/:providerId`），
-- `ai1/model/index.vue` 一挂载就要 `providerId`，没有就提示并弹回供应商列表——把它当顶级页挂上去，结果是
-- 点进去立刻被弹走、还带一句「参数错误」。配置模型的路径因此是：**供应商 → 点开那一行 → 模型列表**。
-- （前端本来就有 `router/modules/remaining.ts` 注册那条详情路由。）
--
-- The assistant module's console: one directory and the **seven** pages under it.
--
-- **「模型」 is deliberately not one of the seven.** Upstream puts a model **under a provider**
-- (`provider/model/:providerId`): `ai1/model/index.vue` demands a `providerId` on mount and bounces back
-- to the provider list without one, so mounting it at the top level means entering it and being thrown out
-- again, with a 「参数错误」 toast for company. Configuring a model therefore goes: **供应商 → open that
-- row → the model list.** (The frontend already registers that detail route in `router/modules/remaining.ts`.)
--
-- **行，不是路由。** 本宿主的菜单树由 `system_menu` 建，`.vue` 文件放进检出只是「已编译但没人列得出来」——
-- 上游给这个模块发了控制器与页面，却**没发任何菜单行**，所以在此之前这八页在界面上一个都到不了。
--
-- 挂出来是为了回答一个具体问题：**助手跑在哪个模型上**。模型与密钥住在 `ai1_provider` / `ai1_model`
-- 两行里（`ai1_agent` 再指向其中一行），而在这份文件之前，改它们只能走接口或直接改库——界面里没有入口。
--
-- **不挂权限字符串，也不加角色授权**：与 `keelbase-governance-menus.sql` 同形，看到什么由宿主自己的规则
-- 决定——`super_admin` 看得到每一页，其他人只看得到被授权的。接口那侧照旧由 `ai1:*` 把关，所以「看得到菜单」
-- 不等于「调得动接口」。
--
-- **只插自己号段的行**（20020–20028；上游在本表用到的最大 id 是 12939，本线从 20001 起）。不碰任何既有行。
--
-- 幂等：目录按 id 判在不在，八页各自按**自己的 component 路径**判——重复跑什么都不改；上游哪天挪了 id，
-- 再跑仍找得到同样这八行。
--
-- The assistant module's console: one directory and the eight pages under it.
--
-- Rows, not routes. This host builds its menu tree from `system_menu`, so dropping the `.vue` files into the
-- checkout only gets them compiled — upstream ships this module's controllers and pages but **no menu rows
-- at all**, which is why none of the eight was reachable before this file.
--
-- It exists to answer one question: **which model the assistant runs on.** The model and the key live in the
-- `ai1_provider` / `ai1_model` rows (`ai1_agent` points at one of them), and before this file the only way to
-- change either was the API or the database — the console had no entry for it.
--
-- No permission string and no role grant, same shape as `keelbase-governance-menus.sql`: what a caller sees
-- is the host's own rule — `super_admin` sees every page, anyone else only what their roles were granted.
-- The `ai1:*` checks still guard the API, so seeing the page is not the same as being able to call it.
--
-- Only ids from this line's own range (20020–20028; upstream's highest id here is 12939, this line starts at
-- 20001). No existing row is touched.
--
-- Idempotent: the directory is matched by id and each page by **its own component path**, so a second run
-- changes nothing and a later shift of upstream's ids still finds the same eight rows.

-- 1) 目录。
-- 1) The directory.
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 20020, 'AI 助手', '', 1, 400, 0, '/ai1', 'ri:robot-2-line', NULL, NULL, 0, '1', '1', '1', 'keelbase', now(), 'keelbase', now(), '0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 20020);

-- 2) 七页。`path` 是相对的——前缀由目录给。`component` 写全 `.vue`：前端按**子串**匹配检出的文件
--    （`routerHelper.ts` 的 `modules.findIndex(ev => ev.includes(component))`），写全名才不会误中同名的兄弟。
-- 2) The seven pages. `path` is relative — the directory supplies the prefix. `component` carries the full
--    `.vue`: the frontend matches it as a **substring** against the checkout's files
--    (`routerHelper.ts`'s `modules.findIndex(ev => ev.includes(component))`), so the full name is what keeps
--    it from landing on a same-named sibling.
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 20021, '概览', '', 2, 1, 20020, 'home', 'ep:data-line', 'ai1/home/index.vue', 'Ai1Home', 0, '1', '1', '1', 'keelbase', now(), 'keelbase', now(), '0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE component = 'ai1/home/index.vue');

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 20022, '供应商', '', 2, 2, 20020, 'provider', 'ep:connection', 'ai1/model/provider/index.vue', 'Ai1Provider', 0, '1', '1', '1', 'keelbase', now(), 'keelbase', now(), '0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE component = 'ai1/model/provider/index.vue');

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 20024, '智能体', '', 2, 3, 20020, 'agent', 'ep:magic-stick', 'ai1/agent/index.vue', 'Ai1Agent', 0, '1', '1', '1', 'keelbase', now(), 'keelbase', now(), '0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE component = 'ai1/agent/index.vue');

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 20025, '知识库', '', 2, 4, 20020, 'knowledge', 'ep:notebook', 'ai1/knowledge/index.vue', 'Ai1Knowledge', 0, '1', '1', '1', 'keelbase', now(), 'keelbase', now(), '0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE component = 'ai1/knowledge/index.vue');

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 20026, 'MCP', '', 2, 5, 20020, 'mcp', 'ep:link', 'ai1/mcp/index.vue', 'Ai1Mcp', 0, '1', '1', '1', 'keelbase', now(), 'keelbase', now(), '0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE component = 'ai1/mcp/index.vue');

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 20027, '技能', '', 2, 6, 20020, 'skill', 'ep:star', 'ai1/skill/index.vue', 'Ai1Skill', 0, '1', '1', '1', 'keelbase', now(), 'keelbase', now(), '0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE component = 'ai1/skill/index.vue');

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 20028, '会话', '', 2, 7, 20020, 'session', 'ep:chat-dot-round', 'ai1/session/index.vue', 'Ai1Session', 0, '1', '1', '1', 'keelbase', now(), 'keelbase', now(), '0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE component = 'ai1/session/index.vue');
