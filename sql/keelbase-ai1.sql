-- SPDX-License-Identifier: Apache-2.0
--
-- DERIVED, not upstream's script — the same shape as `keelbase-crm.sql`. Yudao publishes no DDL for
-- `yudao-module-ai1-springai` either; the module is compiled and enabled, and until this file runs its
-- endpoints answer 500 because its tables do not exist. Every table below is read off upstream's own
-- data objects under `dal/dataobject/`, and typed following this line's PostgreSQL conventions
-- (`int8`/`int4`/`int2`, `timestamp`, numeric flags).
--
-- Two things are worth naming rather than leaving in the types. All ten DOs extend `TenantBaseDO`, so
-- every table carries `tenant_id` and the host's tenant interceptor filters it. And the JSON-shaped
-- fields — `headers`, `config`, an agent's three id lists — are `text`, because MyBatis-Plus stores them
-- through a type handler as a JSON string; the columns only have to hold it back.
--
-- Column lengths are chosen, not copied: upstream never wrote them down here, so they are generous
-- rather than exact, and that is the one thing in this file that is a judgement call.
--
-- 推导而来、**不是上游的脚本**——与 `keelbase-crm.sql` 同形。芋道同样**不发布** `yudao-module-ai1-springai`
-- 的 DDL：该模块被编译、被启用，而在本文件跑起来之前，它的端点一律答 500，因为它的表不存在。下面每张表都取自上游
-- `dal/dataobject/` 下**它自己的** data object，类型照本线 PostgreSQL 的惯例（`int8`/`int4`/`int2`、`timestamp`、
-- 数字标志）。
--
-- 两件事要点名，而不是留在类型里。十个 DO **全部**继承 `TenantBaseDO`，故每张表都带 `tenant_id`、由宿主的租户
-- 拦截器过滤。而 JSON 形状的那几个字段——`headers`、`config`、agent 的三份 id 清单——都是 `text`，因为
-- MyBatis-Plus 经类型处理器把它们按 JSON 字符串存；列只要能装回去就行。
--
-- **列长是选的、不是抄的**：上游在这里从未写下它们，故取值**偏宽而非精确**——这是本文件里唯一一处判断。

create sequence if not exists ai1_provider_seq;
create sequence if not exists ai1_model_seq;
create sequence if not exists ai1_mcp_seq;
create sequence if not exists ai1_agent_seq;
create sequence if not exists ai1_session_seq;
create sequence if not exists ai1_session_message_seq;
create sequence if not exists ai1_knowledge_base_seq;
create sequence if not exists ai1_knowledge_document_seq;
create sequence if not exists ai1_skill_seq;
create sequence if not exists ai1_skill_file_seq;

create table if not exists ai1_provider (
    id          bigint        not null,
    name        varchar(255)  not null default '',
    base_url    varchar(1024) null default null,
    api_key     varchar(1024) null default null,
    headers     text          null default null,
    status      int4          not null default 0,
    remark      varchar(500)  null default null,
    creator     varchar(64)   null default '',
    create_time timestamp     not null default CURRENT_TIMESTAMP,
    updater     varchar(64)   null default '',
    update_time timestamp     not null default CURRENT_TIMESTAMP,
    deleted     int2          not null default 0,
    tenant_id   bigint        not null default 0,
    primary key (id)
);

create table if not exists ai1_model (
    id          bigint       not null,
    provider_id bigint       not null,
    name        varchar(255) not null default '',
    model       varchar(255) not null default '',
    type        int4         not null default 0,
    status      int4         not null default 0,
    creator     varchar(64)  null default '',
    create_time timestamp    not null default CURRENT_TIMESTAMP,
    updater     varchar(64)  null default '',
    update_time timestamp    not null default CURRENT_TIMESTAMP,
    deleted     int2         not null default 0,
    tenant_id   bigint       not null default 0,
    primary key (id)
);

create table if not exists ai1_mcp (
    id          bigint        not null,
    name        varchar(255)  not null default '',
    transport   varchar(64)   null default null,
    url         varchar(1024) null default null,
    headers     text          null default null,
    config      text          null default null,
    status      int4          not null default 0,
    remark      varchar(500)  null default null,
    creator     varchar(64)   null default '',
    create_time timestamp     not null default CURRENT_TIMESTAMP,
    updater     varchar(64)   null default '',
    update_time timestamp     not null default CURRENT_TIMESTAMP,
    deleted     int2          not null default 0,
    tenant_id   bigint        not null default 0,
    primary key (id)
);

create table if not exists ai1_agent (
    id                 bigint        not null,
    name               varchar(255)  not null default '',
    introduction       varchar(1024) null default null,
    provider_id        bigint        null default null,
    model_id           bigint        null default null,
    system_prompt      text          null default null,
    knowledge_base_ids text          null default null,
    mcp_ids            text          null default null,
    skill_ids          text          null default null,
    status             int4          not null default 0,
    creator            varchar(64)   null default '',
    create_time        timestamp     not null default CURRENT_TIMESTAMP,
    updater            varchar(64)   null default '',
    update_time        timestamp     not null default CURRENT_TIMESTAMP,
    deleted            int2          not null default 0,
    tenant_id          bigint        not null default 0,
    primary key (id)
);

create table if not exists ai1_session (
    id          bigint       not null,
    agent_id    bigint       null default null,
    user_id     bigint       null default null,
    title       varchar(255) null default null,
    creator     varchar(64)  null default '',
    create_time timestamp    not null default CURRENT_TIMESTAMP,
    updater     varchar(64)  null default '',
    update_time timestamp    not null default CURRENT_TIMESTAMP,
    deleted     int2         not null default 0,
    tenant_id   bigint       not null default 0,
    primary key (id)
);

create table if not exists ai1_session_message (
    id          bigint      not null,
    session_id  bigint      not null,
    role        varchar(32) null default null,
    reasoning   text        null default null,
    content     text        null default null,
    status      int4        not null default 0,
    creator     varchar(64) null default '',
    create_time timestamp   not null default CURRENT_TIMESTAMP,
    updater     varchar(64) null default '',
    update_time timestamp   not null default CURRENT_TIMESTAMP,
    deleted     int2        not null default 0,
    tenant_id   bigint      not null default 0,
    primary key (id)
);

create table if not exists ai1_knowledge_base (
    id                    bigint        not null,
    name                  varchar(255)  not null default '',
    description           varchar(1024) null default null,
    embedding_provider_id bigint        null default null,
    embedding_model_id    bigint        null default null,
    chunk_size            int4          null default null,
    chunk_overlap         int4          null default null,
    top_k                 int4          null default null,
    status                int4          not null default 0,
    creator               varchar(64)   null default '',
    create_time           timestamp     not null default CURRENT_TIMESTAMP,
    updater               varchar(64)   null default '',
    update_time           timestamp     not null default CURRENT_TIMESTAMP,
    deleted               int2          not null default 0,
    tenant_id             bigint        not null default 0,
    primary key (id)
);

create table if not exists ai1_knowledge_document (
    id                bigint       not null,
    knowledge_base_id bigint       not null,
    name              varchar(255) null default null,
    content           text         null default null,
    chunk_count       int4         null default null,
    status            int4         not null default 0,
    creator           varchar(64)  null default '',
    create_time       timestamp    not null default CURRENT_TIMESTAMP,
    updater           varchar(64)  null default '',
    update_time       timestamp    not null default CURRENT_TIMESTAMP,
    deleted           int2         not null default 0,
    tenant_id         bigint       not null default 0,
    primary key (id)
);

create table if not exists ai1_skill (
    id          bigint        not null,
    name        varchar(255)  not null default '',
    description varchar(1024) null default null,
    version     varchar(64)   null default null,
    status      int4          not null default 0,
    creator     varchar(64)   null default '',
    create_time timestamp     not null default CURRENT_TIMESTAMP,
    updater     varchar(64)   null default '',
    update_time timestamp     not null default CURRENT_TIMESTAMP,
    deleted     int2          not null default 0,
    tenant_id   bigint        not null default 0,
    primary key (id)
);

create table if not exists ai1_skill_file (
    id          bigint       not null,
    skill_id    bigint       not null,
    parent_id   bigint       null default null,
    name        varchar(255) null default null,
    type        int4         null default null,
    file_type   varchar(64)  null default null,
    content     text         null default null,
    locked      int2         not null default 0,
    sort        int4         not null default 0,
    creator     varchar(64)  null default '',
    create_time timestamp    not null default CURRENT_TIMESTAMP,
    updater     varchar(64)  null default '',
    update_time timestamp    not null default CURRENT_TIMESTAMP,
    deleted     int2         not null default 0,
    tenant_id   bigint       not null default 0,
    primary key (id)
);
