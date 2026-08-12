package com.example.ottomatic.data.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * An app the user has allowed to call Ottomatic, and the signer they allowed.
 *
 * `EnabledPlugin`'s shape and `EnabledPlugin`'s argument, in the opposite direction:
 * a package *name* is not an identity, because uninstalling `com.acme.tools` frees
 * the name for anybody, so an approval stored by name alone would silently transfer
 * the user's decision to whichever app claimed it next. Recording the signer means an
 * ordinary app update keeps working in silence, while a same-named package signed by
 * somebody else lands back unapproved with the reason on screen.
 *
 * [label] and [approvedAtMs] are here for the App access screen and nothing else — a
 * row saying "com.acme.tools" and no more is a row nobody can make a decision about.
 * [label] is cached rather than resolved on render for `SmartHomeRef`'s reason: the
 * app may since have been uninstalled, and the screen still has to be able to name
 * what it is offering to revoke.
 */
@Serializable
data class ApprovedCaller(
    val packageName: String,
    val signerSha256: String,
    val label: String = "",
    val approvedAtMs: Long = 0L,
)

@Serializable
private data class ApprovedCallers(val callers: List<ApprovedCaller> = emptyList())

/**
 * Which apps the user has allowed to call the process API, persisted.
 *
 * `PluginRepository`'s twin, and deliberately as small: it stores the user's decision
 * and nothing derived from it. What a caller is *allowed to do* is not stored here at
 * all — approval is package-wide and the reachable set is whatever `trigger.api` nodes
 * exist right now — so there is no stale copy of anything to go wrong.
 */
class ApiCallerRepository(filesDir: File) {

    private val file = File(File(filesDir, DIRECTORY).apply { mkdirs() }, FILE_NAME)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val state = MutableStateFlow(read())

    /** Every approved caller, as a flow so the App access screen follows it. */
    val approved: StateFlow<List<ApprovedCaller>> = state

    /** The signer the user approved for [packageName], or null when it is not approved. */
    fun signerFor(packageName: String): String? =
        state.value.firstOrNull { it.packageName == packageName }?.signerSha256

    /** Records the user's decision to allow [packageName], as signed by [signerSha256]. */
    fun approve(packageName: String, signerSha256: String, label: String, atMs: Long) {
        write(
            state.value.filterNot { it.packageName == packageName } +
                ApprovedCaller(packageName, signerSha256, label, atMs),
        )
    }

    /**
     * Forgets [packageName].
     *
     * Called when the user revokes and when the signer no longer matches. Deliberately
     * not called on uninstall the way `PluginRepository.disable` is — nothing here
     * scans for installed packages, so a stale row is harmless and the signer check on
     * every call is what actually stops a name-squatter inheriting the approval.
     */
    fun revoke(packageName: String) {
        write(state.value.filterNot { it.packageName == packageName })
    }

    private fun write(callers: List<ApprovedCaller>) {
        state.value = callers
        runCatching { file.writeText(json.encodeToString(ApprovedCallers.serializer(), ApprovedCallers(callers))) }
    }

    private fun read(): List<ApprovedCaller> = runCatching {
        json.decodeFromString(ApprovedCallers.serializer(), file.readText()).callers
    }.getOrDefault(emptyList())

    private companion object {
        const val DIRECTORY = "api"
        const val FILE_NAME = "callers.json"
    }
}
