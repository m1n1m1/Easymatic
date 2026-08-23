// @ts-check
import { defineConfig } from 'astro/config';
import icon from 'astro-icon';

// https://astro.build/config
export default defineConfig({
  // Only the icons actually referenced are inlined into the page, so the
  // ~15 000-icon @iconify-json/material-symbols set stays a devDependency and
  // never reaches dist/.
  integrations: [icon()],

  // Static build, no adapter: the deploy target is not chosen yet.
  output: 'static',

  // PLACEHOLDER. Canonical URLs, Open Graph URLs and any future sitemap are all
  // derived from this, so it has to become the real domain before launch.
  site: 'https://example.invalid',

  // NOTE: do not set `outDir: 'build'`. The repo root .gitignore has an
  // unanchored `build/` rule (there for the five Gradle modules) that would
  // silently swallow the site output. Astro's default `dist/` is safe.
});
