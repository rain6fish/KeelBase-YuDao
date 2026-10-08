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

## Where the line's records are

| | |
|---|---|
| The runtime's side of this host | this directory (`KEELBASE-HOST.md`) |
| The line's decisions and backlog | the project's private repository, `KeelBase-YuDao/` — not in this repository |
| The spike that started this line | same, `KeelBase-RuoYi/Yudao真跑型Spike_2026-10-08.md` + `evidence-yudao-spike-2026-10-08/` |
