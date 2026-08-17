package com.example.ottomatic.nodeapi.plugin

/**
 * Bounds on what a plugin may declare and send.
 *
 * A plugin's manifest is untrusted input arriving over a binder, so every list and
 * every string needs a ceiling. What these are *not* is a security boundary: a
 * plugin runs its own code in its own process under its own permissions, and no
 * number here changes that. They exist so that a malformed or hostile declaration
 * degrades into a rejection with a sentence attached, rather than into an
 * out-of-memory, a `TransactionTooLargeException`, or a palette with forty thousand
 * rows in it.
 *
 * Every one of them is enforced **per node**, not per plugin, following the
 * quarantine doctrine the graph validator already follows: block the smallest thing
 * that is actually broken. One node with nine levels of nested schema costs that
 * node, not the other twelve in the same app.
 */
object PluginLimits {

    /** Nodes one plugin may contribute. */
    const val MAX_NODES_PER_PLUGIN = 64

    /** Ports on one node, across both kinds and both directions. */
    const val MAX_PORTS_PER_NODE = 16

    /** Rows in one node's config form. */
    const val MAX_CONFIG_FIELDS_PER_NODE = 24

    /** Choices in one enum config field. */
    const val MAX_ENUM_OPTIONS = 64

    /**
     * Execution output routes one action may name.
     *
     * Small on purpose. Routes are *outcomes*, and a node with more than a handful of
     * them is describing data rather than control flow — that belongs on the data port,
     * where `action.if` can compare it, rather than as five more edges on the card.
     */
    const val MAX_ROUTES_PER_NODE = 4

    /**
     * Options one `choices` call may answer with.
     *
     * Larger than [MAX_ENUM_OPTIONS] by an order of magnitude because these are the
     * user's own rows — pages, boards, playlists — rather than a declaration's fixed
     * modes, and a workspace with three hundred pages is ordinary. Beyond this the list
     * is truncated and the truncation is announced, never silent.
     */
    const val MAX_CHOICES = 500

    /**
     * Input extras one `@IntentChoice` field may put on its launch.
     *
     * Small, because the ones that exist in the wild are one or two — a scan mode, a
     * ringtone type, a title. What this actually bounds is the size of a bundle the host
     * assembles on a plugin's word and hands to a third app, which is the one place in this
     * feature where an unbounded declaration would reach past both of them.
     */
    const val MAX_INTENT_EXTRAS = 8

    /**
     * How deeply a port schema may nest.
     *
     * `SchemaWire` is recursive, so without this a few hundred bytes of JSON
     * declaring a list of a list of a list … expands into a structure that
     * `isAssignableFrom` walks on every drop check in the editor.
     */
    const val MAX_SCHEMA_DEPTH = 8

    /** Display names, descriptions and labels. */
    const val MAX_STRING_LENGTH = 512

    /** A typeId, including its mandatory `plugin:<package>/` prefix. */
    const val MAX_TYPE_ID_LENGTH = 128

    /** The whole declarations document. */
    const val MAX_MANIFEST_BYTES = 256 * 1024

    /**
     * One [com.example.ottomatic.nodeapi.wire.ItemWire], each way.
     *
     * Well under the ~1 MB `TransactionTooLargeException` ceiling, with room for a
     * whole call's worth of ports beside it.
     */
    const val MAX_ITEM_BYTES = 256 * 1024

    /** Run-log lines one call may produce, and characters in each. */
    const val MAX_LOG_LINES = 50
    const val MAX_LOG_CHARS = 2_000

    /**
     * The mandatory typeId prefix for a node from [packageName].
     *
     * The host derives this from what `PackageManager` reports the resolved
     * service's package to be — never from a field the plugin sent — so forging one
     * is impossible rather than merely detectable. Two properties follow and both
     * are proofs rather than scans: no built-in typeId contains `plugin:`, so a
     * collision with the app's own nodes cannot happen; and package names are
     * unique on a device, so a collision between two plugins cannot either.
     */
    fun typeIdPrefix(packageName: String): String = "plugin:$packageName/"

    /** True when [typeId] belongs to some plugin rather than to the app itself. */
    fun isPluginTypeId(typeId: String): Boolean = typeId.startsWith("plugin:")
}
