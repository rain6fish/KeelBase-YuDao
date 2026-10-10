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

-- Kept: 首页 has no path (it is the root), plus the four module roots this deployment runs.
-- 保留：首页没有 path（它是根），加上本部署运行的四个模块根。
with recursive off as (
    select id
      from system_menu
     where deleted = 0
       and parent_id = 0
       and coalesce(path, '') not in ('', '/system', '/infra', '/crm')
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
