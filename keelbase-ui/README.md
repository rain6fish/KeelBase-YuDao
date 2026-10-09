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

## How the layer is applied

**Build-time injection** — the layer lives in the artefact, never in the source.

    bash scripts/build-host-ui.sh              # fetch if needed, install, build, inject
    bash scripts/build-host-ui.sh --skip-build # inject into an existing out dir

The checkout the app is built from is never modified. The trade is that the UI's own dev server
renders upstream's look; only the built artefact carries the layer.

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
