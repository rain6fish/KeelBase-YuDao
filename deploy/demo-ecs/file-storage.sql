-- SPDX-License-Identifier: Apache-2.0
--
-- Where this deployment's uploads go.
--
-- Upstream's seed ships eleven storage configs and marks the *example* Qiniu one as master, with the
-- platform vendor's own domain in it. Every upload therefore lands on a server we do not control and
-- comes back as a URL pointing there — which is how a picture uploaded through this deployment
-- arrived as `http://test.yudao.iocoder.cn/…`. Fixing the rows that already point there (see
-- `sql/keelbase-local-images.sql`) treats the symptom; this treats the cause.
--
-- The master is the local client instead. It writes under `basePath` inside the server container,
-- which is why that path is mounted as a volume in `docker-compose.yml` — and why the two files have
-- to move together. `domain` is this deployment's public origin, so the URL the API returns is one
-- the browser can actually fetch: the path under it is `/admin-api/…`, which the outer nginx already
-- proxies to the server.
--
-- Idempotent: it sets exactly one master, and writing the same value twice changes nothing.
--
-- 本部署的上传落到哪里。
--
-- 上游种子带了十一份存储配置，并把**示例**七牛那份标成 master，里面写的是平台厂商自己的域名。于是每次
-- 上传都落到一个我们并不控制的服务器上、回来的 URL 也指向那里——这张经由本部署上传的图就是这样变成
-- `http://test.yudao.iocoder.cn/…` 的。把已经指向那里**的行**改掉（见 `sql/keelbase-local-images.sql`）
-- 治的是症状；这里治的是因。
--
-- 改为把**本地客户端**设为 master。它写在 server 容器内的 `basePath` 下，这正是那个路径在
-- `docker-compose.yml` 里挂了卷的原因——也是这两个文件必须**一起动**的原因。`domain` 是本部署的公网
-- 源，于是接口返回的 URL 是浏览器真能取到的：它下面的路径是 `/admin-api/…`，外层 nginx 已经把它转给
-- 服务了。
--
-- 幂等：它只让**恰好一条**成为 master，同一个值写两遍不产生变化。

-- The local client, made master. Its config is rewritten because upstream's example points at the
-- original author's machine (`/Users/yunai/tmp/file`) and at a loopback origin.
-- 本地客户端设为 master。它的配置要重写：上游那份示例指向的是原作者那台机器（`/Users/yunai/tmp/file`）
-- 和一个回环源。
update infra_file_config
   set master = true,
       config = '{"@class":"cn.iocoder.yudao.module.infra.framework.file.core.client.local.LocalFileClientConfig","basePath":"/app/uploads","domain":"https://demo2.keelbase.com.cn"}'
 where id = 29;

-- Everything else steps down: the module looks the master up by `master = true` and a second one would
-- make that lookup ambiguous.
-- 其余的一律让位：模块是按 `master = true` 找那一条的，多出一条会让这次查找变得没有唯一答案。
update infra_file_config
   set master = false
 where id <> 29
   and master = true;
