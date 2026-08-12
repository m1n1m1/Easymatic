package com.example.ottomatic.data.plugin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Everything either trust gate asks `PackageManager` — the plugin system's, and the
 * process API's.
 *
 * Separate from [PluginRegistry] because the two answer different questions — "what is
 * on this device?" against "what may contribute nodes, and what did it say?" — and
 * because keeping every platform read in one small class makes the trust-relevant ones
 * easy to find. There are exactly two that matter, and both are about *whose* identity
 * is being asked after: [signerOf], which is what makes an enable belong to a
 * developer rather than to a name; and [isGranted], which is asked of the plugin's
 * package and never of Ottomatic's.
 *
 * `ApiCallers` reuses [signerOf] rather than reimplementing it, which the name of this
 * class no longer quite predicts and which is deliberate: two hand-rolled digests over
 * a signing certificate would not throw when they disagreed, they would simply stop
 * matching — silently revoking every approval on the device.
 */
class PluginPackages(private val context: Context) {

    /**
     * Every app on the device exporting a plugin service.
     *
     * The package name comes from what `PackageManager` reports for the *resolved
     * service*, never from anything the plugin later says about itself. That is what
     * makes the typeId namespace a proof rather than a check.
     */
    fun discover(): List<DiscoveredPackage> {
        val manager = context.packageManager
        val resolved = runCatching { manager.queryIntentServices(Intent(PLUGIN_SERVICE_ACTION), 0) }
            .getOrDefault(emptyList())
        return resolved.mapNotNull { info ->
            val packageName = info.serviceInfo?.packageName
            // Ottomatic itself would resolve here if it ever exported one; an app
            // plugging into itself is not a shape worth supporting.
            if (packageName == null || packageName == context.packageName) return@mapNotNull null
            signerOf(packageName)?.let { signer ->
                DiscoveredPackage(
                    packageName = packageName,
                    label = runCatching { info.serviceInfo.applicationInfo.loadLabel(manager).toString() }
                        .getOrDefault(packageName),
                    signerSha256 = signer,
                    permissions = permissionsOf(packageName),
                )
            }
        }.distinctBy { it.packageName }
    }

    /**
     * The SHA-256 of [packageName]'s first signing certificate.
     *
     * A package *name* is not an identity: uninstalling frees it for anybody, so an
     * enable stored by name alone would silently transfer the user's decision to
     * whichever app claimed it next. This is what makes the decision belong to a
     * developer.
     */
    fun signerOf(packageName: String): String? = runCatching {
        val manager = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo
                ?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        signatures?.firstOrNull()?.toByteArray()?.let { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }.getOrNull()

    /**
     * What [packageName] calls itself, or the package name when it cannot be asked.
     *
     * For the consent screen and the App access screen, which are the two places a
     * package name alone is not enough to make a decision about. Never used for
     * anything a trust check depends on — a label is chosen by the app itself, so two
     * of them may legitimately be identical, which is exactly why [signerOf] exists.
     */
    fun labelOf(packageName: String): String = runCatching {
        val manager = context.packageManager
        manager.getApplicationInfo(packageName, 0).loadLabel(manager).toString()
    }.getOrDefault(packageName)

    /**
     * Whether **the plugin's** package holds [permission].
     *
     * Asked of the plugin's uid and never of Ottomatic's, which is the whole point: a
     * binder call runs in the plugin's process under the plugin's identity, so the
     * host's own grants are neither relevant nor lendable.
     */
    fun isGranted(permission: String, packageName: String): Boolean =
        context.packageManager.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED

    private fun permissionsOf(packageName: String): List<String> = runCatching {
        context.packageManager
            .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toList()
            .orEmpty()
    }.getOrDefault(emptyList())
}

/** One app on the device that exports a plugin service. */
data class DiscoveredPackage(
    val packageName: String,
    val label: String,
    val signerSha256: String,
    val permissions: List<String>,
)
