package io.github.m1n1m1.easymatic.data.api

import io.github.m1n1m1.easymatic.data.plugin.PluginPackages
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether a calling app may use the process API — the decision half over
 * [ApiCallerRepository]'s storage half.
 *
 * The split is `PluginRegistry`'s over `PluginRepository`, and the reason it is worth
 * repeating is [isApproved]: an approval is only good while the app is **still signed
 * by the certificate that was approved**, and something has to re-check that on every
 * call rather than trusting a row in a file. That check is the whole class.
 *
 * `PluginPackages` is reused rather than reimplemented, and specifically its
 * [PluginPackages.signerOf]. Two hand-rolled SHA-256s over a signing certificate is
 * exactly the "one of them gets a different constant, silently" failure
 * `KeystoreSecrets` was extracted to prevent — and here that failure would not throw,
 * it would simply stop matching, revoking every approval on the device with no error
 * anywhere.
 */
class ApiCallers(
    private val packages: PluginPackages,
    private val repository: ApiCallerRepository,
) {

    /** Every approved caller, for the App access screen. */
    val approved: StateFlow<List<ApprovedCaller>> = repository.approved

    /**
     * Whether [packageName] may call, **revoking it if the signer has changed**.
     *
     * The revoke-on-mismatch is deliberate and mirrors `PluginRegistry.refreshNow`'s
     * force-disable: an app re-signed by somebody else is not the app the user
     * approved, and leaving the row would mean the next check has to make the same
     * discovery again. Dropping it also makes the App access screen tell the truth
     * with no extra state — the row is simply gone, and the app can ask again.
     *
     * A package the system cannot name a signer for answers **false**: that is an app
     * being uninstalled underneath us, and there is no reading of "unknown signer"
     * that should be allowed to run a macro.
     */
    fun isApproved(packageName: String): Boolean {
        val expected = repository.signerFor(packageName) ?: return false
        // A null signer counts as a mismatch rather than as its own case: it means the
        // package is going away underneath us, and there is no reading of "unknown
        // signer" that should be allowed to run a macro.
        val matches = packages.signerOf(packageName) == expected
        if (!matches) repository.revoke(packageName)
        return matches
    }

    /**
     * Records the user's approval of [packageName]. False when the system cannot name
     * its signer, which is the only way this fails.
     *
     * Called from the consent screen alone, and never from a call path: an app cannot
     * approve itself, which is why nothing here takes the decision — it only writes
     * one down.
     */
    fun approveNow(packageName: String, label: String, atMs: Long = System.currentTimeMillis()): Boolean {
        val signer = packages.signerOf(packageName) ?: return false
        repository.approve(packageName, signer, label, atMs)
        return true
    }

    /** Withdraws [packageName]'s approval. It may ask again. */
    fun revoke(packageName: String) = repository.revoke(packageName)
}
