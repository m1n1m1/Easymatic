package io.github.m1n1m1.easymatic.engine.service

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.core.os.bundleOf
import io.github.m1n1m1.easymatic.ServiceLocator
import io.github.m1n1m1.easymatic.domain.model.ApiContract
import io.github.m1n1m1.easymatic.domain.model.ApiTriggerListWire
import io.github.m1n1m1.easymatic.engine.api.ApiRateLimiter
import io.github.m1n1m1.easymatic.engine.api.ApiInputs
import io.github.m1n1m1.easymatic.engine.api.ApiTriggerTarget
import io.github.m1n1m1.easymatic.engine.api.findApiTrigger
import io.github.m1n1m1.easymatic.engine.api.listApiTriggers
import io.github.m1n1m1.easymatic.engine.api.ApiRun
import io.github.m1n1m1.easymatic.feature.api.ApiConsentActivity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * The process API's app-facing door: `contentResolver.call()`.
 *
 * A `ContentProvider` rather than a bound service with an AIDL, and the reasoning is
 * worth keeping because it looks like the less obvious choice. What an app calling in
 * needs is a **result** and, from our side, a **verified identity**; a plain `Intent`
 * gives neither, and an AIDL gives both at the cost of an `.aidl` the caller has to
 * ship, a binding to manage and a `DeadObjectException` to handle. `call()` gives both
 * for one line at the call site and no dependency on us at all:
 *
 * ```
 * val out = contentResolver.call("content://io.github.m1n1m1.easymatic.triggers".toUri(),
 *                                "run", null, bundleOf("token" to token, "in.city" to "Vienna"))
 * ```
 *
 * The one thing it cannot reach is a caller that is not an Android app — a shell
 * script, Tasker, Automate — which is why [ApiTriggerReceiver] exists beside it rather
 * than instead of it.
 *
 * ## The check here *is* the gate
 *
 * A provider's `android:readPermission` and `android:writePermission` **do not apply
 * to `call()`** — `ContentProvider.Transport.call` performs no permission enforcement
 * at all. So the manifest cannot guard this and the code must, which is why every
 * branch below starts by establishing who is asking. There is no custom permission
 * either, for the reason the manifest already records about `BIND_PLUGIN`: at
 * `normal` it is granted at install to anyone who asks for it and proves nothing.
 *
 * The real gate is the user's approval, exactly as the Plugins screen is for plugins,
 * with the signing certificate pinned at approval time and re-checked on every call
 * (`ApiCallers.isApproved`).
 *
 * ## It answers when the run *starts*
 *
 * Never when it finishes. A macro may hold an `action.delay` or an
 * `action.wait_until`, and a binder call may not sit on either — the caller's thread
 * is blocked and the binder pool is finite.
 */
class ApiTriggerProvider : ContentProvider() {

    private val limiter = ApiRateLimiter()
    private val json = Json { encodeDefaults = true }

    override fun onCreate(): Boolean = true

    @Suppress("ReturnCount") // A door is a sequence of refusals; each one is its own sentence.
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // Null only when the caller reached us without an identity the system will
        // vouch for, which no ordinary `contentResolver.call` can do.
        val caller = callingPackage ?: return refuse(ApiContract.STATUS_INVALID, "No calling package")
        if (!limiter.allow(caller)) {
            return refuse(ApiContract.STATUS_RATE_LIMITED, "Too many calls; try again shortly")
        }
        return when (method) {
            // Deliberately outside the approval check: asking what this app can do is
            // not itself a capability, and needing approval to discover whether
            // approval is worth requesting would be a loop.
            ApiContract.METHOD_VERSION -> ok(bundleOf(ApiContract.KEY_VERSION to ApiContract.VERSION))
            ApiContract.METHOD_LIST -> list(caller)
            ApiContract.METHOD_RUN -> run(caller, arg, extras)
            else -> refuse(ApiContract.STATUS_INVALID, "Unknown method '$method'")
        }
    }

    /**
     * Every callable trigger, for an approved caller only.
     *
     * A token cannot unlock this, and that is a design decision rather than an
     * oversight: a token authorises *one* trigger where this describes all of them,
     * and a caller that has no token yet is precisely the audience this method exists
     * for. Enumerating the user's macro names is a real disclosure even without the
     * ability to run them, so it sits behind the same approval the running does.
     */
    private fun list(caller: String): Bundle {
        if (!ServiceLocator.apiCallers.isApproved(caller)) return needsApprovalBundle(context)
        val payload = runBlocking { listApiTriggers(ServiceLocator.workflowRepository) }
        return ok(
            bundleOf(
                ApiContract.KEY_PAYLOAD to json.encodeToString(ApiTriggerListWire.serializer(), payload),
            ),
        )
    }

    @Suppress("ReturnCount") // As [call]: each refusal is a distinct answer.
    private fun run(caller: String, macroId: String?, extras: Bundle?): Bundle {
        val id = macroId?.takeIf { it.isNotBlank() }
            ?: extras?.getString(ApiContract.EXTRA_MACRO_ID)?.takeIf { it.isNotBlank() }
        val nodeId = extras?.getString(ApiContract.EXTRA_NODE_ID)
        val token = extras?.getString(ApiContract.EXTRA_TOKEN)
        val isApproved = ServiceLocator.apiCallers.isApproved(caller)
        // Approval is checked before resolving IDs, so callers cannot probe the macro list.
        if (!isApproved && token.isNullOrBlank()) return needsApprovalBundle(context)
        if (id == null && nodeId.isNullOrBlank() && token.isNullOrBlank()) {
            return refuse(ApiContract.STATUS_INVALID, "Supply nodeId or token")
        }
        val target = runBlocking {
            findApiTrigger(ServiceLocator.workflowRepository, id, nodeId, token)
        } ?: return if (isApproved) {
            refuse(ApiContract.STATUS_NOT_FOUND, "No unique matching API trigger")
        } else {
            refuse(ApiContract.STATUS_DENIED, "Not allowed")
        }
        if (!target.workflow.enabled) {
            return refuse(ApiContract.STATUS_DISABLED, "'${target.workflow.name}' is switched off")
        }
        start(target, extras, caller)
        return ok(bundleOf(ApiContract.KEY_STATUS to ApiContract.STATUS_STARTED))
    }

    private fun start(target: ApiTriggerTarget, extras: Bundle?, caller: String) {
        val context = context ?: return
        ApiRun.start(context, target, ApiInputs.read(inputsFrom(extras), target.specs), caller)
    }

    // A provider with no table behind it. Everything below is the unimplemented half
    // of the base class rather than a surface anyone should reach — `call()` is the
    // entire API, and a `query` that answered anything would be a second one to keep
    // in step with it.
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}

/**
 * The refusal that tells a caller how to fix itself.
 *
 * The `Intent` is plain rather than a `PendingIntent` on purpose: a `PendingIntent`
 * is launched under *our* identity, so the consent screen would see no calling
 * package and could not name the app the user is being asked to judge — which is the
 * entire content of the question.
 */
private fun needsApprovalBundle(context: Context?): Bundle = bundleOf(
    ApiContract.KEY_OK to false,
    ApiContract.KEY_STATUS to ApiContract.STATUS_NEEDS_APPROVAL,
    ApiContract.KEY_MESSAGE to "Ask the user to allow this app; start the intent with startActivityForResult",
    ApiContract.KEY_APPROVAL_INTENT to context?.let { Intent(it, ApiConsentActivity::class.java) },
)

private fun ok(extras: Bundle): Bundle = extras.apply {
    putBoolean(ApiContract.KEY_OK, true)
    if (!containsKey(ApiContract.KEY_STATUS)) putString(ApiContract.KEY_STATUS, ApiContract.STATUS_STARTED)
}

private fun refuse(status: String, message: String): Bundle = bundleOf(
    ApiContract.KEY_OK to false,
    ApiContract.KEY_STATUS to status,
    ApiContract.KEY_MESSAGE to message,
)

/**
 * The `in.*` extras, flattened to the shape [ApiInputs] reads.
 *
 * `toString()` rather than `getString`, so a caller that put an `Int` in the bundle
 * is served as readily as one that put text — which matters because a shell script
 * can only send strings while a Kotlin caller will naturally use `bundleOf("count" to
 * 3)`. Both end up on the port's declared type through the same total conversion.
 */
internal fun inputsFrom(extras: Bundle?): Map<String, String> {
    val bundle = extras ?: return emptyMap()
    return bundle.keySet()
        .filter { it.startsWith(ApiContract.INPUT_PREFIX) }
        .mapNotNull { key ->
            @Suppress("DEPRECATION") // The typed overload needs a class we deliberately do not fix.
            val value = bundle.get(key) ?: return@mapNotNull null
            key.removePrefix(ApiContract.INPUT_PREFIX) to value.toString()
        }
        .toMap()
}
