package io.github.m1n1m1.easymatic.data.plugin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A plugin the user has enabled, and the signer they enabled.
 *
 * The digest is what makes this an identity rather than a name. A package name is not
 * one: uninstalling `com.acme.tools` frees the name for anybody, so an enable stored
 * by name alone would silently transfer the user's decision to whichever app claimed
 * it next. Recording the signer means an update signed by the same key re-enables in
 * silence — which is what a person expects of an app update — while a same-named
 * package signed by somebody else lands back disabled with the reason on screen.
 *
 * This is `HueTransport`'s trust-on-first-use, in a place where the consequences are
 * milder and the argument is identical.
 */
@Serializable
data class EnabledPlugin(
    val packageName: String,
    val signerSha256: String,
)

@Serializable
private data class EnabledPlugins(val plugins: List<EnabledPlugin> = emptyList())

/**
 * Which plugins the user has enabled, persisted.
 *
 * Deliberately tiny, and deliberately *not* a cache of anything a plugin said. What a
 * plugin declares is re-fetched and re-validated on every bind, because a plugin app
 * updates on its own schedule and a stale cached declaration is a node that renders
 * perfectly and calls something that is no longer there.
 */
class PluginRepository(filesDir: File) {

    private val file = File(File(filesDir, DIRECTORY).apply { mkdirs() }, FILE_NAME)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val state = MutableStateFlow(read())

    /** Every enabled plugin, as a flow so the Plugins screen and the registry both follow it. */
    val enabled: StateFlow<List<EnabledPlugin>> = state

    /** The signer the user enabled for [packageName], or null when it is not enabled. */
    fun signerFor(packageName: String): String? =
        state.value.firstOrNull { it.packageName == packageName }?.signerSha256

    /** Records the user's decision to enable [packageName], as signed by [signerSha256]. */
    fun enable(packageName: String, signerSha256: String) {
        write(state.value.filterNot { it.packageName == packageName } + EnabledPlugin(packageName, signerSha256))
    }

    /**
     * Forgets [packageName].
     *
     * Called both when the user disables a plugin and when its package is *uninstalled*.
     * The second is the one worth thinking about: keeping the entry would mean that a
     * package a different developer later publishes under the same name inherits an
     * enable the user granted to somebody else. Reinstalling costs one tap, and that is
     * the honest side to err on.
     */
    fun disable(packageName: String) {
        write(state.value.filterNot { it.packageName == packageName })
    }

    private fun write(plugins: List<EnabledPlugin>) {
        state.value = plugins
        runCatching { file.writeText(json.encodeToString(EnabledPlugins.serializer(), EnabledPlugins(plugins))) }
    }

    private fun read(): List<EnabledPlugin> = runCatching {
        json.decodeFromString(EnabledPlugins.serializer(), file.readText()).plugins
    }.getOrDefault(emptyList())

    private companion object {
        const val DIRECTORY = "plugins"
        const val FILE_NAME = "enabled.json"
    }
}
