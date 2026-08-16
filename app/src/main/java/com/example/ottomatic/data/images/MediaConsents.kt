package com.example.ottomatic.data.images

import android.app.Activity
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * How Ottomatic obtains Android's permission to change a picture another app saved.
 *
 * **Four rungs, and which one applies is decided by the API level and by who owns the
 * row**, not by anything the user configured:
 *
 * - **Ottomatic's own pictures skip all of it.** A row this app inserted is writable and
 *   deletable with no consent on every version, so [request] checks the owner first. That
 *   is what keeps `action.image_edit` — whose output is always a new file this app
 *   created — clear of this whole mechanism.
 * - **API ≤ 28** — `WRITE_EXTERNAL_STORAGE` is an ordinary runtime permission and there
 *   is no consent step at all.
 * - **API 29** — the write is attempted and a `RecoverableSecurityException` carries the
 *   sender to show, so that rung runs *after* a failure rather than before it. See
 *   [senderFrom].
 * - **API 30+** — `createWriteRequest` / `createTrashRequest` / `createDeleteRequest`
 *   hand over a sender up front, for a batch of rows at once.
 * - **API 31+ holding `MANAGE_MEDIA`** — the same call, which the platform then
 *   auto-approves without drawing a dialog.
 *
 * **The sender needs an Activity, and the engine is a foreground service.**
 * `Context.startIntentSender` has no result callback, so there is no way to tell approval
 * from refusal — and those have to be reported differently. So a translucent trampoline is
 * started, exactly as a launcher shortcut starts one, and posts its answer back through
 * [complete].
 *
 * Starting *that* from the background is itself blocked from Android 10 unless
 * `SYSTEM_ALERT_WINDOW` is held — the fact `ForegroundLaunch` states for
 * `action.launch_app`, and the reason [canAsk] is checked **before** the call rather than
 * after: a refused background activity start throws nothing and returns nothing, it is
 * simply dropped with a line in Logcat.
 *
 * **One at a time**, on `OverlayPrompts`' reasoning: two macros deleting pictures at once
 * must queue rather than stack two system dialogs over each other.
 *
 * The activity is named as a **string constant** rather than a class reference, because
 * `data/` may not depend on `feature/` — `AndroidPermissionChecker`'s
 * `ACCESSIBILITY_SERVICE_CLASS` arrangement, with a test pinning the two together.
 */
@Suppress("TooManyFunctions") // The whole four-rung ladder plus its pending map.
object MediaConsents {

    /** What the user is being asked to allow. */
    enum class ConsentKind { WRITE, TRASH, DELETE }

    /** The answer, and the three outcomes that must not collapse into each other. */
    sealed interface Consent {
        /** Go ahead — either granted, or never needed in the first place. */
        data object Granted : Consent

        /** The user was asked and said no. Not worth retrying. */
        data object Refused : Consent

        /** Nobody could be asked. Worth retrying when the phone is in hand. */
        data class Unavailable(val reason: String) : Consent
    }

    const val CONSENT_ACTIVITY_CLASS: String =
        "com.example.ottomatic.feature.images.MediaConsentActivity"

    const val EXTRA_REQUEST_ID: String = "com.example.ottomatic.media.REQUEST_ID"

    private val gate = Mutex()
    private val pending = ConcurrentHashMap<String, PendingConsent>()
    private val counter = AtomicLong()

    private class PendingConsent(
        val sender: IntentSender,
        val resume: (Boolean) -> Unit,
    )

    /**
     * Asks for permission to [kind] the rows at [uris], if permission is needed at all.
     *
     * Answers [Consent.Granted] without showing anything when the app owns every row, when
     * the platform is old enough not to care, or when `MANAGE_MEDIA` makes the request
     * automatic.
     */
    @Suppress("ReturnCount") // One exit per rung of the ladder. A single one would
    // say only "no" where the whole point is which rung answered.
    suspend fun request(context: Context, uris: List<Uri>, kind: ConsentKind): Consent {
        if (uris.isEmpty()) return Consent.Granted
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Consent.Granted
        if (uris.all { ownsRow(context, it) }) return Consent.Granted
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // API 29 has no up-front request API at all: the caller attempts the write and
            // brings us whatever the platform threw. Nothing to ask here.
            return Consent.Unavailable(NEEDS_ATTEMPT)
        }
        val sender = runCatching { senderFor(context, uris, kind) }.getOrNull()
            ?: return Consent.Unavailable("Android would not say what it needs to change this picture")
        return ask(context, sender)
    }

    /**
     * Shows [sender] and waits for the answer.
     *
     * Public because API 29's path arrives here from a *failure* rather than from
     * [request]: the platform hands the sender back inside a `RecoverableSecurityException`
     * once the write has already been refused.
     */
    suspend fun ask(context: Context, sender: IntentSender): Consent = gate.withLock {
        if (!canAsk(context)) return@withLock Consent.Unavailable(NO_WAY_TO_ASK)
        val id = "consent-${counter.incrementAndGet()}"
        val approved = suspendCancellableCoroutine { continuation ->
            pending[id] = PendingConsent(sender) { granted ->
                if (continuation.isActive) continuation.resume(granted)
            }
            continuation.invokeOnCancellation { pending.remove(id) }
            val started = runCatching {
                context.startActivity(
                    Intent().apply {
                        setClassName(context.packageName, CONSENT_ACTIVITY_CLASS)
                        putExtra(EXTRA_REQUEST_ID, id)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    },
                )
            }.isSuccess
            if (!started) {
                pending.remove(id)
                if (continuation.isActive) continuation.resume(false)
            }
        }
        if (approved) Consent.Granted else Consent.Refused
    }

    /** Hands the trampoline the sender it was started for. */
    internal fun senderFor(requestId: String): IntentSender? = pending[requestId]?.sender

    /**
     * Reports what the user decided.
     *
     * The trampoline calls this from `onDestroy` as well as from its result callback, so a
     * swipe-away resolves the continuation as a refusal instead of hanging the run for
     * ever. [pending] is keyed and removed atomically so the second call does nothing.
     */
    internal fun complete(requestId: String, approved: Boolean) {
        pending.remove(requestId)?.resume?.invoke(approved)
    }

    /**
     * Whether an Activity started from here will actually appear.
     *
     * `AndroidSystemServices.canStartActivity`'s two documented exemptions, and its
     * caveat: this is a prediction rather than a guarantee, because the grant can be
     * revoked between here and the call and the platform reports that to nobody. Which is
     * why the message the node writes says Android *would not let* it rather than claiming
     * certainty.
     */
    private fun canAsk(context: Context): Boolean =
        Settings.canDrawOverlays(context) || isVisibleToUser()

    private fun isVisibleToUser(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /**
     * Whether this app inserted the row, and so may change it with no consent.
     *
     * Answers **false** when it cannot tell, which is the safe direction: a wrong `true`
     * skips the consent step and the write then fails with a bare `SecurityException`
     * carrying nothing the user could act on.
     */
    private fun ownsRow(context: Context, uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                cursor.getString(0) == context.packageName
            } ?: false
        }.getOrDefault(false)
    }

    private fun senderFor(context: Context, uris: List<Uri>, kind: ConsentKind): IntentSender {
        val resolver = context.contentResolver
        val pendingIntent: PendingIntent = when (kind) {
            ConsentKind.WRITE -> MediaStore.createWriteRequest(resolver, uris)
            ConsentKind.TRASH -> MediaStore.createTrashRequest(resolver, uris, true)
            ConsentKind.DELETE -> MediaStore.createDeleteRequest(resolver, uris)
        }
        return pendingIntent.intentSender
    }

    /**
     * Pulls the sender out of whatever API 29 threw, or null if it was not recoverable.
     *
     * `RecoverableSecurityException` exists only from API 29, and on 30+ the platform
     * throws a plain `SecurityException` instead — so this answering null on a newer phone
     * is correct rather than a gap: there the sender came from [request] up front.
     */
    @Suppress("ReturnCount") // Two guards then the answer.
    fun senderFrom(failure: Throwable): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val recoverable = failure as? android.app.RecoverableSecurityException ?: return null
        return recoverable.userAction.actionIntent.intentSender
    }

    /** A row id, for building the collection uri a request needs. */
    fun uriOf(collection: Uri, id: Long): Uri = ContentUris.withAppendedId(collection, id)

    private const val NO_WAY_TO_ASK =
        "Android needs you to confirm this change and there was no way to ask. " +
            "Grant \"Display over other apps\", or turn on media management in Settings."

    private const val NEEDS_ATTEMPT = "Android will ask about this change when it is attempted"

    /** What the trampoline reports back as, so [Activity.RESULT_OK] has one reading. */
    internal fun approvedFrom(resultCode: Int): Boolean = resultCode == Activity.RESULT_OK
}
