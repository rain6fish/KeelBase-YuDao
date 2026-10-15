-- SPDX-License-Identifier: Apache-2.0
--
-- 本部署的**样例业务数据**：一批能看懂的客户、联系人、跟进记录，以及产品目录。
--
-- **为什么要它。** 在这之前，库里 `crm_customer` 与 `products` 的每一行都是门禁跑一次留下的
-- `gate-<时间戳>-*`（`crm_customer` 165 行、`products` 123 行，无一例外）。那让控制台看起来像**没人用过**：
-- 列表里是一串机器名，详情页点开什么都没有。而「一套空的、干净的环境」与「一串测试残留」是两回事 ——
-- 演示、走查、给外部看，需要的都是**前者的样子、后者的干净**。这份文件给的就是那个样子。
--
-- **带外灌入，不进部署引导。** 与 `deploy/demo-ecs/bootstrap-db.sh` 那串 `ADDITIONS` **无关**：那是
-- 「表 + 管理器」的引导，任何一套部署都要跑；这是**给某一套开发/演示环境看的样例**，不该跟着进每一台机器。
-- 本机由手工执行；要重复得到这份样子，就再跑一次这个文件。
--
-- **幂等是构造出来的**：固定 id（9xxx 段）、缺了才插。重跑不翻倍，也不碰任何它自己没写的行。9xxx 段是
-- 挑过的：平台的序列（`crm_customer_seq` 等）停在两百上下，而宿主取号走序列，故这一批**不会**与它撞号。
--
-- 演示数据里**没有**任何身份、角色或权限：那些在 `keelbase-assistant-role-grants.sql` 一类的文件里，
-- 这份只管**业务行**。
--
-- This deployment's **sample business data**: a handful of readable customers, their contacts and
-- follow-up records, and a product catalogue.
--
-- Why it exists: before it, every row in `crm_customer` and `products` on this host was a
-- `gate-<timestamp>-*` left behind by a gate run (165 and 123 rows, without exception). That made the
-- console read as *nobody has used this* — a list of machine names, and detail pages that open on
-- nothing. A clean empty environment and a heap of test residue are two different things, and what a
-- demo, a walkthrough or an outside reader needs is the look of the first with the tidiness of the
-- second. This file is that look.
--
-- Applied **out of band**, and not part of any deployment bootstrap: the `ADDITIONS` list in
-- `deploy/demo-ecs/bootstrap-db.sh` is the tables-and-menus bootstrap every deployment runs, whereas
-- this is sample data for one development/demo environment and must not ride along to every machine. It
-- is run by hand here; running it again is how you get this look back.
--
-- Idempotent by construction: fixed ids in the 9xxx band, inserted only when absent. The band is chosen
-- because the platform's sequences (`crm_customer_seq` and friends) sit in the low two hundreds and the
-- host takes its ids from those sequences, so this batch cannot collide with them.
--
-- It seeds **business rows only** — no identity, role or permission, which live in files like
-- `keelbase-assistant-role-grants.sql`.
--
-- **`creator` / `updater` 必须是数字用户 id，不是一句署名。** CRM 的 VO 把这两列当**用户编号**解析，写进去
-- 任何非数字的东西会让**整个模块的读面**回 500（`NumberFormatException: For input string: "…"`），而不是只坏
-- 那一行 —— 本文件第一次写时用了 `'keelbase-demo'`，`/crm/customer/page`、`/crm/contact/page` 与
-- `/crm/customer/get` 三处**同时**挂掉，直到改成 `'1'` 才好。故这里统一用 `'1'`（admin 本人）。
--
-- 同理，**空的列表列要写空串 `''`，不是 NULL、也不是 `'[]'`**：`LongListTypeHandler` 对 NULL 返回 null，
-- 页面随后对 null 调 `.stream()`（NPE）；对 `'[]'` 抛 `Unparseable number`。它按**逗号分隔的数字**解析，
-- 故「没有」就是空串。
--
-- **`creator` / `updater` must be numeric user ids, not a byline.** The CRM VOs parse those two columns as
-- **user numbers**, so a non-numeric value makes the **whole module's read face** answer 500
-- (`NumberFormatException: For input string: "…"`) rather than spoiling one row — the first cut of this file
-- wrote `'keelbase-demo'` and `/crm/customer/page`, `/crm/contact/page` and `/crm/customer/get` all broke at
-- once, until it became `'1'`. Hence the uniform `'1'` (admin).
--
-- For the same reason, **an empty list column is the empty string `''` — not NULL, and not `'[]'`**:
-- `LongListTypeHandler` returns null for NULL and the page calls `.stream()` on null (NPE); on `'[]'` it
-- throws `Unparseable number`. It parses **comma-separated numbers**, so "none" is the empty string.

-- 客户：六家，行业/级别/来源都用宿主自己的字典值（`system_dict_data` 里现成的），不编词。
-- Customers: six, with industry/level/source taken from the host's own dictionary values rather than invented.
INSERT INTO crm_customer (id, name, industry_id, level, source, owner_user_id, owner_time, mobile, telephone, email, detail_address, remark, creator, create_time, update_time, deleted, tenant_id)
SELECT 9001, '晨光科技（杭州）有限公司', 1, 1, 4, 1, CURRENT_TIMESTAMP, '13800009001', '0571-88099001', 'contact@chenguang-tech.example', '浙江省杭州市滨江区江陵路 88 号', '重点客户，年度续约在即', '1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM crm_customer WHERE id = 9001);

INSERT INTO crm_customer (id, name, industry_id, level, source, owner_user_id, owner_time, mobile, telephone, email, detail_address, remark, creator, create_time, update_time, deleted, tenant_id)
SELECT 9002, '远山医疗器械有限公司', 6, 2, 9, 1, CURRENT_TIMESTAMP, '13800009002', '0512-66088002', 'sales@yuanshan-med.example', '江苏省苏州市工业园区星湖街 328 号', '设备采购周期长，需持续跟进', '1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM crm_customer WHERE id = 9002);

INSERT INTO crm_customer (id, name, industry_id, level, source, owner_user_id, owner_time, mobile, telephone, email, detail_address, remark, creator, create_time, update_time, deleted, tenant_id)
SELECT 9003, '海州物流股份有限公司', 5, 1, 4, 1, CURRENT_TIMESTAMP, '13800009003', '0532-88099003', 'ops@haizhou-logistics.example', '山东省青岛市市南区香港中路 76 号', '运力调度系统改造，预算已立项', '1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM crm_customer WHERE id = 9003);

INSERT INTO crm_customer (id, name, industry_id, level, source, owner_user_id, owner_time, mobile, telephone, email, detail_address, remark, creator, create_time, update_time, deleted, tenant_id)
SELECT 9004, '明远置业集团有限公司', 3, 2, 2, 1, CURRENT_TIMESTAMP, '13800009004', '020-88099004', 'it@mingyuan-estate.example', '广东省广州市天河区珠江新城华夏路 30 号', '集团信息化统一采购，决策链较长', '1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM crm_customer WHERE id = 9004);

INSERT INTO crm_customer (id, name, industry_id, level, source, owner_user_id, owner_time, mobile, telephone, email, detail_address, remark, creator, create_time, update_time, deleted, tenant_id)
SELECT 9005, '万象商业服务有限公司', 4, 3, 8, 1, CURRENT_TIMESTAMP, '13800009005', '010-88099005', 'hello@wanxiang-service.example', '北京市朝阳区建国路 99 号', '初次接触，需求尚不明确', '1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM crm_customer WHERE id = 9005);

INSERT INTO crm_customer (id, name, industry_id, level, source, owner_user_id, owner_time, mobile, telephone, email, detail_address, remark, creator, create_time, update_time, deleted, tenant_id)
SELECT 9006, '江城市政信息服务有限公司', 7, 2, 4, 1, CURRENT_TIMESTAMP, '13800009006', '027-88099006', 'service@jiangcheng-gov.example', '湖北省武汉市武昌区中南路 12 号', '由老客户转介绍', '1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM crm_customer WHERE id = 9006);

-- 联系人：三家客户各一位主要联系人（`sex` 按宿主的字典 1=男、2=女）。
-- Contacts: one primary contact each for three of the customers.
INSERT INTO crm_contact (id, name, customer_id, owner_user_id, mobile, telephone, email, post, master, sex, detail_address, remark, creator, create_time, update_time, deleted, tenant_id)
SELECT 9101, '陈志远', 9001, 1, '13900009101', '0571-88099101', 'chenzhiyuan@chenguang-tech.example', '采购总监', true, 1, '浙江省杭州市滨江区江陵路 88 号', '主要决策人', '1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM crm_contact WHERE id = 9101);

INSERT INTO crm_contact (id, name, customer_id, owner_user_id, mobile, telephone, email, post, master, sex, detail_address, remark, creator, create_time, update_time, deleted, tenant_id)
SELECT 9102, '林晓芸', 9002, 1, '13900009102', '0512-66088102', 'linxiaoyun@yuanshan-med.example', '财务经理', true, 2, '江苏省苏州市工业园区星湖街 328 号', '合同与付款对接人', '1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM crm_contact WHERE id = 9102);

INSERT INTO crm_contact (id, name, customer_id, owner_user_id, mobile, telephone, email, post, master, sex, detail_address, remark, creator, create_time, update_time, deleted, tenant_id)
SELECT 9103, '赵鹏', 9003, 1, '13900009103', '0532-88099103', 'zhaopeng@haizhou-logistics.example', '运营总监', true, 1, '山东省青岛市市南区香港中路 76 号', '技术与实施对接人', '1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM crm_contact WHERE id = 9103);

-- 跟进记录：`biz_type = 2` 是客户（`CrmBizTypeEnum.CRM_CUSTOMER`），`type` 取宿主自己的跟进方式字典。
-- Follow-up records: `biz_type = 2` is a customer, and `type` takes the host's own follow-up-method dictionary.
INSERT INTO crm_follow_up_record (id, biz_type, biz_id, type, content, next_time, pic_urls, file_urls, business_ids, contact_ids, creator, create_time, update_time, deleted, tenant_id)
SELECT 9201, 2, 9001, 3, '上门拜访，确认年度续约意向，下周出方案。', CURRENT_TIMESTAMP + INTERVAL '7 days', '', '', '', '', '1', CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '2 days', 0, 1
WHERE NOT EXISTS (SELECT 1 FROM crm_follow_up_record WHERE id = 9201);

INSERT INTO crm_follow_up_record (id, biz_type, biz_id, type, content, next_time, pic_urls, file_urls, business_ids, contact_ids, creator, create_time, update_time, deleted, tenant_id)
SELECT 9202, 2, 9002, 1, '电话沟通设备采购预算，对方需内部评审后再答复。', CURRENT_TIMESTAMP + INTERVAL '14 days', '', '', '', '', '1', CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '2 days', 0, 1
WHERE NOT EXISTS (SELECT 1 FROM crm_follow_up_record WHERE id = 9202);

INSERT INTO crm_follow_up_record (id, biz_type, biz_id, type, content, next_time, pic_urls, file_urls, business_ids, contact_ids, creator, create_time, update_time, deleted, tenant_id)
SELECT 9203, 2, 9003, 4, '微信同步了运力调度系统的初步需求清单。', CURRENT_TIMESTAMP + INTERVAL '5 days', '', '', '', '', '1', CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '2 days', 0, 1
WHERE NOT EXISTS (SELECT 1 FROM crm_follow_up_record WHERE id = 9203);

-- 产品目录：这是**生成模块**（`products`）的表 —— 它的 `status` 用的是 `specs/products.json` 声明的两值，
-- 且**没有** `tenant_id` 列（该表在宿主的租户拦截器之外，见 `scripts/run-server.sh` 的 ignore-tables）。
-- Product catalogue: the **generated** module's table. Its `status` takes the two values `specs/products.json`
-- declares, and it has no `tenant_id` column (the table sits outside the host's tenant interceptor).
INSERT INTO products (id, name, price, status, owner_user_id, creator, create_time, update_time, deleted)
SELECT 9001, '企业版订阅（年）', 59800, 'on_sale', '1', '1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM products WHERE id = 9001);

INSERT INTO products (id, name, price, status, owner_user_id, creator, create_time, update_time, deleted)
SELECT 9002, '标准版订阅（年）', 19800, 'on_sale', '1', '1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM products WHERE id = 9002);

INSERT INTO products (id, name, price, status, owner_user_id, creator, create_time, update_time, deleted)
SELECT 9003, '私有化部署实施服务', 120000, 'on_sale', '1', '1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM products WHERE id = 9003);

INSERT INTO products (id, name, price, status, owner_user_id, creator, create_time, update_time, deleted)
SELECT 9004, '数据迁移与集成服务', 35000, 'on_sale', '1', '1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM products WHERE id = 9004);

INSERT INTO products (id, name, price, status, owner_user_id, creator, create_time, update_time, deleted)
SELECT 9005, '现场培训（每场）', 8000, 'off_shelf', '1', '1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM products WHERE id = 9005);

INSERT INTO products (id, name, price, status, owner_user_id, creator, create_time, update_time, deleted)
SELECT 9006, '运维支持包（年）', 24000, 'on_sale', '1', '1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM products WHERE id = 9006);
