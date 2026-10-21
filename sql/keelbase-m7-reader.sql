-- SPDX-License-Identifier: Apache-2.0
--
-- M7 走查读者：一个**非管理员**角色 `m7_reader`、它的走查账号 `m7reader`，以及最小的菜单授予。
--
-- **为什么需要它。** 新权限码闸（`ab7ba3727d`）让 AI 写工具在**执行前**问 REST 路径问过的那个问题：
-- `crm:customer:create` / `crm:customer:update`。种子里的普通角色 `common`（role id 2）**没有**这两个码，
-- 于是 AI 新建客户会被扣下、批准后仍以结构化失败交回（理由点名那条码），Proxy-1 已实测。这与
-- `YD-走查包-M7_2026-10-19.md` 原先「完整走查用 `common`」的写法冲突。
--
-- **本文件不改 `common`。** 原则是「不给普通角色加写权」：`common` 继续做「无码读者」对照（它的 AI 写必须失败），
-- 走查另用一个**专用身份**。这正是 `audit_reader` 那一条的同一种做法——角色是独立身份，不混进普通用户。
--
-- **授予什么（最小集合）。**
--   1. CRM 客户那一支：`480`（CRM 系统）→ `474`（客户管理）→ `475`（查询）/ `476`（创建）/ `477`（更新）。
--      没有 `478` 删除、`479` 导出、`596` 导入，也没有公海、线索、合同等其它 CRM 页。
--   2. 助手：`20010`（隐藏父级）与 `20019`（`ai1:session:my`）。AI 写**只有经助手**才发得出去；这一个码是
--      助手抽屉四条调用方接口的同一个码，与 `common` 现在拿到的是同一条。
--   3. `common` 已有的那几个**按调用方限定范围**的读面：`20001`（治理目录）/ `20003`（AI 行动中心）/
--      `20004`（AI 写入台账）/ `20007`（决策轨迹）/ `20029`（治理边界）。走查的一页版步骤要用它们看证据；
--      服务端对每一页都按调用方收束，发给本角色不增加可达面。
--
-- **不授予什么。** 审计链 `20002`（运行时不答非管理员，设计如此——走查第 5 步用 `audit_reader` 账号）、
-- 操作审计 `20008`、工具清单 `20006`、策略中心 `20009`，以及全部系统管理 / 用户 / 租户 / 配置菜单。
--
-- **数据范围 5（仅本人）。** 本角色的客户页只看自己建的客户；AI 创建由 `CrmCustomerGovernance` 把属主盖成
-- 调用方，故刚建的那一行**看得见**。范围取最窄，误挂别的业务页也只会更窄、不会更宽。
--
-- **账号。** `m7reader` / `admin123`（复用上游 `admin` 的 bcrypt 哈希；上游默认口令，便于走查主持人交付）。
-- 它只持有 `m7_reader` 一个角色，**不是**管理员。
--
-- 只插、幂等：角色按 `code + tenant` 匹配，用户按 `username + tenant`，关系与授予按「角色 + 菜单」对匹配。
-- 依赖的菜单行：`480`–`477` 由上游 PG 脚本落下，`20001`/`20003`/`20004` 由
-- `keelbase-governance-menus.sql`，`20007` 由 `keelbase-trace-menu.sql`，`20029` 由
-- `keelbase-boundary-menu.sql`，`20010`/`20019` 由 `keelbase-assistant-role-grants.sql` 落下；
-- 故本文件必须排在它们之后（`seed-manifest.txt` 里紧接 `keelbase-audit-reader.sql`）。

-- M7 walkthrough reader: one **non-admin** role `m7_reader`, its walkthrough account `m7reader`, and the
-- smallest set of menu grants that lets that account complete the walk.
--
-- **Why it exists.** The new permission-code gate (`ab7ba3727d`) makes the AI write tools ask the same
-- question the REST path asks, **before execution**: `crm:customer:create` / `crm:customer:update`. The
-- seeded ordinary role `common` (role id 2) holds **neither** code, so an AI customer creation is held,
-- and after approval comes back as the runtime's structured failure (the reason names the code) — measured
-- by Proxy-1. That conflicts with the walkthrough package's earlier "full walk uses `common`".
--
-- **This file does not touch `common`.** The principle is "no write rights on the ordinary role": `common`
-- stays the **no-code reader** control (its AI write must keep failing), and the walk gets a **dedicated
-- identity** — the same shape `audit_reader` already uses. A role is a separate identity, not a wider
-- ordinary user.
--
-- **What is granted (the minimal set).**
--   1. The CRM customer branch: `480` (CRM) → `474` (customers) → `475` (query) / `476` (create) /
--      `477` (update). No `478` delete, `479` export, `596` import, and no sea-pool, lead or contract pages.
--   2. The assistant: `20010` (hidden parent) and `20019` (`ai1:session:my`). An AI write can only be
--      issued **through the assistant**; this one code is what the drawer's four caller-scoped routes ask
--      for, the same code `common` already holds.
--   3. The caller-scoped read faces `common` already has: `20001` (governance directory) / `20003` (action
--      centre) / `20004` (write ledger) / `20007` (conversation trace) / `20029` (governance boundary).
--      The one-page walk uses them to show evidence; each is scoped to the caller by the server, so handing
--      it to this role adds no reach.
--
-- **What is not granted.** The audit chain `20002` (the runtime refuses it to every non-administrator by
-- design — step 5 of the walk uses the `audit_reader` account), the operations audit `20008`, the tool
-- inventory `20006`, the policy centre `20009`, and every system / user / tenant / configuration menu.
--
-- **Data scope 5 (own rows).** The customer page shows only what this reader created; `CrmCustomerGovernance`
-- stamps the caller as owner on the AI create path, so the row just created **is** visible. The narrowest
-- scope means a business page attached by mistake can only narrow it further.
--
-- **The account.** `m7reader` / `admin123` (reusing upstream `admin`'s bcrypt hash; upstream's default
-- password, so a moderator can hand it over). It holds `m7_reader` and nothing else — **not** an administrator.
--
-- Inserts only, idempotent: the role matches on `code + tenant`, the user on `username + tenant`, the
-- relationships and grants on the role-and-menu pair. Menu rows it depends on: `480`–`477` come from the
-- upstream PG script; `20001`/`20003`/`20004` from `keelbase-governance-menus.sql`; `20007` from
-- `keelbase-trace-menu.sql`; `20029` from `keelbase-boundary-menu.sql`; `20010`/`20019` from
-- `keelbase-assistant-role-grants.sql`. This file therefore runs after all of them (immediately after
-- `keelbase-audit-reader.sql` in `seed-manifest.txt`).

-- 1) 角色本身：自定义角色（type 2），不是管理员。
-- 1) The role itself: a custom role (type 2), not an administrator.
INSERT INTO system_role (id, name, code, sort, data_scope, data_scope_dept_ids, status, type, remark,
                         creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 20050, '走查读者', 'm7_reader', 91, 5, '', 0, 2,
       'M7 走查读者：可建/改自己的客户，无删除、导出、用户、配置与审计管理',
       'keelbase', now(), 'keelbase', now(), 0, 1
WHERE NOT EXISTS (SELECT 1 FROM system_role WHERE code = 'm7_reader' AND tenant_id = 1 AND deleted = 0);

-- 2) 走查账号。口令哈希与上游 `admin` 相同（即上游默认口令 `admin123`）；只在本角色下使用。
-- 2) The walkthrough account. The password hash is upstream `admin`'s (i.e. upstream's default
--    `admin123`); it is used only under this role.
INSERT INTO system_users (id, username, password, nickname, remark, dept_id, post_ids, email, mobile,
                          sex, avatar, status, login_ip, login_date, creator, create_time, updater,
                          update_time, deleted, tenant_id)
SELECT 9001, 'm7reader', '$2a$04$.vd8nPeLwxt6hnSzmAoAyul8BOLX7Cib6QhcxRe30rfvrIPQHH1OG',
       '走查读者', 'M7 走查专用非管理员账号', 100, '[]', '', '', 0, '', 0, '', NULL,
       'keelbase', now(), 'keelbase', now(), 0, 1
WHERE NOT EXISTS (SELECT 1 FROM system_users WHERE username = 'm7reader' AND tenant_id = 1 AND deleted = 0);

-- 3) 账号 ↔ 角色。只挂 `m7_reader` 一个角色。
-- 3) Account ↔ role. `m7_reader` is the only role it carries.
INSERT INTO system_user_role (id, user_id, role_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 20070, u.id, r.id, 'keelbase', now(), 'keelbase', now(), 0, 1
FROM system_users u, system_role r
WHERE u.username = 'm7reader' AND u.tenant_id = 1 AND u.deleted = 0
  AND r.code = 'm7_reader' AND r.tenant_id = 1 AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM system_user_role ur
                  WHERE ur.user_id = u.id AND ur.role_id = r.id AND ur.deleted = 0);

-- 4) CRM 系统目录（`/crm`）—— 顶级菜单，客户页挂在它下面。
-- 4) The CRM directory (`/crm`) — the top-level menu the customer page hangs from.
INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 20051, r.id, 480, 'keelbase', now(), 'keelbase', now(), 0, 1
FROM system_role r
WHERE r.code = 'm7_reader' AND r.tenant_id = 1 AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM system_role_menu rm
                  WHERE rm.role_id = r.id AND rm.menu_id = 480 AND rm.deleted = 0);

-- 5) 客户管理页（`474`）—— 客户列表页本身。
-- 5) The customer page (`474`) — the list page itself.
INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 20052, r.id, 474, 'keelbase', now(), 'keelbase', now(), 0, 1
FROM system_role r
WHERE r.code = 'm7_reader' AND r.tenant_id = 1 AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM system_role_menu rm
                  WHERE rm.role_id = r.id AND rm.menu_id = 474 AND rm.deleted = 0);

-- 6) 客户查询（`475`，`crm:customer:query`）—— 列表与详情要它，走查要在页面上看见新建的那一行。
-- 6) Customer query (`475`, `crm:customer:query`) — the list and detail need it; the walk uses the page
--    to see the row that was just created.
INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 20053, r.id, 475, 'keelbase', now(), 'keelbase', now(), 0, 1
FROM system_role r
WHERE r.code = 'm7_reader' AND r.tenant_id = 1 AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM system_role_menu rm
                  WHERE rm.role_id = r.id AND rm.menu_id = 475 AND rm.deleted = 0);

-- 7) 客户创建（`476`，`crm:customer:create`）—— 新闸在工具执行前要的码。
-- 7) Customer create (`476`, `crm:customer:create`) — the code the new gate asks for before the tool runs.
INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 20054, r.id, 476, 'keelbase', now(), 'keelbase', now(), 0, 1
FROM system_role r
WHERE r.code = 'm7_reader' AND r.tenant_id = 1 AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM system_role_menu rm
                  WHERE rm.role_id = r.id AND rm.menu_id = 476 AND rm.deleted = 0);

-- 8) 客户更新（`477`，`crm:customer:update`）—— 更新工具的那条码，与 REST 同判据。
-- 8) Customer update (`477`, `crm:customer:update`) — the update tool's code, same test as REST.
INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 20055, r.id, 477, 'keelbase', now(), 'keelbase', now(), 0, 1
FROM system_role r
WHERE r.code = 'm7_reader' AND r.tenant_id = 1 AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM system_role_menu rm
                  WHERE rm.role_id = r.id AND rm.menu_id = 477 AND rm.deleted = 0);

-- 9) 助手父级（`20010`）与 `ai1:session:my`（`20019`）—— AI 写只能经助手发起。
-- 9) The assistant parent (`20010`) and `ai1:session:my` (`20019`) — an AI write can only be issued
--    through the assistant.
INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 20056, r.id, 20010, 'keelbase', now(), 'keelbase', now(), 0, 1
FROM system_role r
WHERE r.code = 'm7_reader' AND r.tenant_id = 1 AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM system_role_menu rm
                  WHERE rm.role_id = r.id AND rm.menu_id = 20010 AND rm.deleted = 0);

INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 20057, r.id, 20019, 'keelbase', now(), 'keelbase', now(), 0, 1
FROM system_role r
WHERE r.code = 'm7_reader' AND r.tenant_id = 1 AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM system_role_menu rm
                  WHERE rm.role_id = r.id AND rm.menu_id = 20019 AND rm.deleted = 0);

-- 10) 治理目录（`20001`）—— 下面四个读面的父级，没有它子项无处可挂。
-- 10) The governance directory (`20001`) — parent of the four read faces below; without it the children
--     have nothing to hang from.
INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 20058, r.id, 20001, 'keelbase', now(), 'keelbase', now(), 0, 1
FROM system_role r
WHERE r.code = 'm7_reader' AND r.tenant_id = 1 AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM system_role_menu rm
                  WHERE rm.role_id = r.id AND rm.menu_id = 20001 AND rm.deleted = 0);

-- 11) AI 行动中心（`20003`）—— 调用方自己的待决项与已发生的写；服务端按调用方收束。
-- 11) Action centre (`20003`) — the caller's own pending items and writes; the server scopes it.
INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 20059, r.id, 20003, 'keelbase', now(), 'keelbase', now(), 0, 1
FROM system_role r
WHERE r.code = 'm7_reader' AND r.tenant_id = 1 AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM system_role_menu rm
                  WHERE rm.role_id = r.id AND rm.menu_id = 20003 AND rm.deleted = 0);

-- 12) AI 写入台账（`20004`）—— 调用方自己的副作用；服务端按调用方收束。
-- 12) Write ledger (`20004`) — the caller's own effects; the server scopes it.
INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 20060, r.id, 20004, 'keelbase', now(), 'keelbase', now(), 0, 1
FROM system_role r
WHERE r.code = 'm7_reader' AND r.tenant_id = 1 AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM system_role_menu rm
                  WHERE rm.role_id = r.id AND rm.menu_id = 20004 AND rm.deleted = 0);

-- 13) 决策轨迹（`20007`）—— 调用方自己的会话回合；服务端按调用方收束。
-- 13) Conversation trace (`20007`) — the caller's own conversation turns; the server scopes it.
INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 20061, r.id, 20007, 'keelbase', now(), 'keelbase', now(), 0, 1
FROM system_role r
WHERE r.code = 'm7_reader' AND r.tenant_id = 1 AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM system_role_menu rm
                  WHERE rm.role_id = r.id AND rm.menu_id = 20007 AND rm.deleted = 0);

-- 14) 治理边界（`20029`）—— 构建事实页，走查的入口。
-- 14) Governance boundary (`20029`) — the build-facts page, the walk's entry.
INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 20062, r.id, 20029, 'keelbase', now(), 'keelbase', now(), 0, 1
FROM system_role r
WHERE r.code = 'm7_reader' AND r.tenant_id = 1 AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM system_role_menu rm
                  WHERE rm.role_id = r.id AND rm.menu_id = 20029 AND rm.deleted = 0);
