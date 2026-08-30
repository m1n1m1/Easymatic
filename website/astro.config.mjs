// @ts-check
import { defineConfig } from 'astro/config';
import icon from 'astro-icon';
import starlight from '@astrojs/starlight';
// The node reference is generated *here*: importing the generator runs it, and the sidebar
// it returns is the same pass's second output. It used to be a preceding `npm run nodes`,
// which made `astro build` a command that could only crash on a clean checkout — and that
// is exactly the command Cloudflare Workers Builds runs when its build command is left to
// the framework preset (`npx astro build`), so every pull-request build failed while
// pushes to main went through `npm run build` and passed. A build step no entry point can
// skip cannot be skipped by an entry point nobody configured.
//
// Astro loads this config before it reads any content collection, so the ~180 pages the
// generator writes into src/content/docs/ are on disk in time. The sidebar deliberately
// does NOT travel through a generated file: Vite resolves this config's imports up front,
// so a module written during the config's own evaluation is still missing when it looks.
import nodeSidebar from './scripts/generate-node-pages.mjs';

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
        // The hand-written guides. Each group's label is written here for the same
        // reason the generated ones are: `autogenerate` heads a group with its
        // *directory name*, so these would read `start`, `concepts` and `extend`.
        // Ordering within a group comes from each page's `sidebar.order` frontmatter,
        // which keeps a page's position next to the page rather than in this file.
        // `collapsed` throughout, for the reason set out on the Node reference group
        // below. Starlight opens whichever group holds the current page regardless, so a
        // reader inside Getting started still sees its siblings.
        { label: 'Getting started', collapsed: true, items: [{ autogenerate: { directory: 'docs/start' } }] },
        { label: 'Core concepts', collapsed: true, items: [{ autogenerate: { directory: 'docs/concepts' } }] },
        { label: 'Integrations', collapsed: true, items: [{ autogenerate: { directory: 'docs/integrations' } }] },
        { label: 'Permissions & the phone', collapsed: true, items: [{ autogenerate: { directory: 'docs/system' } }] },
        { label: 'Extending Ottomatic', collapsed: true, items: [{ autogenerate: { directory: 'docs/extend' } }] },
        {
          label: 'Node reference',
          // The reference is 175 nodes over four kinds and ~30 categories. Fully expanded
          // it is several screens of links, which buries the four kind headings — the one
          // division a reader actually navigates by — off the top of the sidebar. So every
          // level here is closed until asked for, and `writeSidebar` sets the same flag on
          // the category groups it generates.
          collapsed: true,
          // Both the pages and the category groups below come from the generator run at
          // the top of this file. It replaced `autogenerate`,
          // which labels a group with its directory name and so headed them `ai`,
          // `apps-intents` and `ask-the-user` — the real label lives on the Kotlin
          // category and reaches the generator through the export.
          //
          // Only the four kind names are still written here, because they are the one
          // part of this tree that is the site's own wording rather than the app's.
          items: [
            { label: 'Triggers', collapsed: true, items: nodeSidebar.TRIGGER },
            { label: 'Actions', collapsed: true, items: nodeSidebar.ACTION },
            { label: 'Values', collapsed: true, items: nodeSidebar.VALUE },
            { label: 'Transforms', collapsed: true, items: nodeSidebar.TRANSFORM },
          ],
        },
        { label: 'Help', collapsed: true, items: [{ autogenerate: { directory: 'docs/help' } }] },
      ],
    }),
  ],

  // Static build, no adapter: Cloudflare serves dist/ as static assets, so nothing
  // here runs on a server and @astrojs/cloudflare is not needed. Adding an adapter
  // is what a future SSR page would cost.
  output: 'static',

  // The workers.dev subdomain, which is provisional: canonical URLs, Open Graph URLs
  // and the sitemap Starlight generates are all derived from this, and it reaches
  // every one of the ~220 built pages. Change it here the day a custom domain is
  // attached — a redirect at the edge does not fix a canonical baked into the HTML.
  site: 'https://ottomatic.mathias-weinstabl.workers.dev',

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
