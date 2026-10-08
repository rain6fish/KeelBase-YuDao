# KeelBase host — why this repository exists

> Chinese: [KEELBASE-HOST.zh-CN.md](KEELBASE-HOST.zh-CN.md)

This repository is **not** an upstream release. It is the **host side** of the KeelBase host
integration: the yudao platform, used as an enterprise application foundation that the KeelBase Java
runtime is embedded into. It is the second host this runtime has been embedded in, beside
`KeelBase-RuoYi`.

It exists because the project decided, on **2026-10-08**, to carry the runtime as a **platform with
adapters**: new work goes into this host, and the earlier host is kept as a frozen adapter rather than
developed further. The internal record of that decision lives in the project's private repository;
this file is the part of it that belongs with the code.

## Where it came from

| | |
|---|---|
| Upstream | `YunaiV/ruoyi-vue-pro` ("yudao") |
| Branch | `master-jdk25` |
| Commit | `994605b51080fbc1f4c7838f80ac0468f1d0e67c` (2026-10-07) |
| Revision | `2026.09-jdk25-SNAPSHOT` · Java 25 · Spring Boot 4.1.1 |
| Licence | MIT — retained unmodified in `LICENSE`; see `NOTICE` |

**The history is not upstream's.** This repository was initialised fresh: it carries no upstream
commit history and shares no merge base with upstream. The derivation is stated in `README.md` and
`NOTICE`, so it can be read without reading a log.

## What is in here

| Part | What it is | Licence |
|---|---|---|
| `yudao-*` | the upstream platform, unedited except where noted below | MIT |
| `yudao-server/src/main/java/cn/iocoder/yudao/server/keelbase/` | the KeelBase host glue — identity bridge, tool face, planner wiring | Apache-2.0 |
| `yudao-server/pom.xml` | adds the `cn.com.keelbase:keelbase4j-core` dependency | — |
| `yudao-module-system/.../admin/auth/AuthController.java` | one seam change: an explicit bean name, because both sides define a class called `AuthController` | the change is Apache-2.0 |

## Where this line stands

This repository began as a **spike that was run to its first evidence**, not as a finished adapter.
Copying it here does not make it one, and nothing below is claimed beyond what was executed.

**Executed on a live host** (2026-10-08):

- a yudao access token reaches the runtime's own endpoints, and the caller resolves to a yudao user;
- the tool face executes a real tool against this host's own user table;
- the audit chain verifies over its own rows, in this host.

**Not done**: the model-driven path has not been run with a real model (only the deterministic planner
was exercised); the generator face has not been adapted to this host's code generator; the
conformance suite and the four rates have not been run here; concurrency, long-run behaviour, and the
licence surface of upstream's module dependency tree are all unmeasured.

## What this line does not do

- **It does not claim verification.** No user, customer or trial has been through it. What is written
  here is what ran on a development host — and nothing beyond it.
- **It is not upstream's release**, and it is not affiliated with, sponsored by or endorsed by the
  upstream project or its authors. "Yudao" and `ruoyi-vue-pro` are used descriptively, to say what
  this repository is built on; they are not this project's name and not a claim of association.
- **It does not settle who owns what.** In a host that brings its own tenancy, its own data-range
  engine and its own AI module, three questions have to be answered before anything moves here:
  organisation / tenant, data range, and AI. They **were answered on 2026-10-08**, in the project's
  internal record rather than in this file: the tenant becomes a first-class contract dimension,
  landing on the contract line and not on this one; row visibility for governed tables belongs to
  KeelBase's pointcuts alone, so this host's data-permission engine is not registered for them; and
  the AI layer reuses this host as its carrier, with KeelBase governing on top. **What this repository
  does not do is implement those answers** — it is still the spike that was run to its first evidence,
  and its code predates them.

## The runtime's guarantee is unchanged

Embedding it here changes none of it: **the model only proposes**. Identity, permission,
confirmation, audit and revoke are applied downstream, unconditionally, on every path that executes
an action — not on a separate console.
