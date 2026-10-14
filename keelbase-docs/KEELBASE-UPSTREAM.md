# KEELBASE-UPSTREAM — how this line stands to the tree it was derived from

> `D45`（2026-10-11）.** The decision lives in the private repository (`KeelBase-YuDao/决定索引.md`);
> this page is where someone about to change an upstream file will actually read it.

This repository's history was started fresh from one upstream commit. It is not a fork in the merge
sense — but it *is* a tree with upstream's files in it, and this line has changed some of them. This is
what it is allowed to change, and where those changes are written down.

## The boundary

The RuoYi line's rule `D15` — *upstream's generated artefacts and `vm/` are never touched* — is **not
transplanted here**（`D45`). It could not be: this line's generator lives in the repository, and this
line has already changed nine of upstream's files (the patrol below reports the live number). What
replaces it:

| | |
|---|---|
| **Allowed without ceremony** | wiring (a dependency, an explicit bean name) · the build shape (which modules are in the reactor) |
| **Allowed, as its own increment** | the console's visual layer, `keelbase-ui/host-patches/` — that is **a different upstream** (`yudao-ui-admin-vue3`) |
| **Allowed, and registered below** | changing **what upstream does** — either because a governance seam needs it, or because the tree does not otherwise work (an upstream dependency moving under it, and the like) |

A change in that third row carries **three things**, and all three are the point: a **marker in the
comment** saying the change is ours, an **entry in the register below**, and a **test**. Without the
marker, the next upstream rewrite of that file is read as a whole diff; without the register, nothing
says how many such places exist; without the test, nothing says the change still holds.

## The register

| file | what it changes | why |
|---|---|---|
| `yudao-module-ai1-springai/.../harness/session/Ai1SessionStreamTool.java` | the security context upstream sets on its own threads; **and the two seams it now consults before running a turn** (which model, and the deployment's own tools) | `D32` — the caller has to reach the tool's thread; the two seams are the deployment's answer to a question upstream otherwise answers itself |
| `yudao-module-ai1-springai/.../harness/mcp/Ai1McpToolFactory.java` | the caller captured where the tool is assembled | `D32` — the same seam, at assembly |
| `yudao-module-ai1-springai/.../harness/llm/Ai1LlmChatTool.java` | it is handed a `ChatClient` rather than a model snapshot, and no longer builds the client itself | the deployment answers which model a session runs on — see `Ai1ChatModelResolver` |
| `yudao-module-ai1-springai/.../service/agent/Ai1AgentServiceImpl.java` | agent creation validates a model by asking the same resolver the session asks | a model this deployment cannot reach is refused when the agent is written, rather than surfacing as a failed session later |
| `yudao-module-ai1-springai/.../harness/llm/Ai1ChatModelResolver.java` + `.../harness/local/Ai1LocalToolProvider.java` (**files added inside the module**) | the two seams the entries above consult — the tool one taking the **session id** as well as the agent | the model comes from the deployment's own catalogue, and an agent can reach the runtime's governed tools **attributably**: without the session, no row a governed call writes can be read per turn |
| `yudao-module-report/.../goview/GoViewProjectMapper.java` | the creator is bound as a string | PostgreSQL refuses the coercion MySQL performs |
| `yudao-framework/.../swagger/config/YudaoSwaggerWebConfiguration.java` + its registration in that starter's `AutoConfiguration.imports` | a new auto-configuration inside upstream's own package, and the one line that switches it on | springdoc 3.0.3 and Boot 4.x disagree about the `api-docs` endpoint's declared return type, and it throws without this（`dcd9c6b548`） |

The rest of the footprint is wiring, documents and the test that covers one of the entries above:
`README.md` · both `pom.xml` · `AuthController.java` · `Ai1McpToolFactoryTest.java` · and the added tree
(this line's seams, generator, console layer, deployment, scripts, SQL, docs).

⚠ The third row was **widened on 2026-10-12**, and that is why the Swagger entry above belongs there:
it is not a governance seam, it is a change the tree needs in order to work at all. Until then the row
read "when a governance seam needs it", which was narrower than this register — the gap was found by
taking the patrol's own advice and looking, not by reading the register and trusting it.

## Measuring, rather than gate-keeping

```bash
node scripts/check-upstream-footprint.mjs
```

It diffs against **the tag this repository carries** (`upstream-pin-994605b`), not the moving remote —
a comparison against the remote would add upstream's own progress to this line's number. It reports
deleted / added / modified, names the modified files with their line counts, lists added files that
landed in directories upstream already owns, and reports the console patch separately as a count of
the files it reaches.

It has **no red light on purpose**. Upstream moving, and this line moving with it, are both normal; a
patrol that fails is a patrol nobody reads. It prints when the footprint grows past the last reading,
and that is a reminder to look — then to update the reading in the script.

## The executable bit

About 130 files differ from upstream by their executable bit alone, which they lost when the tree came
across Windows. **This line does not maintain the mode**（`D45`）, and the patrol excludes mode-only
differences so they are not reported as changes. Restoring them would be its own change, with its own
reason; it is not needed for anything here to work.
