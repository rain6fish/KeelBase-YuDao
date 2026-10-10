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
--
-- It has since grown to cover the rest of the module. The module is compiled in whole, so its menus
-- and pages exist; and a page whose table is missing answers 500 rather than showing an empty list —
-- which is how the clue page announced itself. The tables below are the same kind of derivation,
-- applied to the module's remaining data objects. They are created empty on purpose: the demo reads
-- them, and upstream ships no rows for them either.
--
-- 它后来扩展为覆盖整个模块。模块是**整体**编译进来的，故它的菜单与页面都在；而**表缺失的页面答 500、
-- 不是显示空列表**——线索页就是这样自己暴露出来的。下面这些表是与前面同一类的推导，对象是模块剩下的
-- 数据对象。它们**建成即空**是有意的：demo 只读它们，而上游本来也没给它们任何数据行。

create sequence if not exists crm_customer_seq;

create table if not exists crm_customer (
    id                   bigint       not null,
    name                 varchar(255) not null default '',
    follow_up_status     bool         not null default false,
    contact_last_time    timestamp    null default null,
    contact_last_content varchar(500) null default null,
    contact_next_time    timestamp    null default null,
    owner_user_id        bigint       null default null,
    owner_time           timestamp    null default null,
    lock_status          bool         not null default false,
    deal_status          bool         not null default false,
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
    deal_count_enabled bool          not null default false,
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

-- ---------------------------------------------------------------------------------------------------
-- 商机 · business
-- Java `Boolean` is `boolean` — **not** `int2`. That distinction is not cosmetic: the host passes a
-- Boolean field as a real boolean parameter, and PostgreSQL has no `smallint = boolean` operator, so
-- a numeric column makes every query that compares the flag fail. The host's own schema settles it:
-- `infra_file_config.master` and `infra_config.visible` are `boolean`, while `system_users.status`
-- (a Java `Integer`) is `smallint`. Java `Integer` is `int4` here; money is `numeric(24,6)`; a
-- JSON-shaped list column is `varchar(1024)`.
-- 商机。Java `Boolean` 是 **`boolean`**，**不是** `int2`。这不是外观之争：宿主把布尔字段当成真的布尔
-- 参数传下去，而 PostgreSQL **没有** `smallint = boolean` 这个算符，于是数值类型的列会让**每一条比较该
-- 标志的查询**失败。宿主自己的 schema 就是判据：`infra_file_config.master` 与 `infra_config.visible`
-- 都是 `boolean`，而 `system_users.status`（Java `Integer`）是 `smallint`。这里 Java `Integer` 用 `int4`；
-- 金额用 `numeric(24,6)`；JSON 形态的列表列用 `varchar(1024)`。

create sequence if not exists crm_business_seq;

create table if not exists crm_business (
    id                  bigint        not null,
    name                varchar(255)  not null default '',
    customer_id         bigint        null default null,
    follow_up_status    bool          not null default false,
    contact_last_time   timestamp     null default null,
    contact_next_time   timestamp     null default null,
    owner_user_id       bigint        null default null,
    status_type_id      bigint        null default null,
    status_id           bigint        null default null,
    end_status          int4          null default null,
    end_remark          varchar(500)  null default null,
    deal_time           timestamp     null default null,
    total_product_price numeric(24,6) null default null,
    discount_percent    numeric(24,6) null default null,
    total_price         numeric(24,6) null default null,
    remark              varchar(500)  null default null,
    creator             varchar(64)   null default '',
    create_time         timestamp     not null default CURRENT_TIMESTAMP,
    updater             varchar(64)   null default '',
    update_time         timestamp     not null default CURRENT_TIMESTAMP,
    deleted             int2          not null default 0,
    tenant_id           bigint        not null default 0,
    primary key (id)
);

create sequence if not exists crm_business_product_seq;

create table if not exists crm_business_product (
    id             bigint        not null,
    business_id    bigint        not null,
    product_id     bigint        not null,
    product_price  numeric(24,6) null default null,
    business_price numeric(24,6) null default null,
    count          numeric(24,6) null default null,
    total_price    numeric(24,6) null default null,
    creator        varchar(64)   null default '',
    create_time    timestamp     not null default CURRENT_TIMESTAMP,
    updater        varchar(64)   null default '',
    update_time    timestamp     not null default CURRENT_TIMESTAMP,
    deleted        int2          not null default 0,
    tenant_id      bigint        not null default 0,
    primary key (id)
);

create sequence if not exists crm_business_status_seq;

create table if not exists crm_business_status (
    id          bigint       not null,
    type_id     bigint       not null,
    name        varchar(255) not null default '',
    percent     int4         null default null,
    sort        int4         null default null,
    creator     varchar(64)  null default '',
    create_time timestamp    not null default CURRENT_TIMESTAMP,
    updater     varchar(64)  null default '',
    update_time timestamp    not null default CURRENT_TIMESTAMP,
    deleted     int2         not null default 0,
    tenant_id   bigint       not null default 0,
    primary key (id)
);

create sequence if not exists crm_business_status_type_seq;

create table if not exists crm_business_status_type (
    id          bigint        not null,
    name        varchar(255)  not null default '',
    dept_ids    varchar(1024) null default null,
    creator     varchar(64)   null default '',
    create_time timestamp     not null default CURRENT_TIMESTAMP,
    updater     varchar(64)   null default '',
    update_time timestamp     not null default CURRENT_TIMESTAMP,
    deleted     int2          not null default 0,
    tenant_id   bigint        not null default 0,
    primary key (id)
);

-- ---------------------------------------------------------------------------------------------------
-- 线索 · clue — the page that answered 500 and made this file grow.
-- 线索。就是那个答 500、让本文件长大的页面。

create sequence if not exists crm_clue_seq;

create table if not exists crm_clue (
    id                   bigint       not null,
    name                 varchar(255) not null default '',
    follow_up_status     bool         not null default false,
    contact_last_time    timestamp    null default null,
    contact_last_content varchar(500) null default null,
    contact_next_time    timestamp    null default null,
    owner_user_id        bigint       null default null,
    transform_status     bool         not null default false,
    customer_id          bigint       null default null,
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
    tenant_id            bigint       not null default 0,
    primary key (id)
);

-- ---------------------------------------------------------------------------------------------------
-- 联系人 · contact
-- 联系人。注意 `qq` 在实体里是 `Long`（不是字符串），照它建 bigint。

create sequence if not exists crm_contact_seq;

create table if not exists crm_contact (
    id                   bigint       not null,
    name                 varchar(255) not null default '',
    customer_id          bigint       null default null,
    contact_last_time    timestamp    null default null,
    contact_last_content varchar(500) null default null,
    contact_next_time    timestamp    null default null,
    owner_user_id        bigint       null default null,
    mobile               varchar(20)  null default null,
    telephone            varchar(20)  null default null,
    email                varchar(255) null default null,
    qq                   bigint       null default null,
    wechat               varchar(255) null default null,
    area_id              int4         null default null,
    detail_address       varchar(255) null default null,
    sex                  int4         null default null,
    master               bool         not null default false,
    post                 varchar(255) null default null,
    parent_id            bigint       null default null,
    remark               varchar(500) null default null,
    creator              varchar(64)  null default '',
    create_time          timestamp    not null default CURRENT_TIMESTAMP,
    updater              varchar(64)  null default '',
    update_time          timestamp    not null default CURRENT_TIMESTAMP,
    deleted              int2         not null default 0,
    tenant_id            bigint       not null default 0,
    primary key (id)
);

create sequence if not exists crm_contact_business_seq;

create table if not exists crm_contact_business (
    id          bigint      not null,
    contact_id  bigint      not null,
    business_id bigint      not null,
    creator     varchar(64) null default '',
    create_time timestamp   not null default CURRENT_TIMESTAMP,
    updater     varchar(64) null default '',
    update_time timestamp   not null default CURRENT_TIMESTAMP,
    deleted     int2        not null default 0,
    tenant_id   bigint      not null default 0,
    primary key (id)
);

-- ---------------------------------------------------------------------------------------------------
-- 合同 · contract
-- 合同。

create sequence if not exists crm_contract_config_seq;

create table if not exists crm_contract_config (
    id             bigint      not null,
    notify_enabled bool        not null default false,
    notify_days    int4        null default null,
    creator        varchar(64) null default '',
    create_time    timestamp   not null default CURRENT_TIMESTAMP,
    updater        varchar(64) null default '',
    update_time    timestamp   not null default CURRENT_TIMESTAMP,
    deleted        int2        not null default 0,
    tenant_id      bigint      not null default 0,
    primary key (id)
);

create sequence if not exists crm_contract_seq;

create table if not exists crm_contract (
    id                  bigint        not null,
    name                varchar(255)  not null default '',
    no                  varchar(255)  null default null,
    customer_id         bigint        null default null,
    business_id         bigint        null default null,
    contact_last_time   timestamp     null default null,
    owner_user_id       bigint        null default null,
    process_instance_id varchar(64)   null default null,
    audit_status        int4          null default null,
    order_date          timestamp     null default null,
    start_time          timestamp     null default null,
    end_time            timestamp     null default null,
    total_product_price numeric(24,6) null default null,
    discount_percent    numeric(24,6) null default null,
    total_price         numeric(24,6) null default null,
    sign_contact_id     bigint        null default null,
    sign_user_id        bigint        null default null,
    remark              varchar(500)  null default null,
    creator             varchar(64)   null default '',
    create_time         timestamp     not null default CURRENT_TIMESTAMP,
    updater             varchar(64)   null default '',
    update_time         timestamp     not null default CURRENT_TIMESTAMP,
    deleted             int2          not null default 0,
    tenant_id           bigint        not null default 0,
    primary key (id)
);

create sequence if not exists crm_contract_product_seq;

create table if not exists crm_contract_product (
    id             bigint        not null,
    contract_id    bigint        not null,
    product_id     bigint        not null,
    product_price  numeric(24,6) null default null,
    contract_price numeric(24,6) null default null,
    count          numeric(24,6) null default null,
    total_price    numeric(24,6) null default null,
    creator        varchar(64)   null default '',
    create_time    timestamp     not null default CURRENT_TIMESTAMP,
    updater        varchar(64)   null default '',
    update_time    timestamp     not null default CURRENT_TIMESTAMP,
    deleted        int2          not null default 0,
    tenant_id      bigint        not null default 0,
    primary key (id)
);

-- ---------------------------------------------------------------------------------------------------
-- 产品 · product
-- 产品。`unit` 在实体里是基本类型 `int`。

create sequence if not exists crm_product_category_seq;

create table if not exists crm_product_category (
    id          bigint       not null,
    name        varchar(255) not null default '',
    parent_id   bigint       null default null,
    creator     varchar(64)  null default '',
    create_time timestamp    not null default CURRENT_TIMESTAMP,
    updater     varchar(64)  null default '',
    update_time timestamp    not null default CURRENT_TIMESTAMP,
    deleted     int2         not null default 0,
    tenant_id   bigint       not null default 0,
    primary key (id)
);

create sequence if not exists crm_product_seq;

create table if not exists crm_product (
    id            bigint        not null,
    name          varchar(255)  not null default '',
    no            varchar(255)  null default null,
    unit          int4          null default null,
    price         numeric(24,6) null default null,
    status        int4          null default null,
    category_id   bigint        null default null,
    description   varchar(500)  null default null,
    owner_user_id bigint        null default null,
    creator       varchar(64)   null default '',
    create_time   timestamp     not null default CURRENT_TIMESTAMP,
    updater       varchar(64)   null default '',
    update_time   timestamp     not null default CURRENT_TIMESTAMP,
    deleted       int2          not null default 0,
    tenant_id     bigint        not null default 0,
    primary key (id)
);

-- ---------------------------------------------------------------------------------------------------
-- 回款 · receivable
-- 回款。合同页与回款页都会读它们。

create sequence if not exists crm_receivable_plan_seq;

create table if not exists crm_receivable_plan (
    id            bigint        not null,
    period        int4          null default null,
    customer_id   bigint        null default null,
    contract_id   bigint        null default null,
    owner_user_id bigint        null default null,
    return_time   timestamp     null default null,
    return_type   int4          null default null,
    price         numeric(24,6) null default null,
    receivable_id bigint        null default null,
    remind_days   int4          null default null,
    remind_time   timestamp     null default null,
    remark        varchar(500)  null default null,
    creator       varchar(64)   null default '',
    create_time   timestamp     not null default CURRENT_TIMESTAMP,
    updater       varchar(64)   null default '',
    update_time   timestamp     not null default CURRENT_TIMESTAMP,
    deleted       int2          not null default 0,
    tenant_id     bigint        not null default 0,
    primary key (id)
);

create sequence if not exists crm_receivable_seq;

create table if not exists crm_receivable (
    id                  bigint        not null,
    no                  varchar(255)  null default null,
    plan_id             bigint        null default null,
    customer_id         bigint        null default null,
    contract_id         bigint        null default null,
    owner_user_id       bigint        null default null,
    return_time         timestamp     null default null,
    return_type         int4          null default null,
    price               numeric(24,6) null default null,
    remark              varchar(500)  null default null,
    process_instance_id varchar(64)   null default null,
    audit_status        int4          null default null,
    creator             varchar(64)   null default '',
    create_time         timestamp     not null default CURRENT_TIMESTAMP,
    updater             varchar(64)   null default '',
    update_time         timestamp     not null default CURRENT_TIMESTAMP,
    deleted             int2          not null default 0,
    tenant_id           bigint        not null default 0,
    primary key (id)
);

-- ---------------------------------------------------------------------------------------------------
-- 跟进记录 / 转移记录 / 公海配置 / 业绩目标 — read by the customer detail page and the settings screens.
-- 跟进记录 / 转移记录 / 公海配置 / 业绩目标——客户详情页与设置类页面会读它们。
-- `crm_follow_up_record` is the table; its sequence keeps upstream's name (`crm_follow_up_seq`), which
-- is what its `@KeySequence` says — not the table name plus `_seq`.
-- `crm_follow_up_record` 是表名；它的序列沿用上游给的名字（`crm_follow_up_seq`）——那是它 `@KeySequence`
-- 里写的，不是表名加 `_seq`。

create sequence if not exists crm_follow_up_seq;

create table if not exists crm_follow_up_record (
    id           bigint        not null,
    biz_type     int4          not null,
    biz_id       bigint        not null,
    type         int4          null default null,
    content      varchar(500)  null default null,
    next_time    timestamp     null default null,
    pic_urls     varchar(1024) null default null,
    file_urls    varchar(1024) null default null,
    business_ids varchar(1024) null default null,
    contact_ids  varchar(1024) null default null,
    creator      varchar(64)   null default '',
    create_time  timestamp     not null default CURRENT_TIMESTAMP,
    updater      varchar(64)   null default '',
    update_time  timestamp     not null default CURRENT_TIMESTAMP,
    deleted      int2          not null default 0,
    tenant_id    bigint        not null default 0,
    primary key (id)
);

create sequence if not exists crm_owner_record_seq;

create table if not exists crm_owner_record (
    id                 bigint      not null,
    biz_type           int4        not null,
    biz_id             bigint      not null,
    pre_owner_user_id  bigint      null default null,
    post_owner_user_id bigint      null default null,
    creator            varchar(64) null default '',
    create_time        timestamp   not null default CURRENT_TIMESTAMP,
    updater            varchar(64) null default '',
    update_time        timestamp   not null default CURRENT_TIMESTAMP,
    deleted            int2        not null default 0,
    tenant_id          bigint      not null default 0,
    primary key (id)
);

create sequence if not exists crm_customer_pool_config_seq;

create table if not exists crm_customer_pool_config (
    id                  bigint      not null,
    enabled             bool        not null default false,
    contact_expire_days int4        null default null,
    deal_expire_days    int4        null default null,
    notify_enabled      bool        not null default false,
    notify_days         int4        null default null,
    creator             varchar(64) null default '',
    create_time         timestamp   not null default CURRENT_TIMESTAMP,
    updater             varchar(64) null default '',
    update_time         timestamp   not null default CURRENT_TIMESTAMP,
    deleted             int2        not null default 0,
    tenant_id           bigint      not null default 0,
    primary key (id)
);

create sequence if not exists crm_performance_config_seq;

create table if not exists crm_performance_config (
    id                    bigint        not null,
    biz_type              int4          not null,
    object_id             bigint        null default null,
    object_type           int4          null default null,
    year                  int4          null default null,
    year_target_price     numeric(24,6) null default null,
    january_target_price  numeric(24,6) null default null,
    february_target_price numeric(24,6) null default null,
    march_target_price    numeric(24,6) null default null,
    april_target_price    numeric(24,6) null default null,
    may_target_price      numeric(24,6) null default null,
    june_target_price     numeric(24,6) null default null,
    july_target_price     numeric(24,6) null default null,
    august_target_price   numeric(24,6) null default null,
    september_target_price numeric(24,6) null default null,
    october_target_price  numeric(24,6) null default null,
    november_target_price numeric(24,6) null default null,
    december_target_price numeric(24,6) null default null,
    creator               varchar(64)   null default '',
    create_time           timestamp     not null default CURRENT_TIMESTAMP,
    updater               varchar(64)   null default '',
    update_time           timestamp     not null default CURRENT_TIMESTAMP,
    deleted               int2          not null default 0,
    tenant_id             bigint        not null default 0,
    primary key (id)
);

-- ---------------------------------------------------------------------------------------------------
-- The tables that were already created. `create table if not exists` leaves an existing table
-- completely alone, so a column that was typed as a number before the correction above stays a
-- number — and a database provisioned earlier keeps failing the follow-up-count queries even though
-- this file now says otherwise. This block converts exactly those columns, and only the ones still
-- numeric, so it is a no-op on a fresh install and idempotent on an existing one.
--
-- 已经建出来的那些表。`create table if not exists` 对已存在的表**完全不碰**，所以上面改正之前建成数值
-- 类型的列仍然是数值类型——而早先装好的库，即便本文件已经改了，跟进计数的查询照样失败。这一段只转换那些列、
-- 且只转换其中仍是数值的，故对全新安装是**空操作**、对既有库**幂等**。

do $$
declare r record;
begin
  for r in
    select c.table_name, c.column_name
      from information_schema.columns c
     where c.table_schema = 'public'
       and c.data_type = 'smallint'
       and (c.table_name, c.column_name) in (
             ('crm_customer', 'follow_up_status'), ('crm_customer', 'lock_status'),
             ('crm_customer', 'deal_status'), ('crm_customer_limit_config', 'deal_count_enabled'),
             ('crm_business', 'follow_up_status'), ('crm_clue', 'follow_up_status'),
             ('crm_clue', 'transform_status'), ('crm_contact', 'master'),
             ('crm_contract_config', 'notify_enabled'), ('crm_customer_pool_config', 'enabled'),
             ('crm_customer_pool_config', 'notify_enabled'))
  loop
    -- The numeric default has to go first: PostgreSQL will not cast a `default 0` to boolean as part
    -- of the type change, and says so rather than guessing.
    -- 那个数值默认值必须先去掉：PostgreSQL 不会在改类型时顺带把 `default 0` 转成布尔，它会明说而不是猜。
    execute format('alter table %I alter column %I drop default', r.table_name, r.column_name);
    execute format('alter table %I alter column %I type boolean using (%I <> 0)',
                   r.table_name, r.column_name, r.column_name);
    execute format('alter table %I alter column %I set default false', r.table_name, r.column_name);
    raise notice 'converted %.% to boolean', r.table_name, r.column_name;
  end loop;
end $$;
