-- SPDX-License-Identifier: Apache-2.0
--
-- DERIVED, not upstream's script. Yudao does not publish the CRM module's DDL — verified on both
-- `master` and `master-jdk25`: `sql/mysql` holds only `quartz.sql` and `ruoyi-vue-pro.sql`, whose
-- 48 tables are `system_*` and `infra_*` (the `crm_*` names in them are dictionary and menu seed
-- rows, not tables), and CRM is the only module of eighteen without a test schema of its own.
-- The columns below are read off upstream's `CrmCustomerDO` and its `BaseDO`, and typed following
-- this line's PostgreSQL conventions — the same shape its own schema uses (`int8`/`int4`/`int2`,
-- `timestamp`, and numeric flags where the host keeps numbers).
--
-- Why a table is needed at all: YD-2 governs one of the host's *existing* business modules without
-- regenerating it. The module's code is upstream's and stays untouched; only its schema is
-- supplied, which is exactly what running upstream's own SQL would have done.
--
-- 推导而来、**不是上游的脚本**。芋道不发布 CRM 模块的 DDL —— 在 `master` 与 `master-jdk25` 上都核过：
-- `sql/mysql` 只有 `quartz.sql` 与 `ruoyi-vue-pro.sql`，那 48 张表是 `system_*` 与 `infra_*`（其中出现的
-- `crm_*` 名字是字典与菜单的种子行、不是表），而 CRM 是 18 个模块里**唯一**没有自带测试 schema 的。
-- 下面的列取自上游的 `CrmCustomerDO` 与其 `BaseDO`，类型照本线 PostgreSQL 的惯例——即它自己那份 schema
-- 的写法（`int8`/`int4`/`int2`、`timestamp`，以及宿主按数字存的那几个标志）。
--
-- 为什么需要一张表：`YD-2` 要治理宿主**既有**业务模块之一、且**不重新生成**它。模块的代码是上游的、一字未动；
-- 这里只补它的 schema —— 这正是跑上游自己那份 SQL 会做的事。

create sequence if not exists crm_customer_seq;

create table if not exists crm_customer (
    id                   bigint       not null,
    name                 varchar(255) not null default '',
    follow_up_status     int2         not null default 0,
    contact_last_time    timestamp    null default null,
    contact_last_content varchar(500) null default null,
    contact_next_time    timestamp    null default null,
    owner_user_id        bigint       null default null,
    owner_time           timestamp    null default null,
    lock_status          int2         not null default 0,
    deal_status          int2         not null default 0,
    mobile               varchar(20)  null default null,
    telephone            varchar(20)  null default null,
    qq                   varchar(20)  null default null,
    wechat               varchar(255) null default null,
    email                varchar(255) null default null,
    area_id              int4         null default null,
    detail_address       varchar(255) null default null,
    industry_id          int4         null default null,
    level                int4         null default null,
    source               int4         null default null,
    remark               varchar(500) null default null,
    creator              varchar(64)  null default '',
    create_time          timestamp    not null default CURRENT_TIMESTAMP,
    updater              varchar(64)  null default '',
    update_time          timestamp    not null default CURRENT_TIMESTAMP,
    deleted              int2         not null default 0,
    -- The host's tenant interceptor decides per entity class, not per table: `CrmCustomerDO`
    -- extends `BaseDO` and carries no `@TenantIgnore`, so `ignoreTable` answers false and the
    -- `tenant_id = ?` condition IS appended. The column therefore has to exist.
    -- 宿主的租户拦截器**按实体类**判、不按表：`CrmCustomerDO` 继承 `BaseDO`、也没带 `@TenantIgnore`，
    -- 于是 `ignoreTable` 答 false、`tenant_id = ?` 这个条件**会**被加上。故这一列必须在。
    tenant_id            bigint       not null default 0,
    primary key (id)
);

-- The create path reads this one before it inserts: `validateCustomerExceedOwnerLimit` asks the
-- owner-limit configuration whether the caller is already at their ceiling. With no configuration
-- rows it returns early, which is why it can be created empty — but it has to exist, and the read
-- went looking for it the first time a create was approved.
--
-- 创建路径在插入前会读这一张：`validateCustomerExceedOwnerLimit` 先问「负责人上限」配置，看调用方是否已到顶。
-- 没有配置行它就早退，故这张表可以是空的——但它必须存在，而那次读正是在**第一次批准创建**时才去找它的。

create sequence if not exists crm_customer_limit_config_seq;

create table if not exists crm_customer_limit_config (
    id                 bigint        not null,
    type               int4          not null,
    user_ids           varchar(1024) null default null,
    dept_ids           varchar(1024) null default null,
    max_count          int4          not null,
    deal_count_enabled int2          not null default 0,
    creator            varchar(64)   null default '',
    create_time        timestamp     not null default CURRENT_TIMESTAMP,
    updater            varchar(64)   null default '',
    update_time        timestamp     not null default CURRENT_TIMESTAMP,
    deleted            int2          not null default 0,
    tenant_id          bigint        not null default 0,
    primary key (id)
);

-- The customer page joins this one: upstream's own row-level permission model, read through
-- `CrmPermissionUtils.appendPermissionCondition` (a MyBatis-Plus-Join inner join). A read of the
-- customer list therefore needs it as much as a create does — `createCustomer` writes it too.
--
-- 客户分页会 join 这一张：上游自己的行级权限模型，经 `CrmPermissionUtils.appendPermissionCondition` 读
-- （MyBatis-Plus-Join 的 inner join）。因此读客户列表与写客户一样需要它——`createCustomer` 也会写它。

create sequence if not exists crm_permission_seq;

create table if not exists crm_permission (
    id          bigint      not null,
    biz_type    int4        not null,
    biz_id      bigint      not null,
    user_id     bigint      not null,
    level       int4        not null,
    creator     varchar(64) null default '',
    create_time timestamp   not null default CURRENT_TIMESTAMP,
    updater     varchar(64) null default '',
    update_time timestamp   not null default CURRENT_TIMESTAMP,
    deleted     int2        not null default 0,
    tenant_id   bigint      not null default 0,
    primary key (id)
);
