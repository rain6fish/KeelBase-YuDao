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

**One thing the build also turns off.** Upstream renders a doc-alert banner on most pages, pointing at
its own documentation site. It is gated by a build-time flag (`VITE_APP_DOCALERT_ENABLE`), so the
build sets that flag through the environment instead of hiding the banner with CSS — the element then
never renders at all. Vite lets an environment variable that already exists win over the `.env` file,
which is what keeps this a build flag rather than an edit to an upstream file. Set
`KEELBASE_YUDAO_UI_DOCALERT=true` to get upstream's behaviour back.

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
