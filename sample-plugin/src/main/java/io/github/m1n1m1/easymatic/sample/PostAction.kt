package io.github.m1n1m1.easymatic.sample

import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.ChoiceChooser
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Multiline
import io.github.m1n1m1.easymatic.domain.model.config.PluginChoice
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.nodeapi.wire.OptionWire
import io.github.m1n1m1.easymatic.plugin.PluginAction
import io.github.m1n1m1.easymatic.plugin.PluginChoiceSource
import io.github.m1n1m1.easymatic.plugin.PluginContext
import io.github.m1n1m1.easymatic.plugin.PluginOutput
import io.github.m1n1m1.easymatic.plugin.pluginActionNode
import io.github.m1n1m1.easymatic.plugin.routes
import kotlinx.serialization.Serializable

/**
 * Config for the sample's second action — the one that talks to a service.
 *
 * [space] and [board] are the pair worth reading. Both are `@PluginChoice`, both store an
 * id nobody would ever want to type, and the second is **scoped by** the first: choosing a
 * space is what makes that space's boards listable, exactly as choosing a Home Assistant
 * hub narrows its entities in the host's own nodes. Neither could have been a `@Picker` —
 * every `PickerKind` names something of the *user's*, and these are this plugin's.
 *
 * The `source` strings are this plugin's own keys and mean nothing to Easymatic; it passes
 * them back untouched on the `choices` call, which is what lets one node offer two lists.
 */
@Serializable
data class PostConfig(
    @Label("Space") @PluginChoice(source = SPACE_SOURCE) val space: String = "",
    @Label("Board") @PluginChoice(source = BOARD_SOURCE, scopedBy = ["space"]) val board: String = "",
    /**
     * The third chooser, and the one Easymatic does not draw.
     *
     * A card is picked out of a board that may hold thousands of them, in a tree, with a
     * search box — which a flat `List<OptionWire>` cannot present at any length. So this
     * field is `ChoiceChooser.SCREEN`: the plugin exports an Activity, Easymatic opens it
     * by component and takes back one string. Nothing else about the field changes — it is
     * still read-only, still scoped, still stores an opaque id nobody would type.
     */
    @Label("Card")
    @PluginChoice(source = CARD_SOURCE, scopedBy = ["board"], chooser = ChoiceChooser.SCREEN)
    val card: String = "",
    @Label("What to post") @Multiline @Wired val text: String = "",
)

/** What the node hands downstream when the post lands. */
@Serializable
data class Posted(
    val id: String,
    val board: String,
)

/**
 * An action that can *fail*, and says so on the card.
 *
 * The node the two-member `ExecOutputsWire` could not express. Every plugin action worth
 * writing is a call to somebody else's server, so "it was rejected" is the second ordinary
 * outcome rather than an exception — and until protocol 2 there were exactly two shapes to
 * say it in, neither of which fits: a single `out` made a rejected post indistinguishable
 * from a published one, and a branch said it with ports labelled **true** and **false**,
 * which is a comparison's vocabulary rather than an outcome's.
 *
 * Two things to copy from this file:
 *
 *  - **`out` is declared first.** The host lands anything it cannot route honestly on the
 *    first route — an undeclared answer, and a call it could not make at all — so the
 *    first one has to be the outcome that means *carried on*. Putting `error` first would
 *    make an unreachable plugin claim the post definitely failed, which the host has no
 *    way to know: the call may well have succeeded and failed on the way back.
 *  - **The failure carries no data.** `PluginOutput.failed` emits nothing on `posted`,
 *    rather than a `Posted("", "")` that reads downstream exactly like a success.
 */
class PostAction : PluginAction<PostConfig, Posted>, PluginChoiceSource<PostConfig> {

    override val definition = pluginActionNode<PostConfig, Posted>(
        typeId = "post",
        displayName = "Post to board",
        description = "Posts text to a board — a worked example of a failable action",
        icon = NodeIcon.SEND,
        output = dataOut<Posted>("posted", label = "Posted"),
        execOutputs = routes(
            "out" to "When posted",
            "error" to "When it fails",
        ),
    )

    override suspend fun execute(config: PostConfig, context: PluginContext): PluginOutput<Posted> {
        val refusal = when {
            config.board.isBlank() -> "No board chosen, so there is nowhere to post"
            config.text.isBlank() -> "Nothing to post"
            else -> null
        }
        if (refusal != null) {
            context.log(refusal)
            return PluginOutput.failed("error")
        }
        // A real plugin would call its own API here, under its own permissions, and route
        // to "error" on anything the server refused.
        context.log("Posted ${config.text.length} characters to ${config.board}")
        return PluginOutput(Posted(id = "post-${config.text.hashCode()}", board = config.board))
    }

    /**
     * The lists behind the two choice fields.
     *
     * A real plugin fetches these from its own API with its own credentials. Note what is
     * *not* here: no Easymatic facade, no host library, no way to read a variable or a
     * place. Everything answerable from this method is this plugin's already, which is the
     * whole reason the host is willing to ask.
     */
    override suspend fun choices(
        source: String,
        config: PostConfig,
        context: PluginContext,
    ): List<OptionWire> = when (source) {
        SPACE_SOURCE -> SPACES.map { OptionWire(value = it.first, label = it.second) }
        // Scoped: the boards offered are the chosen space's, which is why `scopedBy`
        // exists at all. An unchosen space lists nothing rather than everything — a
        // chooser that ignores its scope is a chooser that offers unusable answers.
        BOARD_SOURCE -> BOARDS[config.space].orEmpty().map { OptionWire(value = it.first, label = it.second) }
        // CARD_SOURCE is deliberately absent: that field is `ChoiceChooser.SCREEN`, so it
        // is answered by SampleChoiceActivity and never reaches this method at all.
        else -> emptyList()
    }

    private companion object {
        val SPACES = listOf(
            "spc_7f2a" to "Home",
            "spc_91c4" to "Work",
        )

        val BOARDS = mapOf(
            "spc_7f2a" to listOf("brd_1a" to "Shopping", "brd_1b" to "Repairs"),
            "spc_91c4" to listOf("brd_2a" to "Standup", "brd_2b" to "Roadmap"),
        )
    }
}

/** This plugin's key for "which space?". Opaque to Easymatic, which echoes it back. */
const val SPACE_SOURCE = "spaces"

/** This plugin's key for "which board?", narrowed by the chosen space. */
const val BOARD_SOURCE = "boards"

/** This plugin's key for "which card?" — the one served by its own screen. */
const val CARD_SOURCE = "cards"

/**
 * The cards on each board, as [SampleChoiceActivity] offers them.
 *
 * Here rather than in the Activity so the two halves of one plugin agree about what
 * exists — the same reason `PostAction.choices` and the node's own body share a config
 * class rather than each parsing the form.
 */
val CARDS: Map<String, List<Pair<String, String>>> = mapOf(
    "brd_1a" to listOf("crd_a1" to "Milk", "crd_a2" to "Coffee beans"),
    "brd_1b" to listOf("crd_b1" to "Fix the tap", "crd_b2" to "Bleed radiators"),
    "brd_2a" to listOf("crd_c1" to "Monday notes", "crd_c2" to "Blockers"),
    "brd_2b" to listOf("crd_d1" to "Q3 goals", "crd_d2" to "Q4 goals"),
)
