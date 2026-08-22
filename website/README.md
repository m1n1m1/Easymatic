# Ottomatic website

Marketing/showcase site and documentation for [Ottomatic](../README.md), built with
[Astro](https://astro.build). Static output, no adapter — the deploy target is not
chosen yet.

This directory is invisible to the Gradle build: `settings.gradle.kts` includes only
the four Android modules, so nothing here affects `.\gradlew.bat assembleDebug`.

## Commands

Run from this directory. Requires Node ≥ 22.12.

| Command | Does |
| --- | --- |
| `npm install` | Install dependencies |
| `npm run dev` | Dev server on http://localhost:4321 |
| `npm run build` | Regenerate tokens, then build to `dist/` |
| `npm run preview` | Serve the built `dist/` locally |
| `npm run check` | Typecheck (`astro check`) |
| `npm run tokens` | Regenerate `src/styles/tokens.css` only |

## Colour

`src/styles/tokens.css` is **generated and committed** — do not edit it by hand. Run
`npm run tokens` (`npm run build` does it first, so a stale file cannot ship).

`scripts/generate-tokens.mjs` seeds a full Material 3 tonal ramp — light and dark,
54 roles — from a single colour, and emits it as `--md-sys-color-*` custom
properties. The seed is `#E06C4F`, which is `EditorColors.triggerAccent` in the app.
Note that the app's own theme files (`ui/theme/Color.kt`) are the **untouched Android
Studio template purple** and are not the brand; the brand is the graph editor's fixed
dark palette, which is what users actually see.

The four node-kind accents are **not** derived from the seed. They are fixed
`--brand-*` constants copied verbatim from `EditorColors.kt`, because each one *means*
something in the product — a trigger is orange in the app and must be orange here too.
Letting the tonal algorithm re-derive them would break that correspondence.

Because M3 tints neutrals toward the seed, the generated dark surface is a warm
near-black (`#130d0b`) where the app canvas is the cooler `#17181D`. Both are
available: use `--md-sys-color-surface` for site chrome and `--brand-canvas` where a
surface should read as "the app".

## Documentation

Docs are Astro content collections (`src/content/docs/`, schema in
`src/content.config.ts`) with hand-built layouts rather than Starlight, so that
marketing and docs share one design system with no seam between them. Sidebar, table
of contents and search are still to come.

The content source of truth is currently [`../docs/`](../docs/) —
`PLUGINS.md` and `EXTERNAL_API.md` are the two externally-facing guides.
