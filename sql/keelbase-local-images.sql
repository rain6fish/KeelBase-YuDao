-- SPDX-License-Identifier: Apache-2.0
--
-- KeelBase: the demo's own data stops pointing at the platform vendor's file server.
--
-- Upstream's seed ships pictures whose URLs live on `test.yudao.iocoder.cn` — the admin user's avatar,
-- a maintenance notice's body, some OAuth2 client logos. They are upstream's demo assets, and a
-- deployment that keeps them asks every *visitor* to fetch images from a host we do not control: on a
-- network that cannot reach it the images fail, and the page looks broken for reasons that have
-- nothing to do with this deployment. So these rows are pointed at what this host serves itself.
--
-- Idempotent: every statement matches on the foreign host, so a second run changes nothing. And like
-- the other `keelbase-*.sql` files it *adds* a file of ours rather than editing upstream's seed.
--
-- KeelBase：让本演示自己的数据不再指向平台厂商的文件服务器。
--
-- 上游那份种子里带着一批图片，URL 挂在 `test.yudao.iocoder.cn` 上——管理员的头像、一条维护通知的正文、
-- 几个 OAuth2 客户端的 logo。它们是上游的演示资产；沿用它们的部署，等于让**每个访问者**去一个我们并不
-- 控制的主机取图：在够不到它的网络里那些图就是加载失败，页面看起来是坏的，而原因与本次部署毫无关系。
-- 故把这些行指向本机自己能提供的东西。
--
-- 幂等：每条语句都按「那个外部主机」匹配，重跑不改动任何东西。与其它 `keelbase-*.sql` 一样，它是**我们
-- 自己的一个文件**，而不是去改上游种子。

-- The admin's avatar — the one that actually renders, since every page's top bar shows it. A failed
-- fetch here is a visibly broken avatar rather than a missing image tucked inside a dialog.
-- 管理员的头像——真正会渲染的那一张：每一页的顶栏都显示它。这里取不到，就是一个肉眼可见的坏头像，
-- 而不是某个弹窗里悄悄缺掉的一张图。
update system_users
   set avatar = '/keelbase-avatar.svg'
 where avatar like '%test.yudao.iocoder.cn%';

-- The maintenance notice's body is HTML whose <img> tags point at the same host. The tags go, the
-- words stay: the notice is upstream's own demo content, and its text is not the problem.
-- 那条维护通知的正文是 HTML，`<img>` 标签指向同一个主机。标签去掉、文字留下：通知本身是上游的演示内容，
-- 有问题的是它的图，不是它的话。
update system_notice
   set content = regexp_replace(content, '<img[^>]*test\.yudao\.iocoder\.cn[^>]*>', '', 'g')
 where content like '%test.yudao.iocoder.cn%';

-- OAuth2 client logos, shown on the social-login and SSO screens. The column is NOT NULL here — not
-- something to guess at: `set logo = null` was tried first and the constraint refused it — so the
-- replacement is the asset this host already serves rather than an empty value that would leave the
-- browser fetching the page itself as an image.
-- OAuth2 客户端的 logo，出现在社交登录与 SSO 页面上。这一列在这里是 **NOT NULL**——不是猜的：
-- 先试过 `set logo = null`，约束把它拒了——所以替代值取本机已经在提供的那张资源，而不是留空
-- （留空会让浏览器把页面本身当成图片去取）。
update system_oauth2_client
   set logo = '/keelbase-avatar.svg'
 where logo like '%test.yudao.iocoder.cn%';
