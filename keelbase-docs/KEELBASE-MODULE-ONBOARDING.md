# KeelBase-YuDao — onboarding a business module

> Chinese: [KEELBASE-MODULE-ONBOARDING.zh-CN.md](KEELBASE-MODULE-ONBOARDING.zh-CN.md)

Bringing a business module under KeelBase splits into two halves. One half is written **once per
host**, and every later module gets it for free: identity, the planner, the audit chain, the
confirmation engine. The other half is written **per module**. This file is the second half, as a
checklist.

It **decides nothing**. Where a decision is missing it names which one, rather than picking.

**Written 2026-10-10**, from the two modules that exist: `crm` (upstream's own code — 283 Java files
we did not write, regenerate, or edit) and `products` (generated from `specs/products.json`). Two
samples is the same limit the cross-host evidence note states: enough to see a shape repeat, not
enough to freeze a contract.

**Revised 2026-10-11**: `YudaoRowScope` replaced the scope machine's declared narrowing with a read of
this host's own roles (§4.1 and §5, which is where the two sentences it changed are).

**Revised 2026-10-12**: a **third** module was onboarded — `yudao-module-bpm`'s leave request
(`bpm_oa_leave`), the second module upstream wrote, with its gate at
`scripts/run-bpm-oa-leave-scenarios.mjs`. It is what §4.1's owner-machine sentence could then be
corrected against (the owner here is `user_id`, not `owner_user_id`) and what turned up this line's
first **runtime-side** cost: the chat request declares one structured slot, and it is named `customerId`
(§4.5).

## 1. Which half is which

| Half | Where it lives | A new module touches it? |
|---|---|---|
| Identity bridge | `YudaoCallerAuthenticator` · `YudaoIdentityResolver` · `YudaoCallerId` · `ActingCaller` | no |
| Planner | `YudaoToolPlanner` · `YudaoToolFaceConfiguration` | **no** |
| Row-range machine | `YudaoModuleScope` | no — with the caveat in §4 |
| Audit · confirmation · revoke · conformance | the platform, `keelbase4j-core` | no |
| **Governance aspect** | the module's own package | **yes** |
| **Tool face** | the module's own package | **yes** |
| **Routes** | the module's own package | **yes** |

The planner is written once because it never names a tool. `YudaoToolFaceConfiguration` takes a
`List<ModuleRoute>` from the context, so declaring a route bean is the whole of a module's
contribution to routing — nothing in `YudaoToolPlanner` changes when a module is added.

## 2. What a new module costs

Three things, and they usually land in **two files**: a `*Governance` aspect and a `*Tools`
configuration (the routes are `@Bean`s in the latter). `ListYudaoUsersTool` with `YudaoUsersRoute`
shows the same three things split across two files instead — that split is a style choice, not a rule.

### 2.1 The governance aspect — the pointcut *is* the list

`@Aspect @Component`, with its pointcut naming the module's **own service**:

```java
@Around("execution(* cn.iocoder.yudao.module.crm.service.customer.CrmCustomerService.getCustomerPage(..))")
```

**Why at the service, and not inside the tool.** A narrowing written into the AI tool governs the
tool's own call and nothing else; the module's REST path stays open. Pointing the advice at the
module's service puts both the REST path and the tool under the same advices. The generated module's
first cut made exactly that mistake, and its file records it.

**What to narrow — read, don't template.** The four advices CRM needed are not a template; they are
four things **found by reading CRM's own code**. What generalises is the question set to ask of any
module:

| Ask | CRM's answer, found in their code |
|---|---|
| What does the page return when no filter is given? | `CrmSceneTypeEnum` adds **no condition at all** when `sceneType` is null, and the request VO documents null as "全部" |
| Is a read by id scoped? | no — `getCustomer(Long)` is a bare `customerMapper.selectById(id)` |
| Does the save VO let a client name someone else as owner? | yes — the owner column is in the VO |
| Does an update / delete by id check ownership? | no |

Each module answers these differently, and some will answer "nothing to close here". That reading is
the work, and it does not compress.

**Declare a narrowing *as* a narrowing.** CRM's advice asks only the owner question, while upstream's
`crm_permission` grants READ and WRITE to participants as well as the owner. The advice is therefore
**stricter than the module, not equal to it** — its file says so, and widening it is a decision about
this host's vocabulary that has been left unmade rather than guessed. Every module owes the same
sentence.

### 2.2 The tool face — the `AiTool` beans

A `@Configuration` with `@Bean AiTool` methods: one read (R1) and one write (R3). Declare
`parameters()` on a write — a proposal that names an argument the tool does not read is refused at
the gate with `invalid_arguments`, before it reaches a human.

**Carry the caller.** Upstream's own page query takes the user id **as an argument**, so identity has
to travel rather than merely be available: `ActingCaller.as(principal.userId(), …)`, plus the host's
tenant (`TenantUtils.execute(...)`) — a host-service call is refused without one.

**Project.** Drop the identity and contact fields on the way out; the admin console's red line is
that identity fields are masked at the edge.

### 2.3 The routes

One `ModuleRoute` per tool, with `toolName()`, `triggers()` and `args(context)`. The planner matches
triggers and then falls back to the runtime's `RuleBasedPlanner` for everything the host says nothing
about — and the fallback is load-bearing, because supplying any `ToolCallPlanner` makes the runtime's
own auto-configuration back off.

### 2.4 The two places the build checks you

**Hand-written SQL is declared.** An upstream module whose schema upstream does not publish gets a
`sql/keelbase-<module>.sql` we derive. Add it to `HAND_WRITTEN` in
`keelbase-codegen/src/main/java/cn/iocoder/yudao/codegen/SpecCodegen.java`, or
`bash scripts/gen-from-spec.sh --check-boundary` reports it `UNDECLARED` and exits 1.

**Hand-written Java stays out of `generated/`.** The boundary check owns exactly two namespaces:
`sql/keelbase-*.sql` and `keelbase/generated/*.java`. A module we hand-write (CRM) keeps its
`*Governance` and `*Tools` in the **parent** package `cn.iocoder.yudao.server.keelbase`, not in
`…server.keelbase.generated`. Putting them under `generated/` turns the check red — it is enforced,
not a naming preference.

### 2.5 The gate

A new module gets its own scenario script, and `scripts/run-crm-customer-scenarios.mjs` is the shape
to copy. It asserts the four things `D28 ④` calls the first acceptance gate — reachable under an
explicit identity, out-of-scope access refused, a high-risk write held for a human, the side effect
auditable. **The last of the four is best-effort, not a guarantee**: the host writes to its own
database and the audit row to another, so a write that lands and an audit append that fails leave the
write unrecorded (`D36` ①, 2026-10-11). Claim it as "in principle recorded", never as "every write is
on the chain". **Scope every assertion by a name unique to this run, never by a count of the whole
table**, so the gate holds on a database it has already been run against and alongside another run of
itself.

## 3. The generated path is the same three things, by machine

`specs/products.json` → `bash scripts/gen-from-spec.sh specs/products.json` emits the module's DDL,
its `*Tools`, and its `*Governance`. The same three shapes, produced rather than typed.

One thing is easier on this path, and the direction is worth noting because it is the reverse of the
usual one: the page request VO is ours, so it already carries the owner column and the generated
mapper already turns it into an `eqIfPresent`. Narrowing a page is one `setOwnerUserId` before the
call — with the total narrowed too, so no page's total disagrees with its rows.

This is the half that does not repeat by hand **only for modules we generate**. For a module upstream
wrote, there is no Spec to write — see §2.

## 4. Five things that decide whether the per-module work is cheap

1. **The scope machine is an owner-machine, and the owner is whatever field the module names.**
   `YudaoModuleScope.belongsToCaller` takes the owner as a string — `owner_user_id` for CRM, `user_id`
   for BPM's leave request — so nothing in the machine changes with the column's name; what changes is
   **which field an advice reads**, and that reading does not compress. A module whose row range is a
   department, a permission table, the tenant, or **the engine's own tables** (BPM's task and
   process-instance surfaces take their ownership from `assignee` / `startUserId`, not from a column)
   will not fit it and will need a second machine. This is the line between "the same every time" and
   "different every time". A second machine now **partly** exists — `YudaoRowScope` reads *this host's
   own* role table — but it answers a different question: whether the caller ranges over every row and
   is therefore exempt from narrowing at all, and not "which rows does this caller see". The narrowing
   itself is still the ownership field.
2. **Nothing enumerates the governed modules.** RuoYi has a `GovernedModule` interface and a registry;
   YuDao's `CrmCustomerGovernance`, `BpmOALeaveGovernance` and `ProductsGovernance` are bare
   `@Component`s. There is no single place that lists what is governed — so there is nowhere to read
   that a module was forgotten.
3. **Each module owes a narrowing decision** (§2.1): owner-only, or aligned with the module's own
   permission table.
4. **The abstraction boundary is not settled.** `YD-T7` is open, and the cross-host evidence note
   nominates `ActingCaller`, `ModuleRoute` and the fallback planner as promotion candidates. Seams
   written now may later move package, because the sample is still two hosts.
5. **The runtime's request shape names one module's field.** The chat request
   (`cn.com.keelbase.runtime.web.ChatRequest`) declares `message`, `conversationId` and **`customerId`**
   — one structured slot, and it is named for CRM. A second module's rows have no slot of their own, so
   BPM's route puts the leave id into `customerId`. It works, it is recorded in the tool face's class
   comment rather than dressed up, and it is the first cost this line has found that lives **outside**
   the module. Generalising the slot is a runtime change and lands on the platform line, not here.

## 5. What this file does not claim

- Three modules is three samples — two upstream (`crm`, and BPM's leave request) and one generated
  (`products`). Everything above describes the shape they repeated, not a contract.
- **CRM and the BPM leave slice have been run end to end** (`YD-2`'s gate, `YD-14`'s gate). `products`
  has been generated and built, not put through the same gate.
- The scope machine's administrator test is still a **placeholder**: "administrator" means user 1
  until a role mapping is decided. That half is unchanged. What changed on **2026-10-11** is the other
  half — the host's role table *is* read now (`YudaoRowScope`), for the every-row exemption only.
  Until then this file said there was no such read, and that sentence was true when it was written.

## 6. Where the source of truth is

| Question | Read |
|---|---|
| What this repository is, and what it does not do | `KEELBASE-HOST.md` |
| How to get a running checkout, and the traps on the way | `KEELBASE-DEV.md` |
| The same problem on the frozen RuoYi host, in more detail | `../../KeelBase-RuoYi/keelbase-docs/KEELBASE-AI-GUIDE.md` |
| What a module's gate asserts | `scripts/run-crm-customer-scenarios.mjs` |
| Which files a Spec owns, and which are hand-written | `bash scripts/gen-from-spec.sh --check-boundary` |
