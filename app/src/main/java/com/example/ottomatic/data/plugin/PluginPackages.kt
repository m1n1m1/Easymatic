package com.example.ottomatic.data.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.example.ottomatic.plugin.PLUGIN_CHOICE_ACTION
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

    /**
     * [packageName]'s own settings screen, or null when it exports none.
     *
     * The way in for an account. Withholding `@ApiToken` and the credential libraries
     * from plugins is right — those are a host trust boundary — and it left a plugin's
     * own sign-in unreachable from Ottomatic, which is not the same thing as refusing it.
     * A plugin that needs a login declares an Activity under [PLUGIN_SETTINGS_ACTION] and
     * Ottomatic offers a button to it.
     *
     * **Resolved here, from `PackageManager`, and never from anything the plugin sent.**
     * That is the same rule that keeps `Uri`, `PendingIntent` and `IBinder` out of
     * `nodeapi/wire` entirely: a component name arriving over the wire would be a
     * capability the host would then exercise on the plugin's behalf. Constrained to
     * [packageName] as well as to the action, so a second app answering the same action
     * cannot be launched in this one's name.
     */
    fun settingsComponentOf(packageName: String): ComponentName? = runCatching {
        context.packageManager
            .queryIntentActivities(Intent(PLUGIN_SETTINGS_ACTION).setPackage(packageName), 0)
            .firstNotNullOfOrNull { info ->
                info.activityInfo
                    ?.takeIf { it.exported && it.packageName == packageName }
                    ?.let { ComponentName(it.packageName, it.name) }
            }
    }.getOrNull()

    /**
     * The class name of [packageName]'s own chooser Activity, or null when it exports none.
     *
     * What a `@PluginChoice(chooser = SCREEN)` field opens. Resolved here, from
     * `PackageManager`, and constrained to [packageName] as well as to the action — the
     * same rule [settingsComponentOf] follows, for the same reason: a component name is a
     * thing to launch, so it may never come from anything the plugin sent.
     *
     * A **class name rather than a `ComponentName`** because the answer is stored on
     * `PluginNodeEntry`, which lives in `domain/` and may hold nothing of Android's. The
     * package is already on the entry, so `feature/` assembles the two.
     */
    fun chooserActivityOf(packageName: String): String? = runCatching {
        context.packageManager
            .queryIntentActivities(Intent(PLUGIN_CHOICE_ACTION).setPackage(packageName), 0)
            .firstNotNullOfOrNull { info ->
                info.activityInfo
                    ?.takeIf { it.exported && it.packageName == packageName }
                    ?.name
            }
    }.getOrNull()

    /**
     * Opens [packageName]'s settings screen, answering false when there is none to open.
     *
     * Started **by component** rather than by action, so the app that gets launched is
     * the one the row the user tapped is about — resolving an action at launch time could
     * pick a different answerer than the one [settingsComponentOf] reported.
     */
    fun openSettings(packageName: String): Boolean {
        val component = settingsComponentOf(packageName) ?: return false
        val intent = Intent()
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

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
