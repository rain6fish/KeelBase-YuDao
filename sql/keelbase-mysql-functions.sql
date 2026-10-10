-- SPDX-License-Identifier: Apache-2.0
--
-- MySQL's date-part functions, defined for PostgreSQL.
--
-- The modules this line enabled are written against MySQL, and a few of their queries build SQL in
-- Java rather than in a mapper file — `MONTH(birthday) = ?` on the employee table is one. Those names
-- do not exist in PostgreSQL (which spells them `extract(month from …)`), so the query fails with
-- `function month(timestamp) does not exist` and the page that issues it answers 500.
--
-- The alternative is to edit upstream's Java, which this line does not do: its whole claim is that it
-- wires up somebody else's platform without rewriting it. So the names are supplied here instead —
-- six small functions, with MySQL's own semantics spelled out rather than assumed.
--
-- `WEEK` is the one worth reading twice: MySQL's `WEEK(d)` defaults to mode 0 — weeks start on Sunday,
-- numbered 0–53, with week 0 being the partial week before the first Sunday — which is *not*
-- PostgreSQL's `extract(week …)` (ISO: weeks start Monday, numbered 1–53). The formula below is
-- MySQL's mode 0, so a weekly figure computed here means what it means upstream.
--
-- Idempotent: every statement is `create or replace`.
--
-- MySQL 的日期分量函数，为 PostgreSQL 定义一份。
--
-- 本线启用的这些模块是照 MySQL 写的，其中少数查询的 SQL 是在 **Java 里拼**的、不在 mapper 文件里——
-- 「`MONTH(birthday) = ?`」就是一处。这些名字在 PostgreSQL 里**不存在**（那边写作
-- `extract(month from …)`），于是查询报 `function month(timestamp) does not exist`、发起它的页面答 500。
--
-- 另一条路是改上游的 Java，而本线**不做这件事**：它整个主张就是「接别人原样的平台而不重写它」。所以在这里
-- 把名字补上——六个小函数，语义按 MySQL 的写法**写清楚**、而不是假定。
--
-- 其中 `WEEK` 值得读两遍：MySQL 的 `WEEK(d)` 默认是 **mode 0**——周从**周日**起、编号 **0–53**，
-- 第 0 周是第一个周日之前那段不完整的周——这**不是** PostgreSQL 的 `extract(week …)`（ISO：周一起、
-- 编号 1–53）。下面的公式是 MySQL 的 mode 0，于是在这里算出的周数，与上游的含义一致。
--
-- 幂等：每条语句都是 `create or replace`。

create or replace function month(d timestamp) returns int
  language sql immutable as $$ select extract(month from d)::int $$;

create or replace function year(d timestamp) returns int
  language sql immutable as $$ select extract(year from d)::int $$;

create or replace function day(d timestamp) returns int
  language sql immutable as $$ select extract(day from d)::int $$;

create or replace function dayofmonth(d timestamp) returns int
  language sql immutable as $$ select extract(day from d)::int $$;

create or replace function quarter(d timestamp) returns int
  language sql immutable as $$ select ((extract(month from d)::int - 1) / 3) + 1 $$;

-- MySQL's WEEK(d) — mode 0: Sunday first, 0–53.
-- MySQL 的 WEEK(d)——mode 0：周日为一周之首，0–53。
create or replace function week(d timestamp) returns int
  language sql immutable as $$
    select floor((extract(doy from d)::int + 5 - extract(dow from d)::int) / 7)::int
  $$;

-- The same names for `date` arguments, so a date column resolves without a cast.
-- 给 `date` 参数的同名函数，好让日期列不必强转就能解析。
create or replace function month(d date) returns int
  language sql immutable as $$ select extract(month from d)::int $$;

create or replace function year(d date) returns int
  language sql immutable as $$ select extract(year from d)::int $$;

create or replace function day(d date) returns int
  language sql immutable as $$ select extract(day from d)::int $$;

create or replace function dayofmonth(d date) returns int
  language sql immutable as $$ select extract(day from d)::int $$;

create or replace function quarter(d date) returns int
  language sql immutable as $$ select ((extract(month from d)::int - 1) / 3) + 1 $$;

create or replace function week(d date) returns int
  language sql immutable as $$
    select floor((extract(doy from d)::int + 5 - extract(dow from d)::int) / 7)::int
  $$;
