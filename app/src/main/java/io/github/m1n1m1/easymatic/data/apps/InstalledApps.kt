package io.github.m1n1m1.easymatic.data.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One installed app, as a chooser shows it. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    /** Whether it has a launcher activity, i.e. whether `action.launch_app` could open it. */
    val launchable: Boolean,
)

/**
 * Every app on the device, cached for the process.
 *
 * Enumerating packages is slow — hundreds of rows, each with a label to resolve
 * through the resource system — so it happens once on an IO dispatcher behind a
 * mutex, the shape `WebViewScriptEngine` already uses for a lazily built resource
 * whose construction must not race.
 *
 * A cache rather than a ViewModel published through a `CompositionLocal`, which is
 * what the place and variable libraries get. Those locals exist so a picker can
 * *edit* its library and see its own in-flight edits; nothing in this app edits the
 * app list, so there is nothing to observe. What does change it — an install or an
 * uninstall — already broadcasts, and [PackageReceiver][io.github.m1n1m1.easymatic.data.trigger.PackageReceiver]
 * calls [invalidate] on the way past.
 *
 * Icons are deliberately not loaded here: a few hundred drawables decoded up front
 * is the difference between a list that appears and one that stalls. The picker
 * loads each row's icon as that row is composed.
 */
object InstalledApps {

    private val mutex = Mutex()

    @Volatile
    private var cache: List<InstalledApp>? = null

    /** Every installed app, sorted by label. Loads on first call, then returns the cache. */
    suspend fun all(context: Context): List<InstalledApp> {
        cache?.let { return it }
        return mutex.withLock {
            cache ?: load(context.applicationContext).also { cache = it }
        }
    }

    /** Drops the cache, so the next [all] re-reads. Called when a package is added or removed. */
    fun invalidate() {
        cache = null
    }

    private suspend fun load(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val manager = context.packageManager
        // Two queries rather than one: `getInstalledApplications` is the only way to
        // reach a package with no launcher activity — which is most of what posts
        // notifications, and therefore most of what a notification filter is for —
        // while only a launcher query can say which of them can actually be opened.
        val launchable = runCatching {
            manager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0,
            ).mapTo(mutableSetOf()) { it.activityInfo.packageName }
        }.getOrDefault(emptySet())

        runCatching {
            manager.getInstalledApplications(PackageManager.GET_META_DATA).map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = runCatching { info.loadLabel(manager).toString() }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: info.packageName,
                    launchable = info.packageName in launchable,
                )
            }
        }.getOrDefault(emptyList()).sortedBy { it.label.lowercase() }
    }
}

/**
 * What to call the app [packageName]: its label, or the package itself when it is
 * not installed.
 *
 * Resolving one package is a single lookup rather than an enumeration, so a field
 * showing a chosen app never has to wait for [InstalledApps.all]. Falling back to
 * the raw package follows the rule the geofence picker keeps — an empty field reads
 * as unconfigured when the truth is "pointing at something that is gone" — and here
 * the raw value is legible anyway.
 */
fun appLabel(context: Context, packageName: String): String {
    if (packageName.isBlank()) return ""
    val manager = context.packageManager
    return runCatching {
        manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: packageName
}
