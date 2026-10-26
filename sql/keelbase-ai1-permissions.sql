-- SPDX-License-Identifier: Apache-2.0
--
-- AI 助手管理台的**按钮权限**：把 `ai1:*` 的权限行种上。
--
-- **为什么需要这一份。** `keelbase-assistant-menus.sql` 把 20020–20028 七页挂了出来，但**没有种任何权限
-- 字符串**；而那几页的每个「新增 / 修改 / 删除 / 测试 / 模型」按钮都挂着 `v-hasPermi`（例如
-- `ai1:provider:create`）。前端指令读的是 `get-permission-info` 返回的**权限串清单**：超管那份是**全部
-- 菜单**的权限串（`getRoleMenuListByRoleId` 对超管返回全部菜单），其它角色是**被授予菜单**的那一份。
-- 无论哪一份，`system_menu` 里没有那一行，就都没有这个码——而本部署此前只有一条 `ai1:session:my`。
--
-- **超管也躲不过去，而这是两套规则的分界。** 菜单树对 `super_admin` 是**绕过授予**的——本部署实测
-- `system_role_menu` 里角色 1 在 20010–20029 上一行都没有，侧栏照样显示这些页；但权限串清单要**先有那一行**
-- 才拼得出来。服务端 `@PreAuthorize` 另有超管兜底（`PermissionServiceImpl.hasAnyPermissions` 的「情况二」），
-- 故读接口本来就能过——于是修前是一种**看着能进、按钮全没**的形态：页面打得开、列表有数据、一个“新增”都没有。
-- 本文件补的就是这些行。
--
-- **谁看得到。** 只对 `super_admin` 生效：它对菜单树是全量读取，故行一存在就进它的权限串清单。
-- 本文件**不加任何角色授予**——`tenant_admin`、`common`、`audit_reader`、`m7_reader` 继续看不到这些按钮，
-- 正是想要的（配置 provider / 模型 / 密钥是管理员的活，只读读者不该拿到）。将来若要让某个角色也能配，
-- 应当**单独**加 `system_role_menu` 授予，而不是靠本文件顺手发出去。
-- `ai1:session:my` 不在本文件里：它由 `keelbase-assistant-role-grants.sql` 发给 `common`，是抽屉那条
-- **按调用方**的权限，与本文件的配置面不是一回事。
--
-- **模型页没有独立菜单。** 它挂在某条供应商之下（`provider/model/:providerId`，见
-- `keelbase-assistant-menus.sql` 的说明），故 `ai1:model:*` 五条按钮行挂在**供应商页** 20022 下；
-- 供应商行上的「模型」按钮正是靠 `ai1:model:query` 出现的。
--
-- **只插、幂等**：按钮行按 permission 串判在不在，父级按 **component 路径**取（不是写死 id），故上游哪天
-- 挪了 id，再跑仍找得到同一页。
--
-- The AI assistant console's **button permissions**: seed the `ai1:*` rows.
--
-- **Why this file exists.** `keelbase-assistant-menus.sql` lays the seven pages 20020–20028 but seeds **no
-- permission strings**; every "create / update / delete / test / models" button on them carries
-- `v-hasPermi` (e.g. `ai1:provider:create`). The directive reads the **permission list** returned by
-- `get-permission-info`: for the super administrator that is **every menu's** permission string
-- (`getRoleMenuListByRoleId` returns all menus for a super admin), for any other role it is the **granted**
-- set. Either way, a code with no `system_menu` row cannot appear — and this deployment had exactly one
-- `ai1:session:my` row.
--
-- **The super administrator does not escape this, and that is where the two rules part.** The menu tree
-- **bypasses grants** for `super_admin` — measured here, role 1 has no row at all on 20010–20029 and the
-- sidebar still shows those pages; the permission list still needs **the row** to be assembled. The server
-- side has its own super-admin fallback (`PermissionServiceImpl.hasAnyPermissions`, its "case two"), which is
-- why the read APIs already answered — so the pre-fix shape was **pages open, list populated, not one
-- "create" button**. These rows are what this file adds.
--
-- **Who sees it.** Only `super_admin`, which reads the whole menu tree, so the rows land in its permission
-- list as soon as they exist. This file adds **no role grant** — `tenant_admin`, `common`, `audit_reader` and
-- `m7_reader` keep seeing no buttons, which is the intent (configuring a provider, a model or a key is an
-- administrator's job, not a read-only reader's). If another role should configure this later, give it its
-- own `system_role_menu` grant deliberately rather than riding along on this file.
-- `ai1:session:my` is not here: `keelbase-assistant-role-grants.sql` gives it to `common`, a **caller-scoped**
-- permission for the drawer, not this console's configuration surface.
--
-- **The model page has no menu of its own.** It hangs under a provider (`provider/model/:providerId`, see the
-- note in `keelbase-assistant-menus.sql`), so the five `ai1:model:*` button rows hang under the **provider
-- page** 20022; the "models" button on a provider row is what `ai1:model:query` brings back.
--
-- **Inserts only, idempotent**: a button row matches on its permission string, and the parent is found by
-- **component path** (not a hard-coded id), so a later shift of upstream's ids still finds the same page.

-- 按钮行（`type = 3`，永不渲染）。父级按 component 路径取，故不依赖具体 id。
-- The button rows (`type = 3`, never rendered). The parent is found by component path, not by a fixed id.
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
