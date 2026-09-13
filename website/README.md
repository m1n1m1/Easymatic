# Easymatic website

The marketing site and the documentation for [Easymatic](../README.md), built with
[Astro](https://astro.build) and [Starlight](https://starlight.astro.build). The output is
static — there is no server half — and Cloudflare Workers serves `dist/` straight from the
edge.

Live at **<https://easymatic.mathias-weinstabl.workers.dev>**, with the documentation at
[`/docs/`](https://easymatic.mathias-weinstabl.workers.dev/docs/).

> **Note:** This directory is invisible to the Gradle build. `settings.gradle.kts`
> includes only the four Android modules, so nothing here affects
> `.\gradlew.bat assembleDebug`. The reverse is not true any more: the site reads
> `../docs/`, which is covered under [Node reference](#node-reference) below.

## Getting it running

You need **Node 22.12 or newer**. CI uses the version in `.node-version` (24.13). Run
everything from this directory:

```
cd website
npm install
npm run dev
```

That serves the site on <http://localhost:4321>.

| Command | What it does |
| --- | --- |
| `npm install` | Installs the dependencies |
| `npm run dev` | Dev server on http://localhost:4321 |
| `npm run build` | Regenerates the design tokens, then builds to `dist/` |
| `npm run preview` | Serves the built `dist/` locally |
| `npm run check` | Typechecks with `astro check` |
| `npm run tokens` | Regenerates `src/styles/tokens.css` only |
| `npm run nodes` | Generates the node reference pages on their own |

## Deployment

`wrangler.jsonc` points Cloudflare Workers at `dist/` as static assets. Two things there
matter:

- The Worker has **no `main`**, because `output: 'static'` means there is no code to run.
  Adding SSR later is what would add `main` here and `@astrojs/cloudflare` in
  `astro.config.mjs`.
- **`name` must stay in step with the workers.dev subdomain.** It decides which Worker
  `wrangler deploy` writes to, so renaming it creates a second site instead of moving
  this one.

The `site` value in `astro.config.mjs` is the provisional workers.dev subdomain. Canonical
URLs, Open Graph URLs and the sitemap are all derived from it, and it is baked into every
one of the ~220 built pages — so change it there the day a custom domain is attached. A
redirect at the edge does not fix a canonical that is already in the HTML.

## Design system

The site is editorial rather than Material: **near-black, neutral**, very heavy type and
**no cards**. Almost nothing has a `border-radius` and nothing at all has a border.
`.band`, `.band-alt` and `.shell` in `global.css` are the whole layout system — a band is
a full-bleed section with a centred measure inside it.

Sections used to alternate cream and near-black, and that alternation *was* the separation
system. It was also five luminance swings on one scroll, the widest 18:1, which is what
made the page tiring. Sections still alternate — `.band` and `.band-alt` — but the swing is
**1.05:1**: enough to see a seam, nowhere near enough to make the eye re-adapt.
`--surface-raised` is a separate job. It is for **objects** that sit on a section, never
for a section itself, so a panel keeps its contrast wherever it lands.

> **Note:** `.band` padding is symmetric, half a rhythm each side. Two neighbours
> contribute one full gap between them and no section is lopsided. The tinted sections
> need this: with top-only padding, a `.band-alt` block would begin a whole rhythm above
> its text and then stop dead at the bottom of it. `.tight` is zero, for the one pair that
> shares a ground and reads as a single thought.

### Icons

Icons come from **Material Symbols** through `astro-icon` and
`@iconify-json/material-symbols`. That package is a devDependency: only the glyphs
actually referenced are inlined into the page, so the ~15 000-icon set never ships.

Use the **Sharp** cut. The site has no rounded corners outside the buttons, and
`material-symbols:home-sharp` is the *filled* sharp variant — `-outline-sharp` is the
hollow one. Where a glyph has an equivalent on the canvas, take the same one the node
wears: `NodeIcon` (`node-api/.../NodeIcon.kt`) maps to concrete Material icons in the
`when` in `EditorColors.kt`.

### Imagery

Everything in `public/img/` is **real editor capture**, taken on an emulator at a phone's
native 1080×2400 and exported as WebP (~40 KB each). There are two kinds, dressed
differently on purpose:

- `hero-editor.webp` is the capture inside a drawn phone — bezel, rounded screen, side
  buttons, transparent around it — because the hero's job is to show that this is an app
  running on a phone.
- `showcase/*.webp` are the whole screen with its corners rounded and nothing drawn around
  it. The hero already shows the bezel; a second one in the rail would compete with it.

Both are portrait, so both are sized by `max-block-size` and centred rather than stretched
to their column. At the hero's column width a 1:2 picture would run to over a thousand
pixels and dwarf the copy beside it.

The graphs are laid out for a phone: a trunk column with two arms one card width to either
side, so every graph is 420 dp wide and the editor's fit-to-screen lands on the same zoom
for all of them. Node names are at most **16 characters** — the card's title line holds
about that at 13 sp, and anything longer is ellipsized on the canvas.

Re-taking them is scripted, and the scripts are in the repo under `tools/screenshots/`:

1. `graphs.py` holds the six macros as workflow JSON.
2. `capture.py` seeds the app's private storage with a throwaway AI connection, two hubs
   and two places (so no card wears a Problems badge), pushes the workflows with `run-as`,
   puts the status bar in demo mode, drives the editor by `uiautomator` text lookups and
   captures each graph at native resolution. Needs a running emulator with a debug build.
3. `compose.py` draws the phone frame, rounds the showcase corners, and also writes the
   9:16 Play Store pictures under `fastlane/metadata/android/en-US/images/phoneScreenshots/`
   — a 9:20 capture is taller than the 2:1 Play accepts, so the framed phone goes onto a
   1080×1920 canvas with one line of copy above it.

> **Note:** the store pictures are generated from the same captures, so the website and
> the listing can never show two different versions of a macro.

### Type and theme

The face is **Archivo Variable**, self-hosted through `@fontsource-variable/archivo`.
Display sizes live in `--t-*` tokens and run to weight 800 with negative tracking. Weight is
what carries hierarchy here — not colour, and not containers.

The site **commits to one look**. There is no light mode, and `color-scheme: dark` says so,
so form controls, scrollbars and the viewport ground are painted to match instead of ringing
a dark page in light chrome. `theme-color` in `BaseLayout.astro` carries `--surface` and has
to be edited by hand when that changes.

## Colour

> **Note:** `src/styles/tokens.css` is **generated and committed** — do not edit it by hand.
> Run `npm run tokens` to regenerate it. `npm run build` does that first, so a stale file
> cannot ship.

That file seeds a full Material 3 tonal ramp from `#E06C4F`
(`EditorColors.triggerAccent` in the app). **Nothing in it is consumed by the site any
more** — not the 54 `--md-sys-color-*` roles, and not the four `--brand-*` accents either.
The palette below, in `global.css`, is the whole of it. `tokens.css` is left generated
because the script derives those roles by reflection, and unpicking that is a separate
change.

There are three surfaces, each with a job. Every one is a pure grey — all three channels
equal — so the only hue on the page comes from the accents:

| Surface | Value | Job |
| --- | --- | --- |
| `--surface` | `#0a0a0a` | The default section ground |
| `--surface-alt` | `#111111` | Every other section — **1.05:1** away |
| `--surface-raised` | `#1d1d1d` | Objects: panels, pictures, `<pre>`, the footer slab, the scrolled header |

| Ink | Value | On surface | On alt | On raised |
| --- | --- | --- | --- | --- |
| `--on-surface` | `#dedede` | 14.8:1 | 14.1:1 | 12.6:1 |
| `--on-surface-dim` | `#a3a3a3` | 7.9:1 | 7.5:1 | 6.7:1 |
| `--signal` | `#ff6a2b` | 6.9:1 | 6.6:1 | 5.9:1 |
| `--accent-trigger` | `#ff8054` | 8.0:1 | 7.6:1 | 6.8:1 |
| `--accent-action` | `#74a8ff` | 8.3:1 | 7.9:1 | 7.1:1 |
| `--accent-value` | `#cf94ff` | 8.8:1 | 8.4:1 | 7.5:1 |
| `--accent-transform` | `#9fadff` | 9.3:1 | 8.9:1 | 7.9:1 |

Five things in that table are decisions rather than values:

- **The neutrals are neutral.** An earlier pass tinted the ground toward the brand orange
  and the whole site read as yellow. Hue belongs to the things that mean something, not to
  the paper.
- **Near-black, so an OLED can switch the pixels off.** Not `#000000`: at true black a
  scrolling page smears, and the step up to a raised panel has nowhere to come from.
- **Text is `#dedede`, not white.** White on near-black is ~19:1, and that extreme is a
  halation source rather than a virtue. A deeper ground pushes every ratio up, so the ink
  comes down to compensate.
- **`raised` clears the bar against *both* grounds** — 1.17:1 from the base, 1.12:1 from
  alt. The second is the one that binds, because most panels sit on alt sections. 1.09:1 is
  the reference: the old ink/ink-raised step, which the showcase's open row proved is
  visible.
- **One accent per node kind.** They used to come in *pairs* — a bright half for ink, a
  deep half for cream — and that whole axis went away with the second ground. Each keeps
  the hue of the app's own value (`EditorColors.kt`: `#E06C4F`, `#5B8DEF`, `#C58AF9`,
  `#8E9CF7`) without being it, because the app's palette is tuned for its own canvas.

The worst pairing anywhere is 5.9:1. If you add one, measure it rather than assuming it.

> **Note:** the app's own theme files (`ui/theme/Color.kt`) are the untouched Android Studio
> template purple. That is not the brand. The brand is the graph editor's fixed dark
> palette.

### The use-case showcase

It switches with no JavaScript. Each row carries its own radio, `display: contents` keeps
the input, the label and the picture DOM siblings so `:checked ~` reaches both, and the same
markup is two columns above 64rem and an accordion below it.

Collapsing uses `grid-template-rows: minmax(0, 0fr)` — **not** a bare `0fr`, which is
shorthand for `minmax(auto, 0fr)` and floors the track at the inner element's padding,
leaving a visible strip on every closed row.

The showcase needs five accents where the graph has four, so its fifth row wears
`--signal`. Its unchosen rows sit at `opacity: 0.7` rather than 0.6, because flattening
`--signal` at 0.6 lands under the 3:1 bar those large bold titles qualify for — and just
under is still under.

## Documentation

The docs are **Starlight**, served from `/docs/`. They were hand-built layouts for a while,
on the argument that marketing and docs should share one design system with no seam; the
sidebar, table of contents and search were "still to come". They were still to come because
they are the expensive part, and a reference of 180-odd node pages needs all three on day
one.

One design system survives that, just held somewhere else. `global.css` is still the only
palette, and `src/styles/starlight-theme.css` maps it onto Starlight's own `--sl-*`
properties. Edit a colour there instead of in `global.css` and the two halves of the site
start to drift. Starlight's own values sit in `@layer starlight.*` and ours do not, which is
what makes the mapping win regardless of source order.

Three things about that setup look like mistakes and are not:

- **`src/content/docs/docs/` is doubled on purpose.** Starlight's `docsLoader()` reads
  `src/content/docs/` with no way to point it elsewhere, and maps each file's path within it
  straight to a URL. Nesting one level deeper is the only way to serve from `/docs/` rather
  than from `/`, which `src/pages/index.astro` already holds.
- **`Header` is replaced, not wrapped.** Wrapping stacked two bars inside the one fixed,
  `--sl-nav-height`-tall box Starlight draws, and the second overflowed onto the sidebar and
  the content. `DocsHeader` is a single bar that renders Starlight's own search inside it.
  `Footer` is deliberately **not** overridden: Starlight's own is what carries the
  previous/next pagination across the whole node reference.
- **The theme toggle is replaced with an empty component *and* the dark values are repeated
  under `[data-theme='light']`.** Hiding the toggle is not enough on its own. Starlight's
  ThemeProvider reads a `starlight-theme` entry from localStorage, so anyone carrying a
  stale `light` from another Starlight site would otherwise get a half-light page.

### Node reference

`/docs/reference/nodes/` is generated, and its source is deliberately split in two:

| Half | Where it lives | Written by |
| --- | --- | --- |
| Facts — ports, config rows, permissions, the one-line description | [`../docs/nodes.generated.json`](../docs/nodes.generated.json) | `NodeDocsExportTest`, from the Kotlin declarations |
| Prose — the multi-paragraph explanation | [`../docs/nodes/`](../docs/nodes/) | By hand, one plain-CommonMark file per node |

`scripts/generate-node-pages.mjs` composes the two into one page per node. A node with no
prose file still gets a full page from its facts, so the reference is complete while the
prose lands node by node.

The generator runs from **`astro.config.mjs` itself**: importing it runs it, and the sidebar
it returns is the same pass's second output. It used to be a preceding `npm run nodes`,
which made `astro build` a command that could only crash on a clean checkout — and that is
exactly the command Cloudflare Workers Builds runs when the build command is left to the
framework preset (`npx astro build`). Every pull-request build failed there while pushes to
main went through `npm run build` and passed. A build step that no entry point can skip
cannot be skipped by an entry point nobody configured. `npm run nodes` still exists for
running the generator on its own.

The split is **prose versus facts, not app versus web**. The app will later render the same
prose files on a node's config sheet, which is why they are restricted to a subset of
CommonMark: no tables, no images, no raw HTML, no MDX. `NodeDocsExportTest` enforces that,
so an unrenderable construct fails the Android build instead of being discovered a year
later.

Two consequences are worth knowing:

- The generated pages are **gitignored**, unlike `tokens.css`, because their producer runs
  in the same command as their consumer and so they cannot go stale.
- `nodes.generated.json` is the opposite case: committed, and byte-compared on every
  `test` run.

The remaining hand-written guides are still at [`../docs/`](../docs/). `PLUGINS.md` and
`EXTERNAL_API.md` are the two externally-facing ones, not yet brought across.
