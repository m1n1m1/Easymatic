package io.github.m1n1m1.easymatic.plugin

import android.app.Activity
import android.content.Intent
import io.github.m1n1m1.easymatic.nodeapi.wire.NodeCallWire
import io.github.m1n1m1.easymatic.nodeapi.wire.PluginJson

/**
 * The action a plugin's own chooser Activity advertises.
 *
 * The third and last thing Easymatic reads out of a plugin's Android manifest, after the
 * service and the optional settings screen — and, like the settings screen, a manifest
 * convention rather than a wire field. The reason is the same and worth restating because
 * it is the one rule this whole boundary rests on: everything crossing the binder is inert
 * data by construction, and a component name is a thing to *launch*. Easymatic resolves
 * this action against the plugin's **own package** through `PackageManager` and starts the
 * result by component; nothing the plugin sends is ever started.
 *
 * One Activity serves every [ChoiceChooser.SCREEN][io.github.m1n1m1.easymatic.domain.model.config.ChoiceChooser.SCREEN]
 * field the plugin declares, dispatching on [PluginChoiceRequest.source].
 */
const val PLUGIN_CHOICE_ACTION = "io.github.m1n1m1.easymatic.action.PLUGIN_CHOICE"

/** Extra carrying the namespaced typeId of the node whose field is being filled in. */
const val EXTRA_TYPE_ID = "io.github.m1n1m1.easymatic.extra.TYPE_ID"

/** Extra carrying the `source` the field declared. */
const val EXTRA_SOURCE = "io.github.m1n1m1.easymatic.extra.SOURCE"

/** Extra carrying the scoping config, as a `NodeCallWire` JSON document. */
const val EXTRA_CONFIG = "io.github.m1n1m1.easymatic.extra.CONFIG"

/** Extra carrying the chosen id back, on `RESULT_OK`. */
const val EXTRA_VALUE = "io.github.m1n1m1.easymatic.extra.VALUE"

/**
 * What Easymatic is asking a chooser Activity for.
 *
 * The same three things a [PluginChoiceSource] is given, in the same meanings — [source]
 * is the field's own key, echoed back untouched, and [config] carries the siblings named
 * in `scopedBy` and nothing else. A plugin implementing both kinds of chooser can share
 * one lookup between them.
 */
class PluginChoiceRequest(
    val typeId: String,
    val source: String,
    val config: Map<String, String>,
) {
    companion object {
        /**
         * Reads the request out of the Activity's launch [intent].
         *
         * Every field degrades to blank or empty rather than throwing: an Activity that
         * crashes on launch is indistinguishable, from Easymatic's side, from one that is
         * not there at all.
         */
        fun from(intent: Intent?): PluginChoiceRequest = runCatching {
            PluginChoiceRequest(
                typeId = intent?.getStringExtra(EXTRA_TYPE_ID).orEmpty(),
                source = intent?.getStringExtra(EXTRA_SOURCE).orEmpty(),
                config = intent?.getStringExtra(EXTRA_CONFIG)
                    ?.let { json ->
                        runCatching { PluginJson.decodeFromString(NodeCallWire.serializer(), json) }.getOrNull()
                    }
                    ?.config
                    .orEmpty(),
            )
        }.getOrElse { PluginChoiceRequest("", "", emptyMap()) }
    }
}

/**
 * Answers Easymatic with [value] and closes this Activity.
 *
 * **The id, and nothing else.** A display name is deliberately not part of this contract:
 * a node's config is a flat `Map<String, String>` with one value per property, so there is
 * nowhere for a second string to live, and a label the field showed until it was next
 * opened would be worse than one it never showed at all.
 */
fun Activity.finishWithChoice(value: String) {
    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_VALUE, value))
    finish()
}

/**
 * Closes this Activity without choosing anything.
 *
 * Equivalent to backing out — the field keeps whatever it already held, which matters more
 * than it sounds: a chooser that cleared the field on dismissal would make "let me just
 * look at the options" a destructive act.
 */
fun Activity.finishWithoutChoosing() {
    setResult(Activity.RESULT_CANCELED)
    finish()
}
