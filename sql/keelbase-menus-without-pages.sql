-- SPDX-License-Identifier: Apache-2.0
--
-- A menu that asks to route to something that was never a page.
--
-- Upstream's seed carries `项目分组` (`pms/pm/project/list/components/group/index`). No such file
-- exists: what sits in that directory is `ProjectGroupList.vue`, and that is a *dialog* — the project
-- list page renders it inline and opens it from its own button, which is why that page has a
-- 个人分组 control of its own. So the menu produces a route whose page is empty: no error, no content.
--
-- Two things it must not do, both learned by trying the obvious move first. Turning the menu off is
-- not enough — its four children are `type = 3` rows, i.e. the button permissions
-- (`pms:pm:project-group:create` and friends) that the dialog checks with `v-hasPermi`, and a
-- disabled parent takes them out of the permission list with it. And clearing the `component` is not
-- enough either — the row still routes, still to nothing.
--
-- What is actually true: those four permissions belong to `我的项目`, because that is where the
-- dialog lives. So they are re-parented there, and only then is the bogus row switched off — the
-- permission strings are untouched, the tree stops claiming a page that does not exist, and the dead
-- route goes away.
--
-- Idempotent: both statements match on the current shape, so a second run changes nothing.
--
-- 一条要求路由到一个**从来不是页面**的东西上的菜单。
--
-- 上游种子里带着 `项目分组`（`pms/pm/project/list/components/group/index`）。**没有这个文件**：那个目录里
-- 放的是 `ProjectGroupList.vue`，而它是一个**弹窗**——项目列表页把它内嵌渲染、由自己的按钮打开，这也是那个
-- 页面**自带**「个人分组」控件的原因。于是这条菜单产出一条**页面为空的**路由：不报错、也没有内容。
--
-- 有两件事它不能做，都是先把最显眼那步试过之后学到的。**只把菜单关掉不够**——它的四个子行是 `type = 3`，
-- 也就是弹窗用 `v-hasPermi` 检查的那些**按钮权限**（`pms:pm:project-group:create` 等），而父菜单被停用会把
-- 它们一并带出权限清单。**只把 `component` 清掉也不够**——那一行照样路由，照样路由到空。
--
-- 而**真正成立**的是：那四条权限属于 `我的项目`，因为弹窗就住在那里。故先把它们改挂过去，**然后**才把那行
-- 关掉——权限字符串一字未动，菜单树不再声称有一个并不存在的页面，那条死路由随之消失。
--
-- 幂等：两条语句都按**当前形状**匹配，重跑不改动任何东西。

-- 1) The four button permissions move to the page that actually uses them.
-- 1) 那四条按钮权限改挂到**真正使用它们**的那个页面上。
update system_menu
   set parent_id = (select id from system_menu where path = 'project/list' and deleted = 0)
 where deleted = 0
   and type = 3
   and parent_id = (select id from system_menu where component = 'pms/pm/project/list/components/group/index' and deleted = 0);

-- 2) And the row that named a non-page is switched off, so it routes nowhere.
-- 2) 而那一行——它点名的东西不是页面——被关掉，于是它不路由到任何地方。
update system_menu
   set status = 1
 where deleted = 0
   and status <> 1
   and component = 'pms/pm/project/list/components/group/index';
