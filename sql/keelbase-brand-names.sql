-- KeelBase: the names this deployment shows, in place of the platform vendor's.
--
-- Run this against an existing database after upstream's seed has been applied; a fresh install runs
-- `sql/postgresql/ruoyi-vue-pro.sql` and then this. Upstream's seed file is left untouched — it is
-- upstream's, it is several thousand lines, and it is duplicated across dialects, so a rename belongs
-- in a file of ours that says what it changes rather than as edits scattered through theirs.
--
-- Idempotent: every statement matches on the old value, so a second run changes nothing.
--
-- KeelBase：本部署显示的名字，替换掉平台厂商的。
--
-- 已有库在上游种子之后跑它；新装则先跑 `sql/postgresql/ruoyi-vue-pro.sql`，再跑本文件。上游那份种子**一字不改**
-- ——它是上游的、几千行、且在多个方言间重复；改名该放在一份**说明它改了什么**的我们自己的文件里，而不是散落在
-- 他们那份里的编辑。
--
-- 幂等：每条语句都按旧值匹配，故第二次跑什么都不改。

-- The tenant is what the login form asks for by name, and what the top bar shows.
-- 租户名是登录时按名字要的那个，也是顶栏显示的那个。
UPDATE system_tenant SET name = 'KeelBase' WHERE name = '芋道源码';

-- The seeded administrator's display name, shown in the top bar and in the greeting.
-- 内置管理员的显示名，出现在顶栏与首页问候语里。
UPDATE system_users SET nickname = 'KeelBase' WHERE username = 'admin' AND nickname = '芋道源码';

-- 侧边栏那条 AI 分组菜单带的是厂商自己的「AI」标记，本部署显示机器人——与顶栏的助手入口同一个图标。
-- The AI group in the sidebar carries the vendor's own "AI" mark; this deployment shows the robot, the
-- same icon the header's assistant entry uses.
-- 按旧值匹配：跑第二次时已经没有行可命中，幂等；一条语句同时盖住所有仍用旧图标的菜单行。
-- Matched on the old value, so a second run finds nothing to change; one statement covers every menu
-- row still carrying the old icon.
UPDATE system_menu SET icon = 'ri:robot-2-line' WHERE icon = 'tabler:ai' AND deleted = 0;
