# The adversarial showcase, and what it is not / 对抗性演示，以及它不是什么

> **This file is the boundary declaration `D48 ③` requires.** It says what the showcase is, what it
> deliberately is not, and which of the reference's scenarios have nothing to land on here.
>
> **本文件是 `D48 ③` 要求的那份边界声明。** 它说明这个展示是什么、刻意不是什么，以及参照实现的哪几条
> 场景在这里**没有可落的东西**。

## What it is

A page in this host's own console, and two routes behind it
(`GET /ai/security-showcase/scenarios`, `POST /ai/security-showcase/run/{id}`), each run **against this
deployment** — this runtime, this host's tools, the identity the host resolved. Every scenario reports
the runtime's own answer and carries the engine's raw status beside it, so no vocabulary here is
invented: a refusal the engine calls `error` is shown as a refusal *and* as the engine's word for it.

The runner **drives** the engine through the one entry every proposal travels (`execute`). It
implements no gate, no confirmation policy and no scope rule of its own — those stay in
`keelbase4j-core`, which is why this can live on a host without moving a boundary onto it. It is the
same shape as the scenario gates this line already keeps (`scripts/run-*-scenarios.mjs`).

## What it deliberately is not

**It is a runtime-specific exhibit: it has no wire object, and it is not in replay scope.** Nothing
in the contract repository describes it — not the schemas (none of `v1`–`v5`), and not the reference's
own scenario corpus, which says as much of itself: five cases that "have no corresponding wire
object", excluded from neutral replay because they address one implementation's own route.

So this page adds **nothing** to cross-runtime comparability, and nothing here should be quoted as
contract conformance. What it offers is what a demonstration offers: a way to watch *this*
deployment refuse things, in front of somebody.

## Where the ids and the outcome words come from

Three of the four ids — `unauthorized`, `confirmation`, `unknown-tool` — are **the reference's own
strings, kept deliberately**, and so are the words they answer with (`denied`,
`requiresConfirmation`, `unresolved`). Same case, same name: a reader who has seen the other
demonstration can line the two up without a translation table.

**That is a convenience, not a shared vocabulary — and the difference matters.** Nothing freezes
these words. The reference's own pack defines no schema for them (its replay grammar does not mention
`outcome` at all), the contract repository has no object for any of this, and nothing anywhere asserts
that two implementations should agree. So the overlap is a fact about **two implementations that
happened to name the same danger the same way**, and it stops where the two sets differ:

- `positive-control` has **no counterpart** in the reference — a demonstration of refusal needs one
  call that must *succeed*, and the reference's pack is a list of adversarial cases.
- The reference's `injection` and `r5-block` have no landing here, for the reasons below.

**Do not read the four as the reference's five minus one.** They are not a subset, and treating the
names as a vocabulary would make the two missing cases look like an omission rather than the two
things this deployment cannot demonstrate at all.

## Four scenarios, not the reference's five

| id | drives | expects |
|---|---|---|
| `unauthorized` | a read the caller may not perform | the tool refuses it |
| `confirmation` | an R3 write, which this runtime holds for a person | held, with a token; nothing written |
| `unknown-tool` | a tool name that does not exist | the registry refuses to resolve it |
| `positive-control` | a read the caller may perform | it runs and returns data |

The last one is not decoration. Without it, "refuse everything" would pass every scenario above.

**Two of the reference's five have nothing to land on here, and are not faked:**

- **R5 blocked.** This runtime's gate decides on risk level alone (`R5 → block`); it has no policy
  toggles and no role allowlist, and this deployment declares **no R5 tool** — it does declare an R4
  one (`escalate_customer`), which waits for a second person rather than being refused, and that is a
  different gate. There is no tool that could be blocked, and no policy that could block one. Landing this scenario needs a tool at that
  level — or a policy surface the runtime does not have.
- **Prompt injection.** There is no prompt on this path. This deployment's planner is rule-based, so
  an injection has nothing to be injected into. Landing it needs a model path.

## What the `unauthorized` scenario does and does not prove

It drives `get_notify_message` with a synthetic id, and that is faithful rather than a shortcut: the
tool answers "not yours" and "does not exist" with the same sentence — it withholds both alike — so an
id outside the caller's scope exercises the same refusal path a real foreign row would.

**What it does not prove:** that a *real* row belonging to somebody else is withheld. That is the
tool's own behaviour, covered where the tool is covered, and this page does not re-establish it.
Saying so is the difference between a demonstration and a claim.
