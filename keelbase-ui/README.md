# keelbase-ui — the visual layer this host renders with

These files are the KeelBase visual layer: the Element Plus token set (colours, radii, typography)
used by the product's own front end. They are here so the host's UI can be rendered with our look
**without editing a single upstream file**.

## They are a generated copy

The tokens are authored once, in `Web-Admin-Vue`:

    Web-Admin-Vue/src/styles/element-theme.scss
    Web-Admin-Vue/src/styles/themes/{_blue,_teal,_graphite}.scss

Edit them **there**, then re-vendor and verify:

    bash scripts/sync-ui-theme.sh          # write the copy
    bash scripts/sync-ui-theme.sh --check  # verify only; non-zero on drift

The copy must stay byte-identical (line endings normalised), so do not edit these files in place —
`--check` exists to catch exactly that.

## What these tokens are

Pure CSS custom properties, applied through `<html data-theme>` and `<html class="dark">`: three
switchable themes (deep indigo default / teal / graphite) crossed with light and dark. Nothing here
compiles into build-time variables, which is why the layer can be dropped in *after* the app's own
stylesheets and take effect at runtime — no upstream SCSS needs to change.

In this host only the **default (deep indigo) theme** is live: the app has no `data-theme` switcher,
so the `teal` and `graphite` blocks never match. They travel with the copy anyway, so the three
themes stay one authored thing rather than a fork.

## Two stylesheets, two jobs

| file | covers |
|---|---|
| `theme/element-theme.scss` → `keelbase-theme.css` | the **Element Plus token surface** — colours, radii, typography, card and button treatment |
| `theme/host-yudao.scss` → `keelbase-host.css` | **yudao's own layout namespaces** — `--left-menu-*`, `--top-header-*`, `--logo-*` |

The second one exists because the first cannot reach the sidebar, the top bar or the tab strip: yudao
drives all three through a namespace of its own and defaults it to a dark navy the product does not
use. It is a **translation, not a copy** — `Web-Admin-Vue` states the same look against class names
(`.admin-aside`, `.el-menu-nav`) that do not exist here, so those rules cannot be vendored. Every
*value* in it is a `var(--el-*)` reference into the vendored tokens, so the palette still has one
source; only the structure is restated. It is authored here, not synced.

## How the layer is applied

**Build-time injection** — the layer lives in the artefact, never in the source.

    bash scripts/build-host-ui.sh              # fetch if needed, install, build, inject
    bash scripts/build-host-ui.sh --skip-build # inject into an existing out dir

The checkout the app is built from is never modified. The trade is that the UI's own dev server
renders upstream's look; only the built artefact carries the layer.

The injected links carry a content hash (`keelbase-host.css?v=…`). Vite hashes its own assets; these
two files keep their names, so without a version a browser that has cached one keeps serving it after
the layer changes — which presents as "the edit did nothing".

The sidebar is also where the product's own row rhythm lands: 42px for a group, 38px for a child, and
a right padding so the rows do not run under the scrollbar, which here is an overlay rather than a
reserved strip.

**One thing the build also turns off.** Upstream renders a doc-alert banner on most pages, pointing at
its own documentation site. It is gated by a build-time flag (`VITE_APP_DOCALERT_ENABLE`), so the
build sets that flag through the environment instead of hiding the banner with CSS — the element then
never renders at all. Vite lets an environment variable that already exists win over the `.env` file,
which is what keeps this a build flag rather than an edit to an upstream file. Set
`KEELBASE_YUDAO_UI_DOCALERT=true` to get upstream's behaviour back.

## Seeing the layer locally

The layer lives in the artefact, so checking it means serving the artefact. Serving the files alone is
not enough: the built page asks for its API at the **same origin** on purpose (the build empties
`VITE_BASE_URL` so the page asks whichever host served it, and the outer nginx decides where
`/admin-api` goes). On a deployed host that is exactly the point; locally it means a plain static
server answers every API call with the SPA fallback — `index.html`, HTTP 200 — which the app reads as
a successful response whose body carries no data. Sign-in then fails with an error that names neither
the API nor the proxy, and the fault looks like it is in the client.

`scripts/serve-host-ui.mjs` fills that gap: static files at the root, and the same forwarding the
deployment's nginx does (`/admin-api`, `/app-api`, `/app`, `/ai`, `/audit`) to a backend you point it
at. An API path is never answered with the SPA fallback, and an API call that cannot reach the backend
is reported as a failure (`502`) — so the next symptom is never a silently empty response.

    node scripts/serve-host-ui.mjs                        # page on :18099, backend http://127.0.0.1:48080
    node scripts/serve-host-ui.mjs --port 18100 --backend http://127.0.0.1:48080

## Two facts that differ from the RuoYi host

1. **The admin UI is not in this repository.** Upstream keeps `yudao-ui/yudao-ui-admin-vue3` as a
   placeholder directory, so the build script fetches the real tree into `target/` — a path upstream's
   `.gitignore` already ignores, so nothing is added to any tracked file to keep it out of the index.

2. **The app writes its own theme colour onto `<html>` as an inline style at mount**
   (`ConfigGlobal` → `appStore.setCssVarTheme()`). An inline style beats a plain stylesheet on its
   own, so a last-linked stylesheet carries everything *except* the primary colour family. `!important`
   does beat a non-important inline style, so the build adds it to that family alone — the rest of the
   layer stays overridable at runtime, and the host's own theme picker keeps working for everything
   else. The side effect is that the host's primary-colour picker no longer visibly changes the page:
   this layer pins it on purpose.

## Three deltas the layer cannot express

A stylesheet can restyle what the host renders. It cannot change what the host renders from its own
source — and three of the things this product needs are exactly that: the dashboard card's contents are
a static array in a component, the logo is a source import, and the IM module is not shipped at all.
They are therefore carried as deltas and laid over the checkout before it is built.

    keelbase-ui/host-overlay/      files we add to the checkout
    keelbase-ui/host-patches/      a patch applied to the checkout
    keelbase-ui/host-removals.txt  paths we take out of the checkout

The checkout is disposable and refetched, so the build resets it to upstream first and then lays the
delta on. All three fail loudly on purpose: an overlay whose file was moved upstream, a patch that no
longer applies, and a removal whose path is no longer there, all mean our delta needs revisiting —
never that it should be skipped, which would leave the artefact quietly half-ours.

**The overlay adds, it does not replace.** That is what keeps the coupling loud: a file we add is
referenced only from a patch, so upstream moving the code that references it makes the patch fail and
say so. A replacement would have no such tripwire — upstream renaming the file would leave our copy
sitting there unused while the app went on using theirs, silently.

**Taking a route out and taking the files out are two different jobs, and both are needed.** The route
is the patch's job: it imports the removed files by literal path, so leaving it behind fails the build.
The files are the removal list's job, and the route is not what puts them in the bundle — that is
`utils/routerHelper.ts`, which pulls every view in through `import.meta.glob('../views/**/*.{vue,tsx}')`
so backend menu records can name one by path. Delete the route alone and the IM module would still be
built and deployed; it is unreachable either way, which is why the cost was only ever the bytes.

## The avatar and the brand mark

`assets/keelbase-avatar.svg` is generated by [DiceBear](https://www.dicebear.com) (`shapes` style).
Its own embedded metadata declares the artwork **CC0 1.0**; the packages it comes from declare MIT.
Either way it is free for commercial use, and the attribution is in `NOTICE`.

It is applied through the stylesheet rather than the components, which is what lets one rule cover
every avatar in the console — tables and the profile page included — instead of visiting each one.
The image the host hands out is hidden and the ground beneath it carries this instead; the `src`
itself is not reachable from here, so it cannot be swapped.

`host-overlay/src/assets/imgs/keelbase-logo.svg` is the product's brand mark, with the same path and
colour as Web-Admin-Vue's own logo component and favicon, so the mark keeps one source.

## The names the deployment shows

The system's name comes from the build's environment (`VITE_APP_TITLE`), so it lands in the page
title and everywhere the UI prints it without any source being edited.

Two other names are **not** labels and cannot be changed here. The **tenant name** is what the login
looks the tenant up by, and the seeded administrator's **nickname** is what the top bar and the
greeting show. Both live in the database, and the login pre-fill has to agree with the tenant record
or sign-in stops working. They move together, through `sql/keelbase-brand-names.sql` — run it against
an existing database after upstream's seed; a fresh install runs upstream's seed and then it.

Upstream's seed file is left untouched: it is upstream's, it is several thousand lines, and it is
duplicated across dialects, so a rename belongs in a file that says what it changes rather than as
edits scattered through theirs.

## Two traps the build had to learn

**The href has to be absolute.** A relative `href` resolves against the *current route*, not the
document root. The app uses history routing, so at the root route it works — which is the first thing
anyone checks — and the moment a user reaches a deep route the link points at a path that does not
exist and the whole
layer vanishes **silently**: no error, no 404 the user sees, just upstream's look back. The build now
reads the base prefix off an asset link Vite already emitted and writes an absolute href.

**`!important` alone is not enough against yudao's own menu rules.** Those are scoped, which makes
them e.g. `.v-menu[data-v-…] .el-menu .el-menu-item:hover` — five class-level terms. A plain
`html body .el-menu .el-menu-item:not(.is-active):hover` has four, because `:not()` contributes only
the weight of its argument, and both sides are `!important`, so yudao's wins and its white hover text
comes back on a light ground. The adapter carries a `.v-menu` ancestor in those selectors for the
weight. This is worth knowing before editing it: dropping what looks like a redundant class silently
loses the state again.

**A selector list is unforgiving.** One selector the browser cannot parse invalidates its entire rule,
silently — the other selectors in that list stop applying too and nothing reports it. Sass compiles a
nested `:has()` without complaint, and nesting `:has()` is not allowed, so a rule that paired one with
a perfectly good selector lost both. The adapter therefore keeps **one selector per rule**, so every
rule stands or falls on its own. The repetition is the point.
