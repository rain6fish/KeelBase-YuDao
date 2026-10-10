# Deploy: the demo2 instance

> 中文：[README.zh-CN.md](README.zh-CN.md)

This directory holds the deployment of the KeelBase-YuDao instance that answers on
`demo2.keelbase.com.cn`. It is what actually runs on the demo host, kept here so the host can be
rebuilt from the repository rather than from memory.

## What has to exist first

This stack is **not standalone**. It joins an existing network and uses that host's datastore:

- A running KeelBase stack on the same machine, whose compose project is `keelbase`, so the network
  `keelbase_default` exists and carries `keelbase-postgres-1` (a `pgvector/pgvector:pg17` container)
  and `keelbase-redis-1`.
- An outer nginx in front (`keelbase-web-1`) that terminates TLS for `demo2.keelbase.com.cn` and
  proxies the whole host to `yudao-admin`. That server block lives in the **main repository**
  (`KeelBase/nginx.https.conf`), not here; look for `server_name demo2.keelbase.com.cn`.

## Artifacts to produce

Two build outputs are copied next to these files; neither is tracked (see `.gitignore`).

**The server jar.** Built from this repository on a machine that has the toolchain — the host has no
JDK and no Maven.

```bash
# from the repository root; scripts/jdk25.sh resolves JDK 25 without touching your default
bash scripts/jdk25.sh mvn -q -DskipTests package -pl yudao-server -am
# ⇒ yudao-server/target/yudao-server.jar   (a fat jar, ~300 MB)
```

> ⚠ A **running** instance holds a lock on this jar, so `mvn package`'s repackage step fails and can
> leave the fat jar replaced by a thin one. Stop the instance first, or build in a separate worktree.

**The admin UI** is not in this repository. `scripts/build-host-ui.sh` fetches the upstream tree,
builds it, and lays the KeelBase visual layer over the built page. It also pins the page's API base
to **the same origin**: upstream's own `.env.prod` sets `VITE_BASE_URL=http://localhost:48080`, which
is right for the machine that builds the page and wrong for every machine that serves it — a
deployed page would ask its *visitor's* machine for the API, and every call would fail as a network
error while the page itself looked fine.

```bash
bash scripts/build-host-ui.sh
# ⇒ target/yudao-ui-admin-vue3/dist-prod/
```

## Deploy

On the host, in the directory that will hold the stack (on the demo host: `/opt/keelbase-yudao`):

```bash
mkdir -p /opt/keelbase-yudao/ui
# copy in: docker-compose.yml, admin-nginx.conf from here
# copy in: yudao-server.jar  → the directory
# copy in: dist-prod/*       → ui/

cp .env.example .env        # then fill both values — see .env.example for where each comes from

bash bootstrap-db.sh        # create the database and load the schema (idempotent; RESET=1 to reload)

docker compose up -d
```

The server takes a minute or two to come up, and its log ends with `项目启动成功！`. Check it with:

```bash
docker logs -f yudao-server
curl -s -o /dev/null -w '%{http_code}\n' https://demo2.keelbase.com.cn/
curl -s https://demo2.keelbase.com.cn/ | grep -o '<title>[^<]*</title>'   # ⇒ <title>KeelBase</title>
```

## The AI model loop, and where to reach it

KeelBase's own surface sits at the server **root**, not under `/admin-api` — that is where the AI
loop answers. The admin nginx forwards three prefixes there (`/app`, `/ai`, `/audit`); with
`AI_ENABLED=true` and a `MODEL_API_KEY` in `.env`, a real model drives them:

```
POST https://demo2.keelbase.com.cn/ai/chat    →  { "provider": "spring-ai", "toolCalls": [...] }
POST https://demo2.keelbase.com.cn/ai/task    →  steps ≥ 2, and the step that writes comes back held
                                                 for confirmation, with its token
GET  https://demo2.keelbase.com.cn/app/capabilities
```

The provider defaults to DeepSeek — `MODEL_BASE_URL=https://api.deepseek.com`,
`MODEL_NAME=deepseek-v4-flash` — and `MODEL_BASE_URL` can point at any OpenAI-compatible endpoint.
The two `POST`s take the host's own access token (the runtime resolves it to a host user), so there
is one identity rather than two. The runtime also registers `/auth`, `/customers` and `/follow_ups`
at the root; those are deliberately **not** forwarded — see `admin-nginx.conf`.

## Things worth knowing before changing this

- **Secrets are not here.** `docker-compose.yml` reads `PG_PASSWORD` and `AUDIT_KEY` from `.env`,
  which is git-ignored. The database password is the host's existing Postgres role's, read off the
  running container; the audit key must be generated once and then left alone, because the audit
  chain is verified by recomputing hashes from it.
- **Uploads go into the database, not onto the platform vendor's server.** Upstream's seed makes an
  *example* store master with the vendor's own domain in it, so every upload landed on a server we do
  not control. `sql/keelbase-file-storage.sql` makes the **database-backed** store master instead —
  files go into the database, so there is no host directory that has to exist and nothing to mount.
  It takes the origin as a parameter (`PUBLIC_ORIGIN` in the bootstrap), because a stored file's URL
  is built from it.
  Postgres container, and Redis is that stack's, on **database index 1** so it does not collide with
  the other stack's cache. Nothing here creates a container for either.
- **Memory is the binding constraint on the demo host**, not disk. The server runs with
  `-Xmx384m` and still takes ~600 MB resident; the host has 1.6 GB in total, so it swaps. If this
  instance is to be relied on, raise the host's memory rather than tuning the heap down further.
- **The AI model loop** is switched by `AI_ENABLED` plus `MODEL_API_KEY` in `.env`. With both set,
  KeelBase's `/ai/chat` and `/ai/task` are driven by a real model; the base URL and model name default
  to DeepSeek's (`https://api.deepseek.com`, `deepseek-v4-flash`) and can be pointed at any
  OpenAI-compatible provider. With `AI_ENABLED` unset they answer from the deterministic planner and
  `/ai/chat` reports `provider: deterministic`.
- The `--spring.datasource.dynamic.datasource.master.type=...HikariDataSource` argument is not
  cosmetic: on PostgreSQL, upstream's default pool fails roughly half of all logins.
