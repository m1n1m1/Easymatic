package io.github.m1n1m1.easymatic.data.trigger

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.trigger.TriggerBus
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource

/**
 * The device administrator behind `trigger.login_failed`.
 *
 * `onPasswordFailed` is the only thing on Android that reports a *failed* unlock.
 * There is no broadcast for it, the lockscreen is not in any app's window tree,
 * and this app's accessibility service deliberately subscribes to no accessibility
 * events at all — so a device admin holding `watch-login` is the whole mechanism.
 *
 * Payload contract:
 * - `attempts` — consecutive failed attempts, or `-1` when the count is unreadable
 * - `timestamp` — epoch ms
 *
 * Only the two-argument [onPasswordFailed] is overridden. From API 31 the platform
 * calls the `UserHandle` overload instead, but its default implementation delegates
 * here, so there is nothing to gate on `Build.VERSION` at minSdk 26 — and the extra
 * argument names the user a failure happened on, which a single-user phone has
 * nothing to do with.
 */
class LoginAdminReceiver : DeviceAdminReceiver() {

    override fun onPasswordFailed(context: Context, intent: Intent) {
        TriggerBus.emitOrHoldBroadcast(
            TriggerEvent(
                source = TriggerSource.SECURITY,
                triggerNodeId = NodeId.BROADCAST,
                payload = mapOf(
                    KEY_ATTEMPTS to failedAttempts(context).toString(),
                    KEY_TIMESTAMP to System.currentTimeMillis().toString(),
                ),
            ),
        )
    }

    /**
     * Says what is lost, because Android's own confirmation does not: it asks
     * whether to deactivate an administrator, which says nothing about which
     * macros stop working.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.device_admin_disable_warning)

    /**
     * The consecutive failed-unlock count, or **-1** when it cannot be read.
     *
     * The house "unknown is -1, never 0" rule, and it matters more here than
     * usual: the read carries a conditional `MANAGE_DEVICE_ADMINS` requirement and
     * throws for an admin without `watch-login`, so a zero would look like a
     * successful unlock. `trigger.login_failed` is what decides that an unknown
     * count still fires at the default threshold.
     */
    private fun failedAttempts(context: Context): Int = runCatching {
        val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        manager.currentFailedPasswordAttempts
    }.getOrDefault(UNKNOWN_ATTEMPTS)

    private companion object {
        const val KEY_ATTEMPTS = "attempts"
        const val KEY_TIMESTAMP = "timestamp"
        const val UNKNOWN_ATTEMPTS = -1
    }
}
