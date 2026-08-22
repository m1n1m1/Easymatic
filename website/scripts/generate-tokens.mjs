/**
 * Generates the Material 3 colour tokens the site is built on.
 *
 * Build-time rather than runtime: the browser never loads the colour library,
 * and `src/styles/tokens.css` is committed so a clean checkout builds without
 * running this at all. `npm run build` regenerates first so a stale file
 * cannot ship.
 *
 * Run with `npm run tokens`, which bundles this file with esbuild before
 * running it. That is not a preference: @material/material-color-utilities
 * 0.4.0 ships ten extensionless relative imports (e.g. `./dynamic_color` in
 * dynamiccolor/color_spec_2025.js), which raw Node ESM refuses to resolve.
 * Bundling applies Node-style resolution and fixes them up.
 */
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import {
  Hct,
  MaterialDynamicColors,
  SchemeTonalSpot,
  argbFromHex,
  hexFromArgb,
} from '@material/material-color-utilities';

/**
 * The seed the whole tonal ramp is seeded from.
 *
 * `EditorColors.triggerAccent` — the app's most distinctive hue. Deliberately
 * NOT `ui/theme/Color.kt`, which is the untouched Android Studio template
 * purple and is not the brand.
 */
const SEED = '#E06C4F';

/** '2025' is the M3 Expressive spec; '2021' is the original. */
const SPEC_VERSION = '2025';

/**
 * The four node-kind accents, verbatim from `EditorColors.kt`.
 *
 * These are NOT derived from the seed, on purpose. Each one *means* something
 * in the product — a trigger is orange wherever it appears, in the app and on
 * the site — so letting the tonal algorithm re-derive them would break the
 * correspondence the reader is relying on. They are brand constants that the
 * generated ramp sits beside, not inside.
 */
const BRAND = {
  'brand-trigger': '#E06C4F',
  'brand-action': '#5B8DEF',
  'brand-value': '#C58AF9',
  'brand-transform': '#8E9CF7',
  // The editor canvas itself, for surfaces that should read as "the app".
  'brand-canvas': '#17181D',
  'brand-node': '#24252D',
  'brand-node-border': '#3A3C47',
  'brand-selection': '#FF8A65',
};

/** `surfaceContainerLowest` -> `surface-container-lowest`. */
const kebab = (name) => name.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase();

/**
 * Every `DynamicColor` MaterialDynamicColors exposes, resolved against one
 * scheme. Discovered by reflection rather than listed: the role set grows
 * between spec versions, and a hand-written list would quietly lose roles.
 */
function rolesFor(isDark) {
  const scheme = new SchemeTonalSpot(
    Hct.fromInt(argbFromHex(SEED)),
    isDark,
    0, // contrast level: 0 is standard, 0.5 medium, 1 high
    SPEC_VERSION,
  );

  return Object.getOwnPropertyNames(MaterialDynamicColors)
    .map((name) => [name, MaterialDynamicColors[name]])
    .filter(([, value]) => typeof value?.getArgb === 'function')
    .map(([name, color]) => [`--md-sys-color-${kebab(name)}`, hexFromArgb(color.getArgb(scheme))])
    .sort(([a], [b]) => a.localeCompare(b));
}

const block = (entries, indent = '  ') =>
  entries.map(([prop, value]) => `${indent}${prop}: ${value};`).join('\n');

const dark = rolesFor(true);
const light = rolesFor(false);
const brand = Object.entries(BRAND).map(([name, value]) => [`--${name}`, value]);

const css = `/*
 * GENERATED FILE — do not edit by hand.
 * Regenerate with \`npm run tokens\` (see scripts/generate-tokens.mjs).
 *
 * Seed: ${SEED} (EditorColors.triggerAccent)  ·  M3 spec: ${SPEC_VERSION}
 *
 * Dark is the default because the app itself is fixed dark; light is the
 * opt-in override.
 */

:root {
${block(dark)}

  /* Brand constants — fixed, not derived from the seed. */
${block(brand)}
}

@media (prefers-color-scheme: light) {
  :root {
${block(light, '    ')}
  }
}
`;

// Resolved from the package root rather than from `import.meta.url`: this
// script is bundled by esbuild before it runs (see the `tokens` npm script),
// and the bundle does not sit where the source does.
const outFile = join(process.cwd(), 'src', 'styles', 'tokens.css');
mkdirSync(dirname(outFile), { recursive: true });
writeFileSync(outFile, css, 'utf8');
console.log(`tokens: wrote ${dark.length} roles + ${brand.length} brand constants -> ${outFile}`);
