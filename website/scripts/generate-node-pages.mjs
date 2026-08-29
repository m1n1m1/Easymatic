/**
 * Generates the node reference: one page per node, from the two halves of the
 * documentation source.
 *
 *   ../docs/nodes.generated.json   the facts, exported from the Kotlin declarations
 *                                  by NodeDocsExportTest. Committed.
 *   ../docs/nodes/<typeId>.md      the prose, written by hand. Optional.
 *
 * Neither half is authored twice, and neither is authored here. A node with no prose
 * file still gets a complete page from its facts, which is what lets the reference be
 * complete from the first commit and the prose land node by node.
 *
 * ## Why the output is not committed
 *
 * `tokens.css` is generated *and committed*, and this deliberately is not. The reason
 * that file is committed is that its producer and its consumer run in different
 * commands, so it can go stale silently. These pages cannot: their producer runs as
 * step one of every command that consumes them (`npm run dev`, `build`, `check`), so
 * committing 175 derived files would buy a guarantee that already holds and cost a
 * 7 000-line diff on every template tweak.
 *
 * The JSON is the opposite case and *is* committed, for exactly that reason —
 * `NodeDocsExportTest` guards it byte for byte.
 *
 * ## Layout
 *
 * Pages nest by kind and category, so a node that changes category changes URL. That is
 * accepted, and it is the same trade the palette already makes.
 *
 * The sidebar is emitted alongside them, into `src/generated/node-sidebar.mjs`, and that
 * is not a preference: Starlight's `autogenerate` labels a group with its *directory
 * name*, so the four kinds' groups read `apps-intents` and `ask-the-user` — a slug where
 * a heading belongs. There is no frontmatter for a group, because a directory has no
 * frontmatter. The category's real label only exists here, so the sidebar has to be
 * built here too.
 *
 * It reproduces what `autogenerate` did in every other respect — groups ordered by slug,
 * pages within a group by filename — so the labels are the only thing that changed. Both
 * are one `sort` away from palette order if the app's ordering is wanted instead.
 */
import { mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

const EXPORT = join(process.cwd(), '..', 'docs', 'nodes.generated.json');
const PROSE_DIR = join(process.cwd(), '..', 'docs', 'nodes');
const OUT_DIR = join(process.cwd(), 'src', 'content', 'docs', 'docs', 'reference', 'nodes');
const SIDEBAR = join(process.cwd(), 'src', 'generated', 'node-sidebar.mjs');

/** Where a page's slug starts, relative to `src/content/docs/`. */
const SLUG_ROOT = 'docs/reference/nodes';

/** `Apps & Intents` -> `apps-intents`. Category labels become directory names. */
const slugify = (text) =>
  text
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '');

/** A YAML scalar that survives colons, quotes and a leading `%`. */
const yaml = (value) => JSON.stringify(String(value));

/**
 * Node kinds, in palette order, with the sentence each one's page opens its facts with.
 *
 * The wording is the product's own vocabulary rather than the code's: a reader meeting
 * "pull side" for the first time on a reference page has been failed by it.
 */
const KINDS = {
  TRIGGER: { label: 'Trigger', order: 1 },
  ACTION: { label: 'Action', order: 2 },
  VALUE: { label: 'Value', order: 3 },
  TRANSFORM: { label: 'Transform', order: 4 },
};

function readProse() {
  let names = [];
  try {
    names = readdirSync(PROSE_DIR).filter((name) => name.endsWith('.md'));
  } catch {
    // No prose yet is a legitimate state, not an error.
    return new Map();
  }
  return new Map(
    names.map((name) => [
      name.replace(/\.md$/, ''),
      readFileSync(join(PROSE_DIR, name), 'utf8').replace(/\r\n/g, '\n').trim(),
    ]),
  );
}

/**
 * The port table.
 *
 * Headed "Declared ports" for every node, not only the dynamic ones. The export can
 * only see what a node *declares*, and for around twenty of them the ports the user
 * actually gets are resolved from the graph — so a table headed "Ports" would be a
 * claim this file is in no position to make.
 */
function portsSection(node) {
  if (node.ports.length === 0) return '';
  const rows = node.ports.map((port) => {
    const flow = port.kind === 'EXECUTION' ? 'execution' : 'data';
    const direction = port.direction === 'IN' ? 'in' : 'out';
    return `| \`${port.name}\` | ${port.label} | ${flow} ${direction} | ${port.type ?? '—'} |`;
  });
  return [
    '## Declared ports',
    '',
    ...(node.hasDynamicPorts
      ? [
          ':::note',
          "This node's ports change with its config and its wiring, so what you see on the",
          'card may differ from the table below.',
          ':::',
          '',
        ]
      : []),
    '| Port | Label | Flow | Type |',
    '| --- | --- | --- | --- |',
    ...rows,
    '',
  ].join('\n');
}

function configSection(node) {
  if (node.configFields.length === 0) return '';
  const rows = node.configFields.map((field) => {
    // First, because it is the field's own explanation rather than a fact about it — and
    // because it used to be part of `label`, so leaving it out would lose what the table said.
    const notes = [];
    if (field.hint) notes.push(field.hint);
    if (field.chooser) notes.push(`chosen from ${field.chooser}`);
    if (field.options.length > 0) {
      notes.push(field.options.map((option) => `\`${option.value}\``).join(', '));
    }
    if (field.visibleWhen) {
      notes.push(`shown when \`${field.visibleWhen.key}\` is ${field.visibleWhen.values.join(' or ')}`);
    }
    const shown = field.defaultValue === '' ? '—' : `\`${field.defaultValue}\``;
    return `| ${field.label} | \`${field.key}\` | ${shown} | ${notes.join('; ') || '—'} |`;
  });
  return ['## Configuration', '', '| Field | Key | Default | Notes |', '| --- | --- | --- | --- |', ...rows, ''].join('\n');
}

function requirementsSection(node) {
  const lines = [];
  for (const permission of node.permissions) lines.push(`- ${permission.label}`);
  for (const capability of node.capabilities) {
    lines.push(`- hardware: ${capability.toLowerCase().replace(/_/g, ' ')}`);
  }
  if (lines.length === 0) return '';
  return [
    '## Needs',
    '',
    ...lines,
    '',
    'A missing permission is flagged in the editor rather than blocking the macro — the',
    'graph is fine, the phone is not, and granting it starts the node working with no',
    'edit here at all.',
    '',
  ].join('\n');
}

function page(node, prose) {
  const kind = KINDS[node.kind];
  const frontmatter = [
    '---',
    `title: ${yaml(node.displayName)}`,
    `description: ${yaml(node.description)}`,
    `nodeTypeId: ${yaml(node.typeId)}`,
    'sidebar:',
    `  label: ${yaml(node.displayName)}`,
    '---',
    '',
  ].join('\n');

  const noun = kind.label.toLowerCase();
  const header = [
    `${node.description}.`,
    '',
    `${/^[aeiou]/.test(noun) ? 'An' : 'A'} **${noun}** in the **${node.categoryLabel}** group.`,
    `Its type is \`${node.typeId}\`.`,
    '',
    '',
  ].join('\n');

  // Prose last, under its own heading. The generated tables are the reference a reader
  // comes back for, and the prose reads as commentary on them rather than as an
  // introduction to them.
  const body = [
    portsSection(node),
    configSection(node),
    requirementsSection(node),
    prose ? `## Notes\n\n${prose}\n` : '',
  ]
    .filter(Boolean)
    .join('\n');

  return `${frontmatter}${header}${body}`;
}

/**
 * The sidebar for the four kinds, as a module `astro.config.mjs` imports.
 *
 * Entries are `{ slug }` rather than `{ label, link }` on purpose: Starlight resolves a
 * slug against the content collection and takes the label from the page's own
 * `sidebar.label` frontmatter, which `page()` already writes from `displayName`. Writing
 * a label here as well would be the same string authored twice, free to drift.
 *
 * The group label is the one thing that cannot come from frontmatter — it belongs to a
 * directory, and a directory has none — which is the whole reason this file exists.
 *
 * Every group is `collapsed`, because the reference is 175 nodes across four kinds and
 * roughly thirty categories: expanded, the sidebar is several screens of links before the
 * page even starts, and the four kind headings — the one division a reader navigates by —
 * are pushed off the top. Starlight still opens whichever group holds the current page,
 * so this closes the branches nobody is in rather than hiding where you are.
 */
function writeSidebar(groups) {
  const byKind = {};
  for (const [kind, byCategory] of groups) {
    byKind[kind] = [...byCategory]
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([categorySlug, group]) => ({
        label: group.label,
        collapsed: true,
        items: [...group.names]
          .sort((a, b) => a.localeCompare(b))
          .map((name) => ({ slug: `${SLUG_ROOT}/${kind.toLowerCase()}/${categorySlug}/${name}` })),
      }));
  }

  mkdirSync(dirname(SIDEBAR), { recursive: true });
  writeFileSync(
    SIDEBAR,
    [
      '// GENERATED by scripts/generate-node-pages.mjs. Do not edit; run `npm run nodes`.',
      `export default ${JSON.stringify(byKind, null, 2)};`,
      '',
    ].join('\n'),
    'utf8',
  );
}

const data = JSON.parse(readFileSync(EXPORT, 'utf8'));
const prose = readProse();

// Cleared each run so a node that was renamed or deleted does not leave a page behind.
// Safe because nothing in here is hand-written — the directory is gitignored.
rmSync(OUT_DIR, { recursive: true, force: true });

const kindIndex = new Map();
// kind -> category slug -> { label, slugs }. A Map keyed by slug rather than by label so
// two categories that slugify alike would collide loudly here rather than silently share
// a directory on disk.
const groups = new Map();

for (const node of data.nodes) {
  const kind = KINDS[node.kind];
  if (!kind) throw new Error(`Unknown node kind ${node.kind} on ${node.typeId}`);

  const categorySlug = slugify(node.categoryLabel);
  const dir = join(OUT_DIR, node.kind.toLowerCase(), categorySlug);
  mkdirSync(dir, { recursive: true });
  // The typeId prefix is dropped from the filename: the kind is already the directory,
  // so `action/apps-intents/action.open_url.md` would say it twice in the URL.
  const name = node.typeId.split('.').slice(1).join('.');
  writeFileSync(join(dir, `${name}.md`), page(node, prose.get(node.typeId)), 'utf8');

  if (!groups.has(node.kind)) groups.set(node.kind, new Map());
  const byCategory = groups.get(node.kind);
  if (!byCategory.has(categorySlug)) {
    byCategory.set(categorySlug, { label: node.categoryLabel, names: [] });
  }
  const group = byCategory.get(categorySlug);
  if (group.label !== node.categoryLabel) {
    throw new Error(
      `Categories ${yaml(group.label)} and ${yaml(node.categoryLabel)} both slugify to ` +
        `"${categorySlug}", so their pages would share a directory.`,
    );
  }
  group.names.push(name);

  kindIndex.set(node.kind, (kindIndex.get(node.kind) ?? 0) + 1);
}

writeSidebar(groups);

const documented = data.nodes.filter((node) => prose.has(node.typeId)).length;
const counts = [...kindIndex].map(([kind, count]) => `${count} ${kind.toLowerCase()}`).join(', ');
console.log(
  `nodes: wrote ${data.nodes.length} pages (${counts}) -> ${OUT_DIR}\n` +
    `nodes: ${documented} of ${data.nodes.length} have prose; the rest are facts only`,
);
