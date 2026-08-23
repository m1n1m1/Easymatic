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

## Design system

The site is editorial rather than Material: **near-black, neutral**, very heavy type,
and **no cards**. Almost nothing has a `border-radius` and nothing at all has a border.
`.band`, `.band-alt` and `.shell` in `global.css` are the whole layout system — a band
is a full-bleed section with a centred measure inside it.

Sections used to alternate cream and near-black, and that alternation *was* the
separation system. It was also five luminance swings on one scroll, the widest 18:1,
which is what made the page tiring. Sections still alternate — `.band` and `.band-alt`
— but the swing is **1.05:1**, enough to see a seam and nowhere near enough to make the
eye re-adapt. `--surface-raised` is a separate job: **objects** that sit on a section,
never a section itself, so a panel keeps its contrast wherever it lands.

One consequence worth knowing before you touch layout: **`.band` padding is symmetric,
half a rhythm each side.** Two neighbours contribute one full gap between them and no
section is lopsided — which is what the tinted sections need, since with top-only
padding a `.band-alt` block would begin a whole rhythm above its text and stop dead at
the bottom of it. `.tight` is zero, for the one pair that shares a ground and reads as
one thought.

Icons come from **Material Symbols** via `astro-icon` and `@iconify-json/material-symbols`
(a devDependency — only the glyphs actually referenced are inlined into the page, so the
set never ships). Use the **Sharp** cut: the site has no rounded corners outside the
buttons, and `material-symbols:home-sharp` is the *filled* sharp variant — `-outline-sharp`
is the hollow one. Where a glyph has an equivalent on the canvas, take the same one the
node wears: `NodeIcon` (`node-api/.../NodeIcon.kt`) maps to concrete Material icons in the
`when` in `EditorColors.kt`.

Imagery in `public/img/` is **real editor capture**, taken on an emulator in portrait
at 1600x2560 and exported as WebP (~40 KB each). Two kinds, and they are cropped
differently on purpose:

- `hero-editor.webp` is the whole phone — app bar, zoom column, Problems bar and all,
  because the hero's job is to show that this is an app.
- `showcase/*.webp` are **the graph alone**. Status bar, app bar, zoom column and the
  floating buttons are all cropped away, so a use-case pane is nodes and wires and
  nothing else.

Both are portrait, so both are sized by `max-block-size` and centred rather than
stretched to their column: at the hero's column width a 5:8 picture would run to nearly
a thousand pixels and dwarf the copy beside it.

The graphs are laid out for a tall frame — the flow runs top to bottom and a branch
throws its arms left and right — which is what makes the crop portrait. A branch puts
two cards side by side and so fixes the width; only the number of rows can change the
shape, which is why each use-case graph is six rows deep.

Re-taking them is scripted rather than manual: the graphs are authored as workflow JSON
and pushed into the app with `run-as`, the emulator is driven by row taps, and the crop
finds the graph by block-mean brightness (a node card and the canvas dot grid are six
levels apart, so no per-pixel threshold separates them). The scripts are not in the repo
— they seed a throwaway hub/AI/place library so no node renders with a Problems badge,
which is emulator state rather than site content.

The face is **Archivo Variable**, self-hosted via `@fontsource-variable/archivo`.
Display sizes live in `--t-*` tokens and run to weight 800 with negative tracking;
weight is what carries hierarchy here, not colour or containers.

The site **commits to one look** — there is no light mode, and `color-scheme: dark`
says so, so form controls, scrollbars and the viewport ground are painted to match
instead of ringing a dark page in light chrome. `theme-color` in `BaseLayout.astro`
carries `--surface` and has to be edited by hand when that changes.

## Colour

`src/styles/tokens.css` is **generated and committed** — do not edit it by hand. Run
`npm run tokens` (`npm run build` does it first, so a stale file cannot ship). It seeds
a full Material 3 tonal ramp from `#E06C4F` (`EditorColors.triggerAccent` in the app).

**Nothing in that file is consumed by the site any more** — not the 54
`--md-sys-color-*` roles and not the four `--brand-*` accents either. The palette below,
in `global.css`, is the whole of it. `tokens.css` is left generated because the script
derives those roles by reflection and unpicking it is a separate change.

Three surfaces, each with a job. Every one is a pure grey — all three channels equal —
so the only hue on the page comes from the accents:

| Surface | Value | Job |
| --- | --- | --- |
| `--surface` | `#0a0a0a` | the default section ground |
| `--surface-alt` | `#111111` | every other section — **1.05:1** away |
| `--surface-raised` | `#1d1d1d` | objects: panels, pictures, `<pre>`, the footer slab, the scrolled header |

| Ink | Value | On surface | On alt | On raised |
| --- | --- | --- | --- | --- |
| `--on-surface` | `#dedede` | 14.8:1 | 14.1:1 | 12.6:1 |
| `--on-surface-dim` | `#a3a3a3` | 7.9:1 | 7.5:1 | 6.7:1 |
| `--signal` | `#ff6a2b` | 6.9:1 | 6.6:1 | 5.9:1 |
| `--accent-trigger` | `#ff8054` | 8.0:1 | 7.6:1 | 6.8:1 |
| `--accent-action` | `#74a8ff` | 8.3:1 | 7.9:1 | 7.1:1 |
| `--accent-value` | `#cf94ff` | 8.8:1 | 8.4:1 | 7.5:1 |
| `--accent-transform` | `#9fadff` | 9.3:1 | 8.9:1 | 7.9:1 |

Five things here are decisions rather than values:

- **The neutrals are neutral.** An earlier pass tinted the ground toward the brand
  orange and the whole site read as yellow. Hue belongs to the things that mean
  something, not to the paper.
- **Near-black, so an OLED can switch the pixels off.** Not `#000000`: at true black a
  scrolling page smears, and the step up to a raised panel has nowhere to come from.
- **Text is `#dedede`, not white.** White on near-black is ~19:1, and that extreme is a
  halation source rather than a virtue. A deeper ground pushes every ratio up, so the
  ink comes down to compensate.
- **`raised` clears the bar against *both* grounds** — 1.17:1 from the base, 1.12:1 from
  alt. The second is the one that binds, because most of the panels are on alt sections.
  1.09:1 is the reference: the old ink/ink-raised step, which the showcase's open row
  proved is visible.
- **One accent per node kind.** They used to come in *pairs* — a bright half for ink and
  a deep half for cream — and that whole axis went away with the second ground. Each
  keeps the hue of the app's own value (`EditorColors.kt`: `#E06C4F`, `#5B8DEF`,
  `#C58AF9`, `#8E9CF7`) without being it, because the app's palette is tuned for its own
  canvas.

The worst pairing anywhere is 5.9:1. If you add one, measure it rather than assuming.

Note that the app's own theme files (`ui/theme/Color.kt`) are the **untouched Android
Studio template purple** and are not the brand; the brand is the graph editor's fixed
dark palette.

The **use-case showcase** switches with no JavaScript: each row carries its own radio,
`display: contents` keeps the input, the label and the picture DOM siblings so
`:checked ~` reaches both, and the same markup is two columns above 64rem and an
accordion below it. Collapsing uses `grid-template-rows: minmax(0, 0fr)` — **not** a bare
`0fr`, which is shorthand for `minmax(auto, 0fr)` and floors the track at the inner
element's padding, leaving a visible strip on every closed row.

It needs five accents where the graph has four, so its fifth row wears `--signal`. Its
unchosen rows sit at `opacity: 0.7` rather than 0.6, because flattening `--signal` at
0.6 lands under the 3:1 bar those large bold titles qualify for, and just under is
still under.

## Documentation

Docs are Astro content collections (`src/content/docs/`, schema in
`src/content.config.ts`) with hand-built layouts rather than Starlight, so that
marketing and docs share one design system with no seam between them. Sidebar, table
of contents and search are still to come.

The content source of truth is currently [`../docs/`](../docs/) —
`PLUGINS.md` and `EXTERNAL_API.md` are the two externally-facing guides.
