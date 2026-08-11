package com.example.ottomatic.data.plugin

import android.util.Log
import com.example.ottomatic.domain.registry.PluginNodeEntry
import com.example.ottomatic.domain.registry.PluginNodes
import com.example.ottomatic.nodeapi.plugin.PluginDeclarationValidator
import com.example.ottomatic.nodeapi.plugin.PluginLimits
import com.example.ottomatic.nodeapi.plugin.RejectedPluginNode
import com.example.ottomatic.nodeapi.wire.PluginJson
import com.example.ottomatic.nodeapi.wire.PluginManifestWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One plugin app on the device, as the Plugins screen shows it. */
data class InstalledPlugin(
    val packageName: String,
    val label: String,
    val signerSha256: String,
    val enabled: Boolean,
    /** Manifest permissions this plugin's own package declares. */
    val permissions: List<String> = emptyList(),
    /** Nodes it contributed, once enabled and read. */
    val nodeCount: Int = 0,
    /** Nodes the host would not take, with the reason for each. */
    val rejected: List<RejectedPluginNode> = emptyList(),
    /** Why none of it could be read at all, when that is the case. */
    val problem: String? = null,
)

/**
 * Discovery, trust, and turning what a plugin says into what the app serves.
 *
 * ## Nothing appears until the user says so
 *
 * A discovered plugin contributes no nodes — not to the palette, not to
 * `NodeTypeRegistry.all`, not to palette search — until it is enabled on the Plugins
 * screen. Discovery is a list of candidates; enabling is the decision.
 *
 * ## The namespace is derived, never trusted
 *
 * The typeId prefix each of a plugin's nodes must carry is built from the package name
 * **`PackageManager` reports for the resolved service**, not from anything the plugin
 * sent. That turns two properties into proofs rather than checks: a plugin cannot
 * claim one of the app's typeIds, because no built-in contains `plugin:`; and two
 * plugins cannot collide, because package names are unique on a device.
 *
 * ## Declarations are re-read, never cached across a bind
 *
 * A plugin app updates on its own schedule. A cached declaration would render a node
 * perfectly and call something that is no longer there, which is the exact failure
 * shape the whole design tries to avoid, so the manifest is fetched on every refresh.
 */
class PluginRegistry(
    private val packages: PluginPackages,
    private val repository: PluginRepository,
    private val connections: PluginConnections,
    private val scope: CoroutineScope,
) {

    private val mutex = Mutex()
    private val state = MutableStateFlow<List<InstalledPlugin>>(emptyList())

    /** Every plugin app installed, enabled or not, for the Plugins screen. */
    val installed: StateFlow<List<InstalledPlugin>> = state

    /** Re-discovers, re-reads every enabled plugin, and republishes [PluginNodes]. */
    fun refresh() {
        scope.launch { refreshNow() }
    }

    /**
     * Re-arms whatever a plugin was providing after its process came back.
     *
     * `BIND_AUTO_CREATE` reconnects on its own, but a reconnected binding is not a
     * restored subscription: a plugin trigger's registration died with the process, and
     * on the host side its `callbackFlow` is still open and silent. That silence is the
     * exact failure this integration is most prone to, so the binding coming back has to
     * *do* something.
     *
     * What it does is deliberately not a bespoke reconnection protocol. It re-reads the
     * declarations — the process may have come back because the app was updated — and
     * then asks the engine for an ordinary `ACTION_REARM_CHANGED`, the same path a moved
     * geofence takes. That path already cancels and *joins* the previous arm before
     * starting the next, so the old registration is provably gone before a new one is
     * made, and "armed" stays a single fact with a single owner.
     *
     * [MAX_REARMS_PER_PACKAGE] is what stops a plugin that crashes *while arming* from
     * spinning: without it, arm → crash → reconnect → arm is a loop with no exit that
     * would keep the CPU awake indefinitely. Past the cap the plugin's nodes stay
     * published but nothing re-arms until the user acts, which is the same stance the
     * app takes on a macro that cannot arm at boot.
     */
    fun onReconnected(packageName: String, rearm: () -> Unit) {
        val attempts = rearms.merge(packageName, 1, Int::plus) ?: 1
        if (attempts > MAX_REARMS_PER_PACKAGE) {
            Log.w(TAG, "$packageName has reconnected $attempts times; not re-arming again")
            return
        }
        scope.launch {
            refreshNow()
            rearm()
        }
    }

    /**
     * How many times each package has reconnected and been re-armed.
     *
     * Cleared when the user deliberately enables a plugin: an explicit action is a
     * fresh start, and a plugin the user has just fixed should not still be capped.
     */
    private val rearms = java.util.concurrent.ConcurrentHashMap<String, Int>()

    suspend fun refreshNow() = mutex.withLock {
        val discovered = packages.discover().map { it.toInstalled() }
        val enabledSigners = repository.enabled.value.associate { it.packageName to it.signerSha256 }
        val entries = mutableListOf<PluginNodeEntry>()
        val summaries = mutableListOf<InstalledPlugin>()

        for (candidate in discovered) {
            val expectedSigner = enabledSigners[candidate.packageName]
            when {
                expectedSigner == null -> summaries += candidate
                expectedSigner != candidate.signerSha256 -> {
                    // Same name, different developer. Forget the enable rather than
                    // honour it: the user granted it to somebody else.
                    repository.disable(candidate.packageName)
                    connections.disconnect(candidate.packageName)
                    summaries += candidate.copy(
                        enabled = false,
                        problem = "This app is now signed by a different developer, so it has been " +
                            "turned off. Enable it again if you trust the new one.",
                    )
                }
                // Enabled here rather than at discovery: whether a package is enabled is
                // a fact about the repository, and discovery only knows the device.
                else -> summaries += read(candidate.copy(enabled = true), entries)
            }
        }

        // An enabled plugin that is no longer installed loses its enable outright, so a
        // package somebody else later publishes under that name cannot inherit it.
        val installedNames = discovered.mapTo(mutableSetOf()) { it.packageName }
        for (gone in enabledSigners.keys - installedNames) {
            repository.disable(gone)
            connections.disconnect(gone)
        }

        state.value = summaries
        PluginNodes.hydrate(entries)
    }

    /** Enables [packageName] as currently signed, then re-reads it. */
    suspend fun enable(packageName: String) {
        val signer = packages.signerOf(packageName) ?: return
        rearms.remove(packageName)
        repository.enable(packageName, signer)
        refreshNow()
    }

    /** Disables [packageName] and drops its binding. */
    suspend fun disable(packageName: String) {
        repository.disable(packageName)
        connections.disconnect(packageName)
        refreshNow()
    }

    /** Fetches, validates and accumulates one enabled plugin's nodes. */
    @Suppress("ReturnCount") // Three ways to give up before there is anything to validate.
    private suspend fun read(
        candidate: InstalledPlugin,
        into: MutableList<PluginNodeEntry>,
    ): InstalledPlugin {
        val channel = BinderPluginChannel(candidate.packageName, connections, scope)
        val manifest = fetch(channel, candidate.packageName)
            .getOrElse { cause -> return candidate.copy(problem = cause.message) }

        val validation = PluginDeclarationValidator.validate(manifest, candidate.packageName)
        validation.fatal?.let { return candidate.copy(problem = it) }

        into += validation.accepted.map { node ->
            PluginNodeEntry(
                packageName = candidate.packageName,
                pluginName = manifest.pluginName.ifBlank { candidate.label },
                definition = node.definition,
                configSchema = node.configSchema,
                declaration = node.declaration,
                channel = channel,
                missingPermissions = node.declaration.permissions.filterNot {
                    packages.isGranted(it, candidate.packageName)
                },
            )
        }
        return candidate.copy(nodeCount = validation.accepted.size, rejected = validation.rejected)
    }

    /**
     * The plugin's manifest, or the sentence to show instead.
     *
     * A `Result<PluginManifestWire, String>` in all but name: three different things
     * can go wrong before there is anything to validate, and each needs its own words
     * on the Plugins screen rather than a shared "did not work".
     */
    @Suppress("ReturnCount") // One early return per thing that can go wrong, each with its own sentence.
    private suspend fun fetch(channel: BinderPluginChannel, packageName: String): Result<PluginManifestWire> {
        val json = channel.declarations()
            ?: return Result.failure(Problem("Could not be reached. It may have been stopped by the system."))
        if (json.length > PluginLimits.MAX_MANIFEST_BYTES) {
            return Result.failure(
                Problem(
                    "Sent more than ${PluginLimits.MAX_MANIFEST_BYTES / BYTES_PER_KIB} KB of " +
                        "declarations, which is more than one plugin may.",
                ),
            )
        }
        return runCatching { PluginJson.decodeFromString(PluginManifestWire.serializer(), json) }
            .recoverCatching { cause ->
                Log.w(TAG, "Manifest from $packageName would not parse", cause)
                throw Problem("Sent something Ottomatic could not read.")
            }
    }

    /** Carries a user-facing sentence through a [Result]. */
    private class Problem(override val message: String) : Exception(message)

    private companion object {
        const val TAG = "PluginRegistry"
        const val BYTES_PER_KIB = 1024

        /** See [onReconnected]. A plugin that crashes on arm must not spin forever. */
        const val MAX_REARMS_PER_PACKAGE = 6
    }
}

/** A discovered package as the Plugins screen first sees it: found, not yet read. */
private fun DiscoveredPackage.toInstalled(): InstalledPlugin = InstalledPlugin(
    packageName = packageName,
    label = label,
    signerSha256 = signerSha256,
    // Filled in by the caller, which is the only place that knows what was enabled.
    enabled = false,
    permissions = permissions,
)
