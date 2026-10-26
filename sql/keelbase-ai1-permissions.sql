-- SPDX-License-Identifier: Apache-2.0
--
-- AI 助手管理台的**按钮权限**：把 `ai1:*` 的权限行种上，并只发给 `super_admin`。
--
-- **为什么需要这一份。** `keelbase-assistant-menus.sql` 把 20020–20028 七页挂了出来，但**没有种任何权限
-- 字符串**；而那几页的每个「新增 / 修改 / 删除 / 测试 / 模型」按钮都挂着 `v-hasPermi`（例如
-- `ai1:provider:create`）。前端指令读的是 `get-permission-info` 返回的**权限串清单**，那条清单由
-- `system_role_menu` → `system_menu.permission` 拼出来；一行都没有的码，就把按钮从 DOM 里摘掉。
--
-- **超管也躲不过去，而这是两套规则的分界。** 菜单树对 `super_admin` 是**绕过授予**的——本部署实测
-- `system_role_menu` 里角色 1 在 20010–20029 上一行都没有，侧栏照样显示这些页；但**权限串清单不绕过**，
-- 它是按被授予的菜单行数的。服务端 `@PreAuthorize` 另有超管兜底（`PermissionServiceImpl.hasAnyPermissions`
-- 的「情况二」），故读接口本来就能过——于是修前是一种**看着能进、按钮全没**的形态：页面打得开、列表有数据、
-- 一个“新增”都没有。这正是本文件要补的那一行。
--
-- **发给了谁。** 只发给 `super_admin`（role id 1，本部署的管理员角色）。管理台配置的是**本部署自己的**
-- provider / model / agent / 知识库 / MCP / 技能，不是某个用户的业务面：故**不发给 `common`**，也不发给
-- `audit_reader` / `m7_reader`——那两个是只读读者，拿到这些码等于把「谁能配模型与密钥」放开了。
-- 其它管理员角色（如 `tenant_admin`）若将来要配，应当**单独**在这里加授予，而不是靠本文件顺手发出去。
--
-- **模型页没有独立菜单。** 它挂在某条供应商之下（`provider/model/:providerId`，见
-- `keelbase-assistant-menus.sql` 的说明），故 `ai1:model:*` 五条按钮行挂在**供应商页** 20022 下；
-- 供应商行上的「模型」按钮正是靠 `ai1:model:query` 出现的。
--
-- **只插、幂等**：按钮行按 permission 串判在不在，授予按「角色 + 菜单」对判。父级按 **component 路径**
-- 取（不是写死 id），故上游哪天挪了 id，再跑仍找得到同一页。
--
-- The AI assistant console's **button permissions**: seed the `ai1:*` rows and hand them to
-- `super_admin` only.
--
-- **Why this file exists.** `keelbase-assistant-menus.sql` lays the seven pages 20020–20028 but seeds **no
-- permission strings**; every "create / update / delete / test / models" button on them carries
-- `v-hasPermi` (e.g. `ai1:provider:create`). The directive reads the **permission list** returned by
-- `get-permission-info`, which is assembled from `system_role_menu` → `system_menu.permission`; a code with
-- no row removes its button from the DOM.
--
-- **The super administrator does not escape this, and that is where the two rules part.** The menu tree
-- **bypasses grants** for `super_admin` — measured here, role 1 has no row at all on 20010–20029 and the
-- sidebar still shows those pages; the **permission list does not bypass**, it is counted from granted menu
-- rows. The server side has its own super-admin fallback (`PermissionServiceImpl.hasAnyPermissions`, its
-- "case two"), which is why the read APIs already answered — so the pre-fix shape was **pages open, list
-- populated, not one "create" button**. That is the row this file adds.
--
-- **Who gets it.** Only `super_admin` (role id 1, this deployment's administrator role). What these pages
-- configure — provider / model / agent / knowledge / MCP / skill — belongs to **this deployment**, not to a
-- user's business face: so **not `common`**, and not `audit_reader` / `m7_reader` either — handing those two
-- read-only readers these codes would open up who may configure models and keys. Any other administrator
-- role (e.g. `tenant_admin`) that should configure this gets its own grant here, deliberately, rather than
-- riding along on this one.
--
-- **The model page has no menu of its own.** It hangs under a provider (`provider/model/:providerId`, see the
-- note in `keelbase-assistant-menus.sql`), so the five `ai1:model:*` button rows hang under the **provider
-- page** 20022; the "models" button on a provider row is what `ai1:model:query` brings back.
--
-- **Inserts only, idempotent**: a button row matches on its permission string, a grant on the
-- role-and-menu pair. The parent is found by **component path** (not a hard-coded id), so a later shift of
-- upstream's ids still finds the same page.

-- 1) 按钮行（`type = 3`，永不渲染）。父级按 component 路径取，故不依赖具体 id。
-- 1) The button rows (`type = 3`, never rendered). The parent is found by component path, not by a fixed id.
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT v.id, v.name, v.permission, 3, v.sort, p.id, '', '', '', NULL,
       0, '0', '1', '1', 'keelbase', now(), 'keelbase', now(), '0'
FROM (VALUES
    (20030, '概览查询',       'ai1:home:query',                    'ai1/home/index.vue',              1),
    (20031, '供应商查询',     'ai1:provider:query',                'ai1/model/provider/index.vue',     1),
    (20032, '供应商新增',     'ai1:provider:create',               'ai1/model/provider/index.vue',     2),
    (20033, '供应商修改',     'ai1:provider:update',               'ai1/model/provider/index.vue',     3),
    (20034, '供应商删除',     'ai1:provider:delete',               'ai1/model/provider/index.vue',     4),
    (20035, '供应商测试',     'ai1:provider:test',                 'ai1/model/provider/index.vue',     5),
    (20036, '模型查询',       'ai1:model:query',                   'ai1/model/provider/index.vue',     6),
    (20037, '模型新增',       'ai1:model:create',                  'ai1/model/provider/index.vue',     7),
    (20038, '模型修改',       'ai1:model:update',                  'ai1/model/provider/index.vue',     8),
    (20039, '模型删除',       'ai1:model:delete',                  'ai1/model/provider/index.vue',     9),
    (20040, '模型导入',       'ai1:model:import',                  'ai1/model/provider/index.vue',    10),
    (20041, '智能体查询',     'ai1:agent:query',                   'ai1/agent/index.vue',              1),
    (20042, '智能体新增',     'ai1:agent:create',                  'ai1/agent/index.vue',              2),
    (20043, '智能体修改',     'ai1:agent:update',                  'ai1/agent/index.vue',              3),
    (20044, '智能体删除',     'ai1:agent:delete',                  'ai1/agent/index.vue',              4),
    (20045, '知识库查询',     'ai1:knowledge:query',               'ai1/knowledge/index.vue',          1),
    (20046, '知识库新增',     'ai1:knowledge:create',              'ai1/knowledge/index.vue',          2),
    (20047, '知识库修改',     'ai1:knowledge:update',              'ai1/knowledge/index.vue',          3),
    (20048, '知识库删除',     'ai1:knowledge:delete',              'ai1/knowledge/index.vue',          4),
    (20049, '知识库检索',     'ai1:knowledge:search',              'ai1/knowledge/index.vue',          5),
    (20050, '文档查询',       'ai1:knowledge-document:query',      'ai1/knowledge/index.vue',          6),
    (20051, '文档新增',       'ai1:knowledge-document:create',     'ai1/knowledge/index.vue',          7),
    (20052, '文档修改',       'ai1:knowledge-document:update',     'ai1/knowledge/index.vue',          8),
    (20053, '文档删除',       'ai1:knowledge-document:delete',     'ai1/knowledge/index.vue',          9),
    (20054, '文档向量化',     'ai1:knowledge-document:vectorize',  'ai1/knowledge/index.vue',         10),
    (20055, 'MCP 查询',       'ai1:mcp:query',                     'ai1/mcp/index.vue',                1),
    (20056, 'MCP 新增',       'ai1:mcp:create',                    'ai1/mcp/index.vue',                2),
    (20057, 'MCP 修改',       'ai1:mcp:update',                    'ai1/mcp/index.vue',                3),
    (20058, 'MCP 删除',       'ai1:mcp:delete',                    'ai1/mcp/index.vue',                4),
    (20059, 'MCP 测试',       'ai1:mcp:test',                      'ai1/mcp/index.vue',                5),
    (20060, '技能查询',       'ai1:skill:query',                   'ai1/skill/index.vue',              1),
    (20061, '技能新增',       'ai1:skill:create',                  'ai1/skill/index.vue',              2),
    (20062, '技能修改',       'ai1:skill:update',                  'ai1/skill/index.vue',              3),
    (20063, '技能删除',       'ai1:skill:delete',                  'ai1/skill/index.vue',              4),
    (20064, '会话查询',       'ai1:session:query',                 'ai1/session/index.vue',            1)
) AS v(id, name, permission, component, sort)
JOIN system_menu p ON p.component = v.component AND p.deleted = 0
WHERE NOT EXISTS (
    SELECT 1 FROM system_menu m WHERE m.deleted = 0 AND m.permission = v.permission
);

-- 2) 只发给 `super_admin`（role 1）。关系行 id 由菜单 id 推出（20100 + id − 20000），故重复跑不会换号，
--    局部重跑也不会撞号。`ai1:session:my` 不在此列：它由 `keelbase-assistant-role-grants.sql` 发给
--    `common`，是抽屉那条**按调用方**的权限，与本文件的配置面不是一回事。
-- 2) Hand them to `super_admin` (role 1) only. The relation id is derived from the menu id
--    (20100 + id − 20000), so a rerun keeps the same ids and a partial rerun cannot collide.
--    `ai1:session:my` is not in this list: `keelbase-assistant-role-grants.sql` gives it to `common`, a
--    **caller-scoped** permission for the drawer, not this console's configuration surface.
INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 20100 + (m.id - 20000), 1, m.id, 'keelbase', now(), 'keelbase', now(), 0, 1
FROM system_menu m
WHERE m.deleted = 0
  AND m.permission IN (
      'ai1:home:query',
      'ai1:provider:query', 'ai1:provider:create', 'ai1:provider:update', 'ai1:provider:delete', 'ai1:provider:test',
      'ai1:model:query', 'ai1:model:create', 'ai1:model:update', 'ai1:model:delete', 'ai1:model:import',
      'ai1:agent:query', 'ai1:agent:create', 'ai1:agent:update', 'ai1:agent:delete',
      'ai1:knowledge:query', 'ai1:knowledge:create', 'ai1:knowledge:update', 'ai1:knowledge:delete', 'ai1:knowledge:search',
      'ai1:knowledge-document:query', 'ai1:knowledge-document:create', 'ai1:knowledge-document:update',
      'ai1:knowledge-document:delete', 'ai1:knowledge-document:vectorize',
      'ai1:mcp:query', 'ai1:mcp:create', 'ai1:mcp:update', 'ai1:mcp:delete', 'ai1:mcp:test',
      'ai1:skill:query', 'ai1:skill:create', 'ai1:skill:update', 'ai1:skill:delete',
      'ai1:session:query'
  )
  AND NOT EXISTS (
      SELECT 1 FROM system_role_menu r WHERE r.role_id = 1 AND r.menu_id = m.id AND r.deleted = 0
  );
