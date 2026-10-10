-- SPDX-License-Identifier: Apache-2.0
--
-- Point this deployment's file storage at a store it can actually use, and say where its URLs point.
--
-- The upstream seed selects the **vendor's own bucket** as the master storage: a Qiniu/S3 client whose
-- keys and bucket belong to the reference project (`bucket: ruoyi-vue-pro`, `domain:
-- http://test.yudao.iocoder.cn`). Every upload against it fails, and the symptom is silent from the
-- UI's side: the avatar dialog closes and nothing changes. On this deployment `infra_file` had **no
-- rows at all** — nothing had ever been uploaded successfully.
--
-- This file changes only **our data**, in the shape the two upstream sets are left in: the schema and
-- the upstream seed are untouched, and nothing here is deleted.
--
-- Run it with the origin this deployment is reached at, because that is what a stored file's URL is
-- built from:
--
--   psql -v origin=https://demo2.keelbase.com.cn -f sql/keelbase-file-storage.sql
--   psql -v origin=http://127.0.0.1:48080       -f sql/keelbase-file-storage.sql
--
-- A file's URL is `{domain}/admin-api/infra/file/{configId}/get/{path}` — the API path and the config's
-- own id are part of it, which is why id 4 keeps its number.
--
-- 把本部署的文件存储指到一个**真能用**的存储上，并说明它的 URL 指向哪里。
--
-- 上游种子里选中的 master 存储是**厂商自己的桶**：一份七牛/S3 客户端，密钥与桶都属于参照项目
-- （`bucket: ruoyi-vue-pro`、`domain: http://test.yudao.iocoder.cn`）。对它上传**必然失败**，而从界面
-- 一侧看是**静默**的：头像弹窗关掉、什么都没变。在本部署上 `infra_file` **一行都没有**——从来没成功上传过。
--
-- 本文件**只动我们自己的数据**，形状与两份上游集合保持一致：schema 与上游种子一字不动，这里也不删任何东西。
--
-- 跑它时要给出**本部署被访问的那个源**，因为存下来的文件 URL 正是由它拼出来的：
--
--   psql -v origin=https://demo2.keelbase.com.cn -f sql/keelbase-file-storage.sql
--   psql -v origin=http://127.0.0.1:48080       -f sql/keelbase-file-storage.sql
--
-- 文件 URL 形如 `{domain}/admin-api/infra/file/{configId}/get/{path}` —— **API 路径与本条 config 的 id
-- 都在里面**，所以 id 4 的编号必须留住。

\if :{?origin}
\else
  \echo 'origin is required: psql -v origin=https://<this deployment> -f sql/keelbase-file-storage.sql'
  \quit 1
\endif

BEGIN;

-- Only one row may be master; the seed left the vendor's bucket holding it.
-- 只能有一行是 master；上游种子把这一位留给了厂商那个桶。
UPDATE infra_file_config SET master = false WHERE master = true;

-- The database-backed store is the one that works anywhere: files go into the database, so there is no
-- host directory to exist (the local store's upstream path is a macOS home directory) and no third
-- party to reach. 数据库存储是**哪儿都能用**的那一个：文件进库，既不依赖宿主目录存在（本地存储的上游
-- 路径是一个 macOS 家目录），也不依赖第三方可达。
UPDATE infra_file_config SET master = true WHERE id = 4;

-- Its URL is built from `domain` + the API path; set the domain to this deployment's own origin.
-- 它的 URL 由 `domain` + API 路径拼出；把 domain 设成本部署自己的源。
UPDATE infra_file_config
   SET config = '{"@class":"cn.iocoder.yudao.module.infra.framework.file.core.client.db.DBFileClientConfig","domain":"' || :'origin' || '"}'
 WHERE id = 4;

COMMIT;
