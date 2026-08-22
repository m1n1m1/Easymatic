import { defineCollection } from 'astro:content';
import { glob } from 'astro/loaders';
// `astro/zod` rather than the `z` re-exported from `astro:content`, which is
// deprecated in Astro 7.
import { z } from 'astro/zod';

/**
 * The documentation collection.
 *
 * Source of truth for the *content* is still `docs/` at the repo root
 * (ADDING_NODES.md, PLUGINS.md, EXTERNAL_API.md). Whether those get copied in
 * here or read from there directly is a decision for when the first real page
 * lands — the shape below works either way.
 */
const docs = defineCollection({
  loader: glob({ pattern: '**/*.md', base: './src/content/docs' }),
  schema: z.object({
    title: z.string(),
    description: z.string().optional(),
    /** Sidebar position within a section. Lower sorts first. */
    order: z.number().default(100),
    /** Excluded from the build. */
    draft: z.boolean().default(false),
  }),
});

export const collections = { docs };
