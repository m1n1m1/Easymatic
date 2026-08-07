package com.example.ottomatic.data.permissions

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.ottomatic.core.permissions.Permission
import com.example.ottomatic.core.permissions.PermissionChecker
import com.example.ottomatic.core.permissions.PermissionStatus
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType

/**
 * Android implementation of [PermissionChecker]. When constructed with an
 * [Activity] it can populate [PermissionStatus.Denied.showRationale] via
 * [ActivityCompat.shouldShowRequestPermissionRationale]; with a bare [Context]
 * (e.g. application context) the rationale flag is always false.
 *
 * Special permissions that are not granted through the standard runtime dialog
 * are handled here: `ACCESS_NOTIFICATION_POLICY` is checked via
 * [NotificationManager.isNotificationPolicyAccessGranted] instead of
 * [ContextCompat.checkSelfPermission].
 */
class AndroidPermissionChecker(
    private val context: Context,
    private val activity: Activity? = null,
) : PermissionChecker {

    override fun status(permission: Permission): PermissionStatus = when {
        permission == Permissions.ACCESS_NOTIFICATION_POLICY -> dndPolicyStatus()
        !permission.existsOnThisApi() -> PermissionStatus.Granted
        ContextCompat.checkSelfPermission(context, permission.manifest) ==
            PackageManager.PERMISSION_GRANTED -> PermissionStatus.Granted
        else -> PermissionStatus.Denied(
            showRationale = activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, permission.manifest)
            } ?: false,
        )
    }

    /**
     * Each of these has its own system API — there is no `android.permission.*`
     * name to check — so they are answered here rather than through [status].
     */
    override fun isPrerequisiteSatisfied(type: PrerequisiteType): Boolean = when (type) {
        PrerequisiteType.RUNTIME -> false
        PrerequisiteType.NOTIFICATION_POLICY -> dndPolicyStatus() is PermissionStatus.Granted
        PrerequisiteType.NOTIFICATION_LISTENER ->
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        PrerequisiteType.ACCESSIBILITY_SERVICE -> isAccessibilityServiceEnabled()
        PrerequisiteType.OVERLAY -> Settings.canDrawOverlays(context)
        PrerequisiteType.BATTERY_OPTIMISATION -> isIgnoringBatteryOptimizations()
        // Below API 31 an exact alarm needs no permission at all, so there is
        // nothing to grant and "satisfied" is the truth rather than a guess.
        PrerequisiteType.EXACT_ALARM ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager().canScheduleExactAlarms()
        PrerequisiteType.WRITE_SETTINGS -> Settings.System.canWrite(context)
        // A phone with no NFC chip answers *satisfied*, for the same reason
        // `existsOnThisApi` reports granted for a permission the platform has
        // never heard of: there is nothing here to grant. This screen's question
        // is "what does the app need and what has it got?", and "go and switch on
        // hardware you do not have" is not an answer to it — it is a row that can
        // never go green, on a page whose whole job is telling you what to fix.
        // The node's question is a different one, and `trigger.nfc` answers that
        // precisely, in its own console, where the person who placed it will see.
        PrerequisiteType.NFC -> !hasNfcHardware() || isNfcEnabled()
        // Neither is declared by any node; reporting them unsatisfied keeps the
        // safe default rather than claiming something unverified is working.
        PrerequisiteType.FOREGROUND_SERVICE, PrerequisiteType.DEVICE_ADMIN -> false
    }

    private fun hasNfcHardware(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)

    private fun isNfcEnabled(): Boolean =
        NfcAdapter.getDefaultAdapter(context)?.isEnabled == true

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun alarmManager(): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Whether *our* accessibility service is among the enabled ones.
     *
     * The enabled list is a colon-separated set of flattened component names, so
     * it is parsed rather than substring-matched: a `contains(packageName)` test
     * would also match a different app whose package name merely starts with
     * ours. The master switch is checked too, because a service can remain listed
     * while accessibility as a whole is off.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val resolver = context.contentResolver
        val masterSwitch = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        if (masterSwitch != 1) return false
        val enabled = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val ours = ComponentName(context.packageName, ACCESSIBILITY_SERVICE_CLASS)
        return enabled.split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it == ours }
    }

    private fun dndPolicyStatus(): PermissionStatus {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        return if (notificationManager.isNotificationPolicyAccessGranted) {
            PermissionStatus.Granted
        } else {
            PermissionStatus.Denied(showRationale = false)
        }
    }

    /**
     * Whether this permission is a thing the platform knows about at all on the
     * version we are running.
     *
     * `checkSelfPermission` answers DENIED for a name the platform has never
     * heard of, which is indistinguishable from a refusal — so on API 30 a
     * `BLUETOOTH_CONNECT` check reads as "the user said no" when in fact the
     * install-time `BLUETOOTH` is what applies and everything works. Reporting
     * *granted* is the honest answer: there is nothing here to grant. Both
     * callers that would otherwise be misled — the permissions screen and the
     * node card — are about telling the user what to fix.
     */
    private fun Permission.existsOnThisApi(): Boolean = when (this) {
        Permissions.POST_NOTIFICATIONS -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        Permissions.BLUETOOTH_CONNECT -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        else -> true
    }

    companion object {
        /**
         * Fully-qualified name of the app's accessibility service.
         *
         * Named rather than referenced so that `core`/`data` permission checking
         * does not depend on the service class itself;
         * `AccessibilityServiceNameTest` asserts the two stay in step.
         */
        const val ACCESSIBILITY_SERVICE_CLASS =
            "com.example.ottomatic.data.accessibility.OttomaticAccessibilityService"
    }
}
