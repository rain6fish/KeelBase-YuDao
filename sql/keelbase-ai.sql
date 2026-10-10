-- SPDX-License-Identifier: Apache-2.0
--
-- Module: ai. The tables this module's pages read.
--
-- They come from upstream's own test schema for the module — which is written in a mix of dialects —
-- converted to what this line's PostgreSQL takes. The conversion is a dialect one only: `tinyint` to
-- `int2`, `bit` to `boolean` (except `deleted`, which the host compares as a number), `datetime` /
-- `longtext` / `clob`, the auto-increment column, the generated columns. No business meaning is
-- touched. Where the module's test schema does not cover a table its own data objects declare — and
-- for `ai`, which ships no schema at all — the table is derived from the data object instead, the
-- same way `keelbase-crm.sql` was.
--
-- Each table's key sequence comes from its own data object's `@KeySequence` — **not** from the table
-- name plus `_seq`. The two often differ, and the host asks for the sequence by name.
--
-- Idempotent: every statement is `create … if not exists`.
--
-- 模块：ai。该模块的页面所读的表。
--
-- 它们来自上游给这个模块的测试 schema（那份 schema 是混合方言写成的），转成本线 PostgreSQL 能收的样子。
-- 转换只做方言层的事：`tinyint`→`int2`、`bit`→`boolean`（`deleted` 除外，宿主把它当数字比）、
-- `datetime`/`longtext`/`clob`、自增列、生成列。不动任何业务语义。凡模块自己的数据对象声明了、而它的
-- 测试 schema 没覆盖的表——以及**完全没有 schema** 的 `ai`——就照 `keelbase-crm.sql` 的办法从数据对象
-- 推导。
--
-- 每张表的主键序列取自它自己数据对象上的 `@KeySequence`——**不是**表名加 `_seq`。两者常常不同，
-- 而宿主是**按名字**要那个序列的。
--
-- 幂等：每条语句都是 `create … if not exists`。
create sequence if not exists ai_api_key_seq;
create sequence if not exists ai_chat_conversation_seq;
create sequence if not exists ai_chat_message_seq;
create sequence if not exists ai_chat_role_seq;
create sequence if not exists ai_image_seq;
create sequence if not exists ai_knowledge_document_seq;
create sequence if not exists ai_knowledge_segment_seq;
create sequence if not exists ai_knowledge_seq;
create sequence if not exists ai_mind_map_seq;
create sequence if not exists ai_model_seq;
create sequence if not exists ai_music_seq;
create sequence if not exists ai_tool_seq;
create sequence if not exists ai_workflow_seq;
create sequence if not exists ai_write_seq;

create table if not exists ai_chat_conversation (
    id             bigint        not null,
    user_id        bigint null default null,
    title          varchar(255) null default null,
    pinned         boolean null default null,
    pinned_time    timestamp null default null,
    role_id        bigint null default null,
    model_id       bigint null default null,
    model          varchar(64) null default null,
    system_message text null default null,
    temperature    float8 null default null,
    max_tokens     int4 null default null,
    max_contexts   int4 null default null,
    creator            varchar(64) null default '',
    create_time    timestamp   not null default CURRENT_TIMESTAMP,
    updater            varchar(64) null default '',
    update_time    timestamp   not null default CURRENT_TIMESTAMP,
    deleted            int2        not null default 0,
    tenant_id        bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_chat_message (
    id                bigint        not null,
    conversation_id   bigint null default null,
    reply_id          bigint null default null,
    type              varchar(64) null default null,
    user_id           bigint null default null,
    role_id           bigint null default null,
    model             varchar(64) null default null,
    model_id          bigint null default null,
    content           text null default null,
    reasoning_content text null default null,
    use_context       boolean null default null,
    segment_ids       text null default null,
    web_search_pages  text null default null,
    attachment_urls   text null default null,
    creator               varchar(64) null default '',
    create_time       timestamp   not null default CURRENT_TIMESTAMP,
    updater               varchar(64) null default '',
    update_time       timestamp   not null default CURRENT_TIMESTAMP,
    deleted               int2        not null default 0,
    tenant_id           bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_image (
    id            bigint        not null,
    user_id       bigint null default null,
    prompt        text null default null,
    platform      varchar(64) null default null,
    model_id      bigint null default null,
    model         varchar(64) null default null,
    width         int4 null default null,
    height        int4 null default null,
    status        int4 null default null,
    finish_time   timestamp null default null,
    error_message text null default null,
    pic_url       text null default null,
    public_status boolean null default null,
    options       text null default null,
    buttons       text null default null,
    task_id       text null default null,
    creator           varchar(64) null default '',
    create_time   timestamp   not null default CURRENT_TIMESTAMP,
    updater           varchar(64) null default '',
    update_time   timestamp   not null default CURRENT_TIMESTAMP,
    deleted           int2        not null default 0,
    tenant_id       bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_knowledge (
    id                   bigint        not null,
    name                 varchar(255) null default null,
    description          text null default null,
    embedding_model_id   bigint null default null,
    embedding_model      text null default null,
    top_k                int4 null default null,
    similarity_threshold float8 null default null,
    status               int4 null default null,
    creator                  varchar(64) null default '',
    create_time          timestamp   not null default CURRENT_TIMESTAMP,
    updater                  varchar(64) null default '',
    update_time          timestamp   not null default CURRENT_TIMESTAMP,
    deleted                  int2        not null default 0,
    tenant_id              bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_knowledge_document (
    id                 bigint        not null,
    knowledge_id       bigint null default null,
    name               varchar(255) null default null,
    url                text null default null,
    content            text null default null,
    content_length     int4 null default null,
    tokens             int4 null default null,
    segment_max_tokens int4 null default null,
    retrieval_count    int4 null default null,
    status             int4 null default null,
    creator                varchar(64) null default '',
    create_time        timestamp   not null default CURRENT_TIMESTAMP,
    updater                varchar(64) null default '',
    update_time        timestamp   not null default CURRENT_TIMESTAMP,
    deleted                int2        not null default 0,
    tenant_id            bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_knowledge_segment (
    id              bigint        not null,
    knowledge_id    bigint null default null,
    document_id     bigint null default null,
    content         text null default null,
    content_length  int4 null default null,
    vector_id       text null default null,
    tokens          int4 null default null,
    retrieval_count int4 null default null,
    status          int4 null default null,
    creator             varchar(64) null default '',
    create_time     timestamp   not null default CURRENT_TIMESTAMP,
    updater             varchar(64) null default '',
    update_time     timestamp   not null default CURRENT_TIMESTAMP,
    deleted             int2        not null default 0,
    tenant_id         bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_mind_map (
    id                bigint        not null,
    user_id           bigint null default null,
    platform          varchar(64) null default null,
    model_id          bigint null default null,
    model             varchar(64) null default null,
    prompt            text null default null,
    generated_content text null default null,
    error_message     text null default null,
    creator               varchar(64) null default '',
    create_time       timestamp   not null default CURRENT_TIMESTAMP,
    updater               varchar(64) null default '',
    update_time       timestamp   not null default CURRENT_TIMESTAMP,
    deleted               int2        not null default 0,
    tenant_id           bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_api_key (
    id       bigint        not null,
    name     varchar(255) null default null,
    api_key  text null default null,
    platform varchar(64) null default null,
    url      text null default null,
    status   int4 null default null,
    creator      varchar(64) null default '',
    create_time timestamp   not null default CURRENT_TIMESTAMP,
    updater      varchar(64) null default '',
    update_time timestamp   not null default CURRENT_TIMESTAMP,
    deleted      int2        not null default 0,
    tenant_id   bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_chat_role (
    id               bigint        not null,
    name             varchar(255) null default null,
    avatar           text null default null,
    category         text null default null,
    description      text null default null,
    system_message   text null default null,
    user_id          bigint null default null,
    model_id         bigint null default null,
    knowledge_ids    text null default null,
    tool_ids         text null default null,
    mcp_client_names text null default null,
    public_status    boolean null default null,
    sort             int4 null default null,
    status           int4 null default null,
    creator              varchar(64) null default '',
    create_time      timestamp   not null default CURRENT_TIMESTAMP,
    updater              varchar(64) null default '',
    update_time      timestamp   not null default CURRENT_TIMESTAMP,
    deleted              int2        not null default 0,
    tenant_id          bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_model (
    id           bigint        not null,
    key_id       bigint null default null,
    name         varchar(255) null default null,
    model        varchar(64) null default null,
    platform     varchar(64) null default null,
    type         int4 null default null,
    sort         int4 null default null,
    status       int4 null default null,
    temperature  float8 null default null,
    max_tokens   int4 null default null,
    max_contexts int4 null default null,
    creator          varchar(64) null default '',
    create_time  timestamp   not null default CURRENT_TIMESTAMP,
    updater          varchar(64) null default '',
    update_time  timestamp   not null default CURRENT_TIMESTAMP,
    deleted          int2        not null default 0,
    tenant_id      bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_tool (
    id          bigint        not null,
    name        varchar(255) null default null,
    description text null default null,
    status      int4 null default null,
    creator         varchar(64) null default '',
    create_time timestamp   not null default CURRENT_TIMESTAMP,
    updater         varchar(64) null default '',
    update_time timestamp   not null default CURRENT_TIMESTAMP,
    deleted         int2        not null default 0,
    tenant_id     bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_music (
    id            bigint        not null,
    user_id       bigint null default null,
    title         varchar(255) null default null,
    lyric         text null default null,
    image_url     text null default null,
    audio_url     text null default null,
    video_url     text null default null,
    status        int4 null default null,
    generate_mode int4 null default null,
    description   text null default null,
    platform      varchar(64) null default null,
    model         varchar(64) null default null,
    tags          text null default null,
    duration      float8 null default null,
    public_status boolean null default null,
    task_id       text null default null,
    error_message text null default null,
    creator           varchar(64) null default '',
    create_time   timestamp   not null default CURRENT_TIMESTAMP,
    updater           varchar(64) null default '',
    update_time   timestamp   not null default CURRENT_TIMESTAMP,
    deleted           int2        not null default 0,
    tenant_id       bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_workflow (
    id     bigint        not null,
    name   varchar(255) null default null,
    code   varchar(64) null default null,
    graph  text null default null,
    remark text null default null,
    status int4 null default null,
    creator     varchar(64) null default '',
    create_time timestamp   not null default CURRENT_TIMESTAMP,
    updater     varchar(64) null default '',
    update_time timestamp   not null default CURRENT_TIMESTAMP,
    deleted     int2        not null default 0,
    tenant_id   bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_write (
    id                bigint        not null,
    user_id           bigint null default null,
    type              int4 null default null,
    platform          varchar(64) null default null,
    model_id          bigint null default null,
    model             varchar(64) null default null,
    prompt            text null default null,
    generated_content text null default null,
    original_content  text null default null,
    length            int4 null default null,
    format            int4 null default null,
    tone              int4 null default null,
    language          int4 null default null,
    error_message     text null default null,
    creator               varchar(64) null default '',
    create_time       timestamp   not null default CURRENT_TIMESTAMP,
    updater               varchar(64) null default '',
    update_time       timestamp   not null default CURRENT_TIMESTAMP,
    deleted               int2        not null default 0,
    tenant_id           bigint      not null default 0,
    primary key (id)
);


create table if not exists ai_chat_conversation (
    id             bigint        not null,
    user_id        bigint null default null,
    title          varchar(255) null default null,
    pinned         boolean null default null,
    pinned_time    timestamp null default null,
    role_id        bigint null default null,
    model_id       bigint null default null,
    model          varchar(64) null default null,
    system_message text null default null,
    temperature    float8 null default null,
    max_tokens     int4 null default null,
    max_contexts   int4 null default null,
    creator            varchar(64) null default '',
    create_time    timestamp   not null default CURRENT_TIMESTAMP,
    updater            varchar(64) null default '',
    update_time    timestamp   not null default CURRENT_TIMESTAMP,
    deleted            int2        not null default 0,
    tenant_id        bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_chat_message (
    id                bigint        not null,
    conversation_id   bigint null default null,
    reply_id          bigint null default null,
    type              varchar(64) null default null,
    user_id           bigint null default null,
    role_id           bigint null default null,
    model             varchar(64) null default null,
    model_id          bigint null default null,
    content           text null default null,
    reasoning_content text null default null,
    use_context       boolean null default null,
    segment_ids       text null default null,
    web_search_pages  text null default null,
    attachment_urls   text null default null,
    creator               varchar(64) null default '',
    create_time       timestamp   not null default CURRENT_TIMESTAMP,
    updater               varchar(64) null default '',
    update_time       timestamp   not null default CURRENT_TIMESTAMP,
    deleted               int2        not null default 0,
    tenant_id           bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_image (
    id            bigint        not null,
    user_id       bigint null default null,
    prompt        text null default null,
    platform      varchar(64) null default null,
    model_id      bigint null default null,
    model         varchar(64) null default null,
    width         int4 null default null,
    height        int4 null default null,
    status        int4 null default null,
    finish_time   timestamp null default null,
    error_message text null default null,
    pic_url       text null default null,
    public_status boolean null default null,
    options       text null default null,
    buttons       text null default null,
    task_id       text null default null,
    creator           varchar(64) null default '',
    create_time   timestamp   not null default CURRENT_TIMESTAMP,
    updater           varchar(64) null default '',
    update_time   timestamp   not null default CURRENT_TIMESTAMP,
    deleted           int2        not null default 0,
    tenant_id       bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_knowledge (
    id                   bigint        not null,
    name                 varchar(255) null default null,
    description          text null default null,
    embedding_model_id   bigint null default null,
    embedding_model      text null default null,
    top_k                int4 null default null,
    similarity_threshold float8 null default null,
    status               int4 null default null,
    creator                  varchar(64) null default '',
    create_time          timestamp   not null default CURRENT_TIMESTAMP,
    updater                  varchar(64) null default '',
    update_time          timestamp   not null default CURRENT_TIMESTAMP,
    deleted                  int2        not null default 0,
    tenant_id              bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_knowledge_document (
    id                 bigint        not null,
    knowledge_id       bigint null default null,
    name               varchar(255) null default null,
    url                text null default null,
    content            text null default null,
    content_length     int4 null default null,
    tokens             int4 null default null,
    segment_max_tokens int4 null default null,
    retrieval_count    int4 null default null,
    status             int4 null default null,
    creator                varchar(64) null default '',
    create_time        timestamp   not null default CURRENT_TIMESTAMP,
    updater                varchar(64) null default '',
    update_time        timestamp   not null default CURRENT_TIMESTAMP,
    deleted                int2        not null default 0,
    tenant_id            bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_knowledge_segment (
    id              bigint        not null,
    knowledge_id    bigint null default null,
    document_id     bigint null default null,
    content         text null default null,
    content_length  int4 null default null,
    vector_id       text null default null,
    tokens          int4 null default null,
    retrieval_count int4 null default null,
    status          int4 null default null,
    creator             varchar(64) null default '',
    create_time     timestamp   not null default CURRENT_TIMESTAMP,
    updater             varchar(64) null default '',
    update_time     timestamp   not null default CURRENT_TIMESTAMP,
    deleted             int2        not null default 0,
    tenant_id         bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_mind_map (
    id                bigint        not null,
    user_id           bigint null default null,
    platform          varchar(64) null default null,
    model_id          bigint null default null,
    model             varchar(64) null default null,
    prompt            text null default null,
    generated_content text null default null,
    error_message     text null default null,
    creator               varchar(64) null default '',
    create_time       timestamp   not null default CURRENT_TIMESTAMP,
    updater               varchar(64) null default '',
    update_time       timestamp   not null default CURRENT_TIMESTAMP,
    deleted               int2        not null default 0,
    tenant_id           bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_api_key (
    id       bigint        not null,
    name     varchar(255) null default null,
    api_key  text null default null,
    platform varchar(64) null default null,
    url      text null default null,
    status   int4 null default null,
    creator      varchar(64) null default '',
    create_time timestamp   not null default CURRENT_TIMESTAMP,
    updater      varchar(64) null default '',
    update_time timestamp   not null default CURRENT_TIMESTAMP,
    deleted      int2        not null default 0,
    tenant_id   bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_chat_role (
    id               bigint        not null,
    name             varchar(255) null default null,
    avatar           text null default null,
    category         text null default null,
    description      text null default null,
    system_message   text null default null,
    user_id          bigint null default null,
    model_id         bigint null default null,
    knowledge_ids    text null default null,
    tool_ids         text null default null,
    mcp_client_names text null default null,
    public_status    boolean null default null,
    sort             int4 null default null,
    status           int4 null default null,
    creator              varchar(64) null default '',
    create_time      timestamp   not null default CURRENT_TIMESTAMP,
    updater              varchar(64) null default '',
    update_time      timestamp   not null default CURRENT_TIMESTAMP,
    deleted              int2        not null default 0,
    tenant_id          bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_model (
    id           bigint        not null,
    key_id       bigint null default null,
    name         varchar(255) null default null,
    model        varchar(64) null default null,
    platform     varchar(64) null default null,
    type         int4 null default null,
    sort         int4 null default null,
    status       int4 null default null,
    temperature  float8 null default null,
    max_tokens   int4 null default null,
    max_contexts int4 null default null,
    creator          varchar(64) null default '',
    create_time  timestamp   not null default CURRENT_TIMESTAMP,
    updater          varchar(64) null default '',
    update_time  timestamp   not null default CURRENT_TIMESTAMP,
    deleted          int2        not null default 0,
    tenant_id      bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_tool (
    id          bigint        not null,
    name        varchar(255) null default null,
    description text null default null,
    status      int4 null default null,
    creator         varchar(64) null default '',
    create_time timestamp   not null default CURRENT_TIMESTAMP,
    updater         varchar(64) null default '',
    update_time timestamp   not null default CURRENT_TIMESTAMP,
    deleted         int2        not null default 0,
    tenant_id     bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_music (
    id            bigint        not null,
    user_id       bigint null default null,
    title         varchar(255) null default null,
    lyric         text null default null,
    image_url     text null default null,
    audio_url     text null default null,
    video_url     text null default null,
    status        int4 null default null,
    generate_mode int4 null default null,
    description   text null default null,
    platform      varchar(64) null default null,
    model         varchar(64) null default null,
    tags          text null default null,
    duration      float8 null default null,
    public_status boolean null default null,
    task_id       text null default null,
    error_message text null default null,
    creator           varchar(64) null default '',
    create_time   timestamp   not null default CURRENT_TIMESTAMP,
    updater           varchar(64) null default '',
    update_time   timestamp   not null default CURRENT_TIMESTAMP,
    deleted           int2        not null default 0,
    tenant_id       bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_workflow (
    id     bigint        not null,
    name   varchar(255) null default null,
    code   varchar(64) null default null,
    graph  text null default null,
    remark text null default null,
    status int4 null default null,
    creator     varchar(64) null default '',
    create_time timestamp   not null default CURRENT_TIMESTAMP,
    updater     varchar(64) null default '',
    update_time timestamp   not null default CURRENT_TIMESTAMP,
    deleted     int2        not null default 0,
    tenant_id   bigint      not null default 0,
    primary key (id)
);

create table if not exists ai_write (
    id                bigint        not null,
    user_id           bigint null default null,
    type              int4 null default null,
    platform          varchar(64) null default null,
    model_id          bigint null default null,
    model             varchar(64) null default null,
    prompt            text null default null,
    generated_content text null default null,
    original_content  text null default null,
    length            int4 null default null,
    format            int4 null default null,
    tone              int4 null default null,
    language          int4 null default null,
    error_message     text null default null,
    creator               varchar(64) null default '',
    create_time       timestamp   not null default CURRENT_TIMESTAMP,
    updater               varchar(64) null default '',
    update_time       timestamp   not null default CURRENT_TIMESTAMP,
    deleted               int2        not null default 0,
    tenant_id           bigint      not null default 0,
    primary key (id)
);
