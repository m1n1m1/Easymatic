/**
 * Every word on the home page, and the numbers behind it.
 *
 * Copy lives here rather than in the markup so that a wording change is a
 * one-file edit and `index.astro` stays readable as layout.
 */

/** Shown on links whose target does not exist yet. Grep `placeholder` to find them. */
export const PLACEHOLDER_TITLE = 'Not available yet — this page is still to come.';

export interface NavLink {
  label: string;
  href: string;
  /** True when the target does not exist yet. */
  placeholder?: boolean;
}

export const GITHUB_URL = 'https://github.com/m1n1m1/Easymatic';

export const NAV: NavLink[] = [
  { label: 'Use cases', href: '#use-cases' },
  { label: 'Features', href: '#features' },
  { label: 'Docs', href: '/docs/' },
  { label: 'Plugins', href: '/plugins', placeholder: true },
];

export const HERO = {
  eyebrow: 'Android automation',
  title: 'Your mobile always-on assistant. Built for humans, enhanced with AI',
  lead:
    'Easymatic is a visual, node-based automation app for Android. Your automations run 24/7 in the background, wherever you go. The power of AI where you want, manual control where you need it.',
  facts: ['Android 8.0+', 'Open source, MIT', '8 languages'],
} as const;

export interface ShowcaseItem {
  /** Used for the radio input's id. Must be unique and URL-safe. */
  id: string;
  /** Colour for the title and the active marker. */
  accent: string;
  /**
   * Iconify name, from `@iconify-json/material-symbols`.
   *
   * The Sharp cut, because the site has no rounded corners anywhere else. Four
   * of the five are the glyph their nodes already wear on the canvas — see
   * `NodeIcon` and the `when` in `EditorColors.kt`.
   */
  icon: string;
  title: string;
  body: string;
  /** The macro's graph, cropped out of an editor capture. 1100x1236. */
  image: string;
  imageAlt: string;
}

/**
 * The category showcase: five corners of the phone, each with a picture.
 *
 * Five rather than nine, and deliberately not the same cut as `FEATURES` —
 * that list is the complete inventory, this one is the argument. The images
 * are placeholders; each is a stand-alone file so replacing one is a file
 * swap rather than an edit here.
 *
 * The section carries a label and nothing else: the rail titles say what the
 * categories are, so a headline above them would only say it again.
 */
export const SHOWCASE_LABEL = 'Use cases';

export const SHOWCASE: ShowcaseItem[] = [
  {
    id: 'ai-agent',
    icon: 'material-symbols:psychology-sharp',
    accent: 'var(--accent-value)',
    title: 'Autonomous AI Agent',
    body:
      'Connect triggers to an AI agent and have your requests automatically be executed in the background. The AI has access to all features of Easymatic.',
    image: '/img/showcase/ai-agent.webp',
    imageAlt: 
      'An Easymatic macro: an incoming message runs an AI agent, which branches into sending the answer or just logging it.',
  },
  {
    id: 'messaging',
    icon: 'material-symbols:mail-sharp',
    accent: 'var(--accent-action)',
    title: 'Mail, Messaging, Calendar',
    body:
      'React to incoming messages. Send automated replies and create appointments. Manage your email accounts.',
    image: '/img/showcase/messaging.webp',
    imageAlt: 
      'An Easymatic macro: an incoming message branches on who sent it, then adds a calendar entry and acknowledges it, or posts a notification.',
  },
  {
    id: 'smart-home',
    icon: 'material-symbols:home-sharp',
    accent: 'var(--signal)',
    title: 'Smart Home',
    body:
      'Integrate with various Smart Home protocols and systems. Easymatic can react to incoming events and also control smart devices.',
    image: '/img/showcase/smart-home.webp',
    imageAlt: 
      'An Easymatic macro: a Home Assistant door sensor branches on the light level, then turns on a hall light or publishes to MQTT.',
  },
  {
    id: 'location-time',
    icon: 'material-symbols:location-on-sharp',
    accent: 'var(--accent-transform)',
    title: 'Location and Time',
    body:
      'Automatically execute actions based on your location or the current time.',
    image: '/img/showcase/location-time.webp',
    imageAlt: 
      'An Easymatic macro: leaving a geofenced office branches on the weekday, then notifies and turns Wi-Fi on, or waits.',
  },
  {
    id: 'sensors',
    icon: 'material-symbols:sensors-sharp',
    accent: 'var(--accent-trigger)',
    title: 'Sensors',
    body:
      'Unleash the possibilities of your phones sensors. Control your phone with gestures. Monitor your phones status.',
    image: '/img/showcase/sensors.webp',
    imageAlt: 
      'An Easymatic macro: shaking the phone branches on ambient light to turn the torch on or buzz, and a proximity trigger shortens the screen timeout.',
  },
];

export interface FeatureItem {
  /** Iconify name, Sharp cut. See `ShowcaseItem.icon`. */
  icon: string;
  /** Colour for the icon and the heading. */
  accent: string;
  title: string;
  body: string;
}

export const FEATURES_LABEL = 'Features';

/**
 * The three claims under the hero.
 *
 * Full shell width beneath the two-column hero rather than inside its copy
 * column: three sentences-wide statements do not fit in half a page, and the
 * strip is what closes the hero rather than an aside inside it.
 */
export const HERO_POINTS: FeatureItem[] = [
  {
    icon: 'material-symbols:bolt-sharp',
    accent: 'var(--accent-trigger)',
    title: 'Simple Yet Powerful',
    body:
      'Simple editor with an intuitive node-based workflow. JavaScript code when you need it.',
  },
  {
    icon: 'material-symbols:lock-sharp',
    accent: 'var(--accent-action)',
    title: 'Local First',
    body:
      'Everything runs on your phone. No cloud, no accounts, no subscriptions.',
  },
  {
    icon: 'material-symbols:code-sharp',
    accent: 'var(--accent-value)',
    title: 'Open Source and Free',
    body:
      'MIT licensed, with no paid tier. Read the code, change it, or build your own.',
  },
];

/**
 * The condensed inventory.
 *
 * The counterpart to `SHOWCASE`: that one argues, this one lists. So where the
 * two cover the same ground — smart home, AI, messaging — this is the side that
 * names the protocols and the vendors, and the showcase stays free of them.
 *
 * Nine entries and four accents that read on cream, so the accents rotate. The
 * order is what keeps two of the same colour from ever landing next to each
 * other, at one, two or three columns.
 */
export const FEATURES: FeatureItem[] = [
  {
    icon: 'material-symbols:smartphone-sharp',
    accent: 'var(--accent-trigger)',
    title: 'Device and Sensors',
    body:
      'Switch your radios, torch, volume, brightness and Do Not Disturb from a macro. React to how the phone is held, moved, covered or lit.',
  },
  {
    icon: 'material-symbols:wifi-sharp',
    accent: 'var(--accent-action)',
    title: 'Connectivity and Location',
    body:
      'Draw your places on a map and let your macros follow you. React when you join a network, plug something in or tap an NFC tag.',
  },
  {
    icon: 'material-symbols:notifications-active-sharp',
    accent: 'var(--accent-value)',
    title: 'Notifications and Messaging',
    body:
      'Post notifications with buttons and read the answer straight back into your macro. React to SMS and to other apps, and reply without opening them.',
  },
  {
    icon: 'material-symbols:home-sharp',
    accent: 'var(--accent-transform)',
    title: 'Smart Home',
    body:
      'Home Assistant, MQTT and Philips Hue, with discovery and pairing built in. React to what your devices report, and control them back.',
  },
  {
    icon: 'material-symbols:psychology-sharp',
    accent: 'var(--accent-trigger)',
    title: 'AI',
    body:
      'Bring your own model. Gemini, Anthropic, OpenAI, OpenRouter or anything OpenAI-compatible, local ones included.',
  },
  {
    icon: 'material-symbols:folder-open-sharp',
    accent: 'var(--accent-action)',
    title: 'Files and Photos',
    body:
      'Read, write, move and delete files through the system picker. Watch for new photos and screenshots, edit them and manage their metadata.',
  },
  {
    icon: 'material-symbols:calendar-month-sharp',
    accent: 'var(--accent-value)',
    title: 'Calendar and Mail',
    body:
      'Query, add and update appointments, or start a macro when one begins. Send and fetch mail, and react to what arrives.',
  },
  {
    icon: 'material-symbols:call-split-sharp',
    accent: 'var(--accent-transform)',
    title: 'Flow Control and Scripting',
    body:
      'Branch, loop, wait and stop. Keep values in variables, and drop into sandboxed JavaScript when no node fits.',
  },
  {
    icon: 'material-symbols:widgets-sharp',
    accent: 'var(--accent-trigger)',
    title: 'Widgets and Shortcuts',
    body:
      'Put a macro on your home screen as a one-tap tile. Add a panel for engine state and your manual triggers, or a launcher shortcut.',
  },
];

export const EXTENSIBILITY = [
  {
    accent: 'var(--accent-value)',
    icon: 'material-symbols:extension-sharp',
    title: 'Plugins',
    body:
      'A plugin is a separate Android app that adds triggers, actions, values and transforms to the palette. It runs in its own process under its own permissions — Easymatic never lends it any of its own.',
    linkLabel: 'Read the plugin guide',
    href: '/docs/',
  },
  {
    accent: 'var(--accent-action)',
    icon: 'material-symbols:terminal-sharp',
    title: 'Process API',
    body:
      'Other apps and scripts — Tasker, Termux, adb, anything that can send an intent — can run your macros and pass values in. Nothing is reachable until you put the trigger node on it yourself.',
    linkLabel: 'Read the API guide',
    href: '/docs/',
  },
] as const;

export const CLOSING = {
  title: 'Build your first macro.',
  body:
    'Easymatic is free and open source. Grab a build, wire something together, and see how far the graph goes.',
  note: 'Not on the Play Store yet — a download lands here when the first release is cut.',
} as const;

export interface FooterColumn {
  heading: string;
  /** Colour for the column label. */
  accent: string;
  links: NavLink[];
}

export const FOOTER_COLUMNS: FooterColumn[] = [
  {
    heading: 'Product',
    accent: 'var(--accent-trigger)',
    links: [
      { label: 'Download', href: '/download', placeholder: true },
      { label: 'Features', href: '#features' },
      { label: 'Changelog', href: '/changelog' },
    ],
  },
  {
    heading: 'Documentation',
    accent: 'var(--accent-action)',
    links: [
      { label: 'Overview', href: '/docs/' },
      { label: 'Adding nodes', href: '/docs/', placeholder: true },
      { label: 'Plugins', href: '/docs/', placeholder: true },
      { label: 'External API', href: '/docs/', placeholder: true },
    ],
  },
  {
    heading: 'Project',
    accent: 'var(--accent-value)',
    links: [
      { label: 'GitHub', href: GITHUB_URL },
      { label: 'Issues', href: GITHUB_URL + '/issues' },
      { label: 'MIT licence', href: GITHUB_URL + '/blob/main/LICENSE' },
      { label: 'Privacy', href: '/privacy' },
    ],
  },
];
