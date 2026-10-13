-- SPDX-License-Identifier: Apache-2.0
--
-- The showcase's menu row — under the same 治理 directory as the three console pages.
--
-- No grant, deliberately: the showcase drives the runtime as whoever asks for it, so running it is
-- an administrator's action and the routes refuse anyone else. Handing it to the ordinary role would
-- advertise a page that can only refuse. That is the same choice the audit chain got, and for the
-- same reason.
--
-- Idempotent, and only inserts: matches on its own component path, and uses an id outside the range
-- upstream uses.
--
-- 展示那条菜单行——挂在与三个控制台页面**同一个**「治理」目录下。
--
-- **刻意不授予**：这个展示是**以请求者自己的身份**驱动运行时的，故跑它属于管理员动作，而路由对别人
-- 一律拒绝。把它发给普通角色，等于**推荐一个只会拒绝的页面**。这与审计链拿到的处置相同，理由也相同。
--
-- 幂等，且只插入：按它自己的组件路径匹配，并用一个上游不用的号段。

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 20005, '对抗证明', '', 2, 4, 20001, 'security-showcase', 'ep:aim', 'keelbase/governance/SecurityShowcasePage', 'KeelbaseGovernanceShowcase', 0, '1', '1', '1', 'keelbase', now(), 'keelbase', now(), '0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE component = 'keelbase/governance/SecurityShowcasePage');
