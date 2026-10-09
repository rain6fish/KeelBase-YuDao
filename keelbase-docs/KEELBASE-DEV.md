# KeelBase host — building and running this line

> Chinese: [KEELBASE-DEV.zh-CN.md](KEELBASE-DEV.zh-CN.md)

This is the **development note** for this repository: what toolchain it needs, how to build it, and how
to run it against a datastore. The line's identity is in [KEELBASE-HOST.md](KEELBASE-HOST.md).

## Toolchain — two JDKs at once, on purpose

The platform here compiles to **Java 25**. `keelbase4j-core` — the runtime this repository embeds —
targets **Java 17**. Both have to be usable at once, and this machine's default JDK is 17.

`scripts/jdk25.sh` resolves a JDK 25 **for one command** and leaves `JAVA_HOME` and `PATH` alone. That
last part is not politeness: several sessions share this machine, and moving the default JDK would
change what everyone else builds with.

```bash
bash scripts/jdk25.sh                     # report the resolved JDK, change nothing
bash scripts/jdk25.sh java -version       # run one command on 25
```

Resolution order, first hit wins: `$KEELBASE_JDK25` → `$JDK25_HOME` → `/d/Develop/jdk-25` → download
from the Adoptium API into `${KEELBASE_JDK25_DIR:-/d/Develop}`. Nothing is installed unless all three
existing locations fail.

**Why not just install it system-wide:** the core's own rule is that its products build on Java 17.
This host is the exception, so the exception is scoped to the commands that need it.

## Build

```bash
bash scripts/jdk25.sh mvn -pl yudao-server -am -DskipTests -Dmaven.test.skip=true package
```

`-pl yudao-server -am` builds the server and what it depends on, and skips the fifteen business modules
that are not wired into the server. The artifact is a fat jar under `yudao-server/target/`.

## Run

`scripts/run-server.sh` is the whole thing in one command, and it is what the verification above was run
with:

```bash
bash scripts/run-server.sh
```

The datastore and the runtime's own settings are passed as **command-line parameters**, not written into
upstream's `application-local.yaml`. Two reasons: upstream's file stays untouched, and the same checkout
can be pointed at a different stack without editing anything.

| Parameter | Why it is needed |
|---|---|
| `--spring.datasource.dynamic.datasource.master.url` / `.username` / `.password` | the platform's datastore |
| `--spring.data.redis.host` / `.port` | the platform's cache |
| `--spring.flyway.baseline-on-migrate=true` `--spring.flyway.baseline-version=0` | the platform's schema was loaded by hand, so the core's Flyway needs a baseline — and the version must be `0`, or the core's **V1 is skipped** and a table goes missing silently |
| `--keelbase.audit.hmac-key=…` | the runtime's audit chain; with no `application.yml` of its own when embedded, this fails startup without it |
| `--keelbase.wire.exclude-packages=cn.iocoder.yudao` | keeps the runtime's response envelope off this host's own controllers |
| `--yudao.tenant.ignore-tables=…` | names the tables **KeelBase governs**, so the host's tenant interceptor does not narrow them too (one authority per row — see the line's decision index) |
| `--spring.datasource.dynamic.druid.validation-query="SELECT 1"` | the host's own value is `SELECT 1 FROM DUAL`, which is MySQL — PostgreSQL has no `DUAL` |
| `--spring.datasource.dynamic.datasource.master.type=com.zaxxer.hikari.HikariDataSource` | **the pool.** See below — with Druid, about half of all logins fail |

## The pool had to be swapped, and why

On PostgreSQL and **Druid**, about half of all logins answered 500:

```
org.postgresql.util.PSQLException: 这个 statement 已经被关闭   (this statement has already been closed)
  at org.postgresql.jdbc.PgStatement.checkClosed
  …  DruidPooledPreparedStatement.getMaxRows
  …  MybatisMapperMethod → AdminUserMapper.selectWithCursor
```

**On Hikari it does not happen at all** — twelve logins in a row, and the gate goes green. The one-line change is the `…master.type` parameter above, and it is a **command-line parameter**: no upstream file is edited for it.

Getting there took a wrong turn worth keeping. The first attempt concluded "Druid's prepared-statement cache is ruled out" — and reported that as settled. **It was a false green**: what had been verified is that the *property binds*, not that the *pool changed*. Reading the pool's own state (Druid's stat servlet at `/druid/datasource.json`) showed `PoolPreparedStatements = true` the whole time. And then the more useful finding:

> **The Druid pool cannot be configured from outside this application.** `spring.datasource.dynamic.druid.pool-prepared-statements=false` — as a command-line argument, as an external config file at higher precedence — does not change it; neither does the sibling prefix `spring.datasource.druid.*`; **nor does editing `application-local.yaml` and rebuilding**, after which the jar was verified to contain `false` while the pool still reported `true`.

So the whole class of "tune Druid with a property" fixes is a dead end here, and the next thing to try was not another property. `prepareThreshold=0` on the JDBC URL was tried too and did not help.

What the gate is for, the gate then did: on its first run against the fixed stack it found **two real dialect mismatches in the generated module** — a `boolean deleted` column that the host's logic-delete compares against `0` and `1` (its own schema writes `deleted int2 not null default 0`), and an `id` with no sequence behind it (the host's convention is `id int8 not null` plus a `<table>_seq`, and its generated DO carries `@KeySequence`). Both are fixed in the spec adapter, and both had been sitting in the module since the spike.

`--spring.datasource.dynamic.druid.validation-query="SELECT 1"` above is kept because PostgreSQL genuinely has no `DUAL`; it is **not** part of this fix, and does not claim to be.

**⚠ Two traps, both measured on this host** — see the spike record for the runs:

- Chinese piped into the `mysql` client through `docker exec -i` comes out mangled unless
  `--default-character-set=utf8mb4` is passed. It landed in **generated code comments** once.
- Chinese passed to `curl` as a command-line argument through this shell is corrupted; use
  `--data-binary @file`.

## Datastore

This line's datastore is **PostgreSQL with pgvector, plus Redis**, in `scripts/docker-compose.datastore.yml`.
Upstream ships a compose of its own that runs MySQL; that file is upstream's and is left alone — this is
ours, and it is what this line runs against.

```bash
bash scripts/bootstrap-datastore.sh          # create if absent, load if empty
RESET=1 bash scripts/bootstrap-datastore.sh  # drop and reload
```

**Verified end to end (2026-10-08).** The stack comes up, the extension reports `vector 0.8.7`, upstream's
converted schema loads to 49 tables, and the server starts against it: Flyway applies the runtime's four
migrations, `/auth/me` answers, a chat routes to a tool and reads a real row out of PostgreSQL, and the
audit chain verifies over its own rows.

**One thing the first run found, fixed in `yudao-server/pom.xml`:** upstream builds for MySQL and manages
no PostgreSQL driver, so the platform fails at startup with `ClassNotFoundException:
org.postgresql.Driver` until one is added. That dependency is now declared there.

One fact about the schema worth having before reading any failure: `sql/postgresql/ruoyi-vue-pro.sql` is
**machine-converted** from MySQL — its own header says so, and it carries leftovers such as a `dual`
table. It loaded cleanly here; a failure in it would be a fact about that converted file, not about this
line.

**⚠ Not covered:** pgvector being present is what lets the runtime's knowledge layer take its semantic
path, but that path also needs an embedding model, and none is configured here — so the **semantic path
itself has not been exercised**, only the extension's presence.

## Generating a governed module from a spec

```bash
node scripts/keelbase-gen-from-spec.mjs specs/products.json           # write
node scripts/keelbase-gen-from-spec.mjs specs/products.json --check   # compare, non-zero on drift
```

From a Business Spec it produces three files, all of them this project's rather than the host
generator's:

| Output | What it is |
|---|---|
| `sql/keelbase-<module>.sql` | the DDL that creates the table, with the governance columns and the host's base columns. **MySQL**, and the Chinese labels ride in `COMMENT` — this host's own generator reads them at import, so the naming pass disappears into the DDL |
| `…/keelbase/generated/<Entity>Tools.java` | the module's AI tool face: a read and a write, with their routes |
| `…/keelbase/generated/<Entity>Governance.java` | the module's row scope, as pointcuts over the module's own service |

**What it does not do** is generate the module's own CRUD. That is this host's code generator, driven
over its API — a separate step, because it needs a running stack, and it is why this half is
deterministic and offline.

**What `--check` proves, and what it does not.** It proves the files on disk are what the spec says.
It does **not** prove they are right — it compares against what the last run wrote, so a template bug
regenerates itself faithfully and still reports `no drift`. The first version of the DDL template
dropped the trailing commas and glued `comment` onto long constraints, producing invalid SQL that
`--check` called clean; what caught it was comparing against the spike's hand-made file.

## Upstream, and how to compare against it

`upstream` points at `https://github.com/YunaiV/ruoyi-vue-pro.git` — the canonical source; the
project's Gitee mirror is not configured here. It exists because **this repository's history was
started fresh**, which means the usual fork workflow is not available:

> **`git merge upstream/master-jdk25` and `git rebase` are refused** — the two histories share no
> ancestor (`refusing to merge unrelated histories`). This remote is for **diffing, patching and
> taking files**, not for merging — and not for pushing either: its **push URL is deliberately set to
> a sentinel** (`DISABLED-READ-ONLY`) so that a slip cannot publish to upstream.

```bash
git fetch --depth=1 upstream master-jdk25     # kept shallow on purpose; deepen with --deepen
git diff upstream/master-jdk25 HEAD --stat
```

**Two things worth knowing before reading a diff, both learned the hard way:**

1. **Fetching needs HTTP/1.1 here.** A plain `git fetch upstream` fails with `Failed to connect to
   github.com port 443` even while `git ls-remote upstream` and `curl https://github.com` succeed.
   `git -c http.version=HTTP/1.1 fetch --depth=1 upstream master-jdk25` works.
2. **`git diff` reports a file as modified when only its mode changed.** Roughly 130 files differ
   from upstream by the executable bit alone — upstream tracks them `100755`, this copy lost that
   when the tree came across Windows. To see **content** changes only, compare the blobs:

   ```bash
   git diff --name-only upstream/master-jdk25 HEAD | while read -r p; do
     [ "$(git rev-parse "HEAD:$p")" != "$(git rev-parse "upstream/master-jdk25:$p")" ] && echo "$p"
   done
   ```

## Where the line's records are

| | |
|---|---|
| The runtime's side of this host | this directory (`KEELBASE-HOST.md`) |
| The line's decisions and backlog | the project's private repository, `KeelBase-YuDao/` — not in this repository |
| The spike that started this line | same, `KeelBase-RuoYi/Yudao真跑型Spike_2026-10-08.md` + `evidence-yudao-spike-2026-10-08/` |
