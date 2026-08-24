// @ts-check
import { defineConfig } from 'astro/config';
import icon from 'astro-icon';
import starlight from '@astrojs/starlight';
// Generated, gitignored, and written before Astro ever loads this file — `npm run nodes`
// is step one of dev, build and check alike. A missing-module error here means one of
// those was bypassed by running `astro` directly.
import nodeSidebar from './src/generated/node-sidebar.mjs';

// https://astro.build/config
export default defineConfig({
  // Only the icons actually referenced are inlined into the page, so the
  // ~15 000-icon @iconify-json/material-symbols set stays a devDependency and
  // never reaches dist/.
  integrations: [
    icon(),
    starlight({
      title: 'Ottomatic',
      // Matches BaseLayout's `${title} · Ottomatic`, so a docs tab and a marketing
      // tab are titled the same way.
      titleDelimiter: '·',
      customCss: [
        // Starlight owns the <html> element, so BaseLayout does not wrap these pages
        // and none of its imports reach them. The face and the palette have to arrive
        // here instead, and in this order: global.css defines the tokens that
        // starlight-theme.css maps onto Starlight's own.
        '@fontsource-variable/archivo',
        './src/styles/global.css',
        './src/styles/starlight-theme.css',
      ],
      components: {
        // A replacement, not a wrapper. Wrapping Header stacked two bars inside the one
        // fixed, `--sl-nav-height`-tall box Starlight draws, so the second overflowed
        // onto the sidebar and the content; DocsHeader is a single bar that renders
        // Starlight's own Search inside it.
        //
        // Footer is NOT overridden: Starlight's own is what carries the previous/next
        // pagination across a reference of 175 node pages. SiteFooter used to follow it
        // here and no longer does — its link columns repeat the sidebar and the top bar,
        // which is a lot of page to scroll past to reach the next node.
        Header: './src/components/DocsHeader.astro',
        // Starlight puts the site nav's small-screen home at the foot of the sidebar,
        // because the top bar has no room for a hamburger of its own — the one fixed
        // there is Starlight's, opening this very sidebar.
        MobileMenuFooter: './src/components/DocsMobileMenu.astro',
        // The site has one look, so there is nothing to select.
        ThemeSelect: './src/components/Empty.astro',
      },
      // No `social` entry: it feeds Starlight's SocialIcons alone, which lived in the
      // Header and the MobileMenuFooter — both replaced. GitHub is a nav link in
      // `NAV`'s company now, so the icon row would only have said it twice.
      sidebar: [
        { label: 'Overview', link: '/docs/' },
        {
          label: 'Node reference',
          // Both the pages and the category groups below come from `npm run nodes`,
          // which every command that reads them runs first. It replaced `autogenerate`,
          // which labels a group with its directory name and so headed them `ai`,
          // `apps-intents` and `ask-the-user` — the real label lives on the Kotlin
          // category and reaches the generator through the export.
          //
          // Only the four kind names are still written here, because they are the one
          // part of this tree that is the site's own wording rather than the app's.
          items: [
            { label: 'Triggers', items: nodeSidebar.TRIGGER },
            { label: 'Actions', items: nodeSidebar.ACTION },
            { label: 'Values', items: nodeSidebar.VALUE },
            { label: 'Transforms', items: nodeSidebar.TRANSFORM },
          ],
        },
      ],
    }),
  ],

  // Static build, no adapter: the deploy target is not chosen yet.
  output: 'static',

  // PLACEHOLDER. Canonical URLs, Open Graph URLs and any future sitemap are all
  // derived from this, so it has to become the real domain before launch. Now that
  // Starlight is here it is also on every one of the ~180 docs pages.
  site: 'https://example.invalid',

  // NOTE: do not set `outDir: 'build'`. The repo root .gitignore has an
  // unanchored `build/` rule (there for the five Gradle modules) that would
  // silently swallow the site output. Astro's default `dist/` is safe.
});

// A note on the doubled `src/content/docs/docs/` on disk, since it looks like a
// mistake and is not: Starlight's `docsLoader()` reads `src/content/docs/` and offers
// no way to point it elsewhere, and it maps each file's path within that directory
// straight to a URL. Nesting one level deeper is therefore the only way to serve the
// documentation from `/docs/` rather than from the site root, which the home page
// already occupies via `src/pages/index.astro`.
