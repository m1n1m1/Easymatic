package io.github.m1n1m1.easymatic.engine.plugin

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.domain.registry.ConfigOption
import io.github.m1n1m1.easymatic.domain.registry.PluginNodes
import io.github.m1n1m1.easymatic.nodeapi.plugin.PluginLimits
import io.github.m1n1m1.easymatic.nodeapi.wire.ChoiceListWire
import io.github.m1n1m1.easymatic.nodeapi.wire.NodeCallWire
import io.github.m1n1m1.easymatic.nodeapi.wire.PluginJson
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a `@PluginChoice` field may be set to, asked of the plugin that owns it.
 *
 * ## Why the authority is the plugin's and the timeout is the host's
 *
 * A plugin may not declare `@Picker`, because every `PickerKind` names something of the
 * *user's* — their places, their macros, their mailboxes — and handing one over would be
 * exactly the capability the whole separate-APK design exists to withhold. What that
 * correctly-drawn line also did, unnoticed, was leave a plugin no way to offer a list of
 * its **own**: "which of your Pages?" was a text field asking for a sixteen-digit id.
 *
 * So the host asks and hands over nothing. Everything a plugin can answer here is
 * something it already had, under its own permissions, in its own process — and the
 * `providerTypeId` it is asked *as* was stamped by the host from a typeId it had already
 * resolved and namespaced, so one plugin cannot answer for another.
 *
 * ## Nothing is cached
 *
 * The list is the user's own data and it changes without telling anybody. A cached page
 * list would be a chooser confidently offering a page that was deleted last week, which
 * is worse than a chooser that takes a moment — and the alternative failure, a slow
 * chooser, is one the person looking at it can see and understand.
 *
 * ## Every failure is a sentence, not an exception
 *
 * [PluginNodeRunner]'s stance, with one difference that decides where the words go: this
 * is the only plugin transaction answered while somebody is *watching*. A run-log line is
 * right for a failure nobody is looking at; here the reason belongs in the chooser.
 */
object PluginChoiceReader {

    /**
     * Longer than a value read's two seconds, shorter than an action's thirty.
     *
     * A read is bound tightly because the pull side promises to be cheap and a macro is
     * mid-run. This is neither: it may legitimately go to the network, because listing
     * somebody's pages is an authenticated round trip and there is no local cache to
     * answer from — but a person is watching a dialog, and past a few seconds a spinner
     * stops reading as *working* and starts reading as *stuck*.
     */
    private const val CHOICES_TIMEOUT_MS = 6_000L

    /**
     * The options for [source] on [typeId], given the config the node currently holds.
     *
     * [config] is passed through so a field declared `@PluginChoice(scopedBy = [...])` can
     * narrow on a sibling the user has already filled in; the caller sends the *declared*
     * scope and nothing else, so a chooser cannot quietly depend on a field whose change
     * `keysScopedBy` would not clear it for. It carries no wired data either: at the moment
     * somebody opens a chooser nothing has run, so there is nothing to send.
     */
    @Suppress("ReturnCount") // Gone, unreachable, unreadable and answered — four real outcomes.
    suspend fun read(
        typeId: NodeTypeId,
        source: String,
        config: Map<ConfigKey, String>,
    ): ChoiceList {
        val entry = PluginNodes.byId(typeId)
            ?: return ChoiceList(problem = "This node's plugin is not installed or is turned off.")
        val request = PluginJson.encodeToString(
            NodeCallWire.serializer(),
            NodeCallWire(config = config.mapKeys { (key, _) -> key.value }),
        )
        val reply = withTimeoutOrNull(CHOICES_TIMEOUT_MS) {
            runCatching { entry.channel.choices(typeId.value, source, request) }.getOrNull()
        } ?: return ChoiceList(problem = "${entry.pluginName} did not answer.")
        val answer = runCatching { PluginJson.decodeFromString(ChoiceListWire.serializer(), reply) }.getOrNull()
            ?: return ChoiceList(problem = "${entry.pluginName} sent something Easymatic could not read.")
        return ChoiceList(
            options = answer.options
                .take(PluginLimits.MAX_CHOICES)
                .map { ConfigOption(it.value, it.label.ifBlank { it.value }) },
            problem = answer.problem,
        )
    }
}

/**
 * One answer to "what may this field be set to?".
 *
 * [options] and [problem] are **not exclusive**, and that is worth the pair rather than a
 * sealed result: a plugin that can list two workspaces but not reach the third has both,
 * and collapsing it to one or the other would either hide the two or hide the reason.
 */
data class ChoiceList(
    val options: List<ConfigOption> = emptyList(),
    val problem: String? = null,
)
