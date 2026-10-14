-- SPDX-License-Identifier: Apache-2.0
--
-- Which modules *this* deployment runs, and therefore which menus it shows.
--
-- The upstream seed ships the menus for every module it could carry. This host runs a subset — the
-- ones its jar actually has — so the rest have to go: a menu whose module is not compiled in answers
-- 「模块已禁用」, and a sidebar full of those reads as a broken deployment rather than as a small one.
--
-- This is a property of the deployment, not of the line, which is why it lives here and not in
-- `sql/`: another host that runs more modules should not inherit this list. The `status <> 1` guard
-- makes it idempotent, and it is written as "disable everything whose top-level path is not in the
-- list this deployment keeps" so that adding a module means editing one list.
--
-- 本**部署**跑哪些模块，因而显示哪些菜单。
--
-- 上游种子把它能带的模块的菜单**全**给了。这台机器只跑其中一部分——它的 jar 里真正有的那些——所以其余
-- 的必须收掉：模块没编译进来的菜单会答「模块已禁用」，而一整个侧栏都是这种东西，读起来是**部署坏了**，
-- 不是**部署小**。
--
-- 这是**部署**的属性、不是这条线的属性，所以它住在这里而不在 `sql/`：另一台跑更多模块的机器不该继承
-- 这份清单。`status <> 1` 的守卫让它幂等；写法是「把顶级路径不在本部署保留清单里的一律禁用」，于是**加
-- 一个模块＝改一处清单**。

-- 保留：首页没有 path（它是根），加上本部署运行的三个模块根（`/system` · `/infra` · `/crm`），
-- 以及治理菜单区的那个顶级路径（`/keelbase-governance`）。
--
-- ⚠ **治理区必须在清单里**：它是「本部署展示什么」的一部分，而不属于任何一个 yudao 模块 —— 它的页面由
-- `keelbase-ui/host-overlay/` 铺进产物、菜单行由 `sql/keelbase-*-menu.sql` 落库（**不在** `bootstrap-db.sh`
-- 的 `ADDITIONS` 里，得单独落）。漏掉它，后果是**日后跑一次引导就把整个治理菜单区静默收掉** —— 那次引导
-- 不会报任何错，`status <> 1` 的守卫只是把它当成了「本部署不跑的模块」。
--
-- Kept: 首页 has no path (it is the root), plus the three module roots this deployment runs
-- (`/system` · `/infra` · `/crm`), plus the one top-level path the governance menu area sits under
-- (`/keelbase-governance`).
--
-- ⚠ The governance area **has** to be on this list: it is part of *what this deployment shows*, and it
-- belongs to no yudao module — its pages are laid into the artefact from `keelbase-ui/host-overlay/`,
-- and its menu rows come from `sql/keelbase-*-menu.sql` (**not** from the `ADDITIONS` list in
-- `bootstrap-db.sh`; they are applied on their own). Leaving it out means a later bootstrap **silently
-- switches the whole governance area off** — that bootstrap reports nothing wrong, because the
-- `status <> 1` guard simply reads it as a module this deployment does not run.
with recursive off as (
    select id
      from system_menu
     where deleted = 0
       and parent_id = 0
       and coalesce(path, '') not in ('', '/system', '/infra', '/crm', '/keelbase-governance')
    union all
    select m.id
      from system_menu m
      join off o on m.parent_id = o.id
     where m.deleted = 0
)
update system_menu
   set status = 1
 where id in (select id from off)
   and status <> 1;
