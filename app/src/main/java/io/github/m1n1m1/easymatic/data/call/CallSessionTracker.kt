package io.github.m1n1m1.easymatic.data.call

/** Where a call has got to. */
enum class CallPhase {
    RINGING,
    ACTIVE,
    ENDED,
}

/**
 * A call as one channel described it, before [CallSessionTracker] merges the channels.
 *
 * Deliberately plain data with no platform types in it, so the tracker that consumes it
 * stays a JVM-testable state machine — the split `NotificationMessages` and
 * `MessageDedup` already make, for the same reason: the decisions worth testing are here,
 * and everything that reads a `StatusBarNotification` needs a device.
 *
 * [connected] is what separates a call that is ringing from one somebody is talking on.
 * Neither channel reports it the same way — telephony says `OFFHOOK`, a notification says
 * its call type is ongoing — which is why it is normalised to a boolean here.
 */
data class ParsedCall(
    val packageName: String = "",
    val appName: String = "",
    val caller: String = "",
    val incoming: Boolean = true,
    val connected: Boolean = false,
    val video: Boolean = false,
)

/**
 * A phase change worth telling the engine about, ready to become a
 * `io.github.m1n1m1.easymatic.domain.model.items.CallEvent`.
 *
 * [answered] and [durationSeconds] are filled only on [CallPhase.ENDED]; before that
 * there is no answer to either question that is not a guess.
 */
data class CallTransition(
    val phase: CallPhase,
    val packageName: String,
    val appName: String,
    val caller: String,
    val incoming: Boolean,
    val video: Boolean,
    val answered: Boolean,
    val durationSeconds: Int,
    val atMs: Long,
)

/** The call going on right now, as the value nodes read it. */
data class CallSnapshot(
    val phase: CallPhase,
    val packageName: String,
    val appName: String,
    val caller: String,
    val incoming: Boolean,
    val video: Boolean,
)

/**
 * The one thing in the process that knows a call is happening, merging the two channels
 * Android offers into a single session per call.
 *
 * **Telephony** (`android.intent.action.PHONE_STATE`) is authoritative for the cellular
 * radio and reports nothing else. **Notifications** are the only channel that exists for
 * Teams, WhatsApp, Discord and everything like them — and they also carry what telephony
 * will not give up below `READ_CALL_LOG`, namely who is calling, because the phone's own
 * dialer posts a call notification like any other app.
 *
 * Merging them is the whole job, and the rule is one sentence: a call notification from
 * the phone's current dialer joins the open cellular session instead of starting one of
 * its own. Without that a single incoming phone call fires every call trigger twice.
 * Which app is the dialer is asked of the platform per event rather than kept in a list
 * here, so nothing in this file names a vendor.
 *
 * The degraded paths both work and neither needs a special case. With notification access
 * off, telephony alone drives the session and [ParsedCall.caller] stays empty. With
 * `READ_PHONE_STATE` denied no broadcast arrives at all, so the dialer's notification
 * finds no session to join and opens one — a cellular call then behaves exactly like an
 * app call, which is the honest best available.
 *
 * Every transition is published **only on an actual phase change**, which is what makes
 * the merge safe: a channel repeating itself, or a second channel describing a call the
 * first one already opened, produces nothing. A messenger re-posting its notification on
 * every timer tick is the common case, not the rare one.
 */
@Suppress("TooManyFunctions") // Two channels in, one read out, and one private step per phase.
class CallSessionTracker(private val capacity: Int = MAX_TRACKED) {

    private val lock = Any()

    /** Insertion-ordered, so the oldest call is the one evicted. */
    private val sessions = LinkedHashMap<String, Session>()

    private class Session(
        var call: ParsedCall,
        var answeredAtMs: Long? = null,
        var published: CallPhase = CallPhase.RINGING,
    )

    /**
     * A telephony state change: `"ringing"`, `"offhook"` or `"idle"`, in any case.
     *
     * Case is normalised here because the broadcast spells its states in capitals and
     * every other reading of them in this app is lower case.
     *
     * [dialerPackage] and [dialerAppName] name whichever app this phone currently treats
     * as its dialer, so a cellular session is identified even when notification access is
     * off and nothing will ever fill it in.
     */
    fun onTelephony(
        state: String,
        nowMs: Long,
        dialerPackage: String = "",
        dialerAppName: String = "",
    ): CallTransition? = synchronized(lock) {
        val identity = ParsedCall(packageName = dialerPackage, appName = dialerAppName)
        when (state.trim().lowercase()) {
            STATE_RINGING -> ring(TELEPHONY_KEY, identity, nowMs, dialerPackage)
            STATE_OFFHOOK -> connect(TELEPHONY_KEY, identity, nowMs, dialerPackage)
            STATE_IDLE -> end(TELEPHONY_KEY, nowMs)
            else -> null
        }
    }

    /**
     * A call notification posted or updated, keyed by its `StatusBarNotification.key`.
     *
     * Joins the cellular session when [call] comes from [dialerPackage] and one is open;
     * otherwise it is a call in its own right.
     */
    fun onCallNotification(
        key: String,
        call: ParsedCall,
        nowMs: Long,
        dialerPackage: String = "",
    ): CallTransition? = synchronized(lock) {
        val joinsCellular = call.packageName == dialerPackage && sessions.containsKey(TELEPHONY_KEY)
        val target = if (joinsCellular) TELEPHONY_KEY else key
        if (call.connected) {
            connect(target, call, nowMs, dialerPackage)
        } else {
            ring(target, call, nowMs, dialerPackage)
        }
    }

    /**
     * A notification gone, which for a call notification is the call ending.
     *
     * Answers null for every other notification, and for a cellular call whose session
     * telephony has already closed — so the dialer's notification disappearing after
     * `IDLE` does not report the same call ending twice.
     *
     * The honest limit, and it belongs in the node's prose too: a notification going away
     * is the call *UI* going away. That is the call ending in every ordinary case, and it
     * is not the same statement.
     */
    fun onNotificationGone(key: String, nowMs: Long): CallTransition? =
        synchronized(lock) { end(key, nowMs) }

    /**
     * Drops the app calls when notification access goes away.
     *
     * Their notifications can never be seen to disappear now, so nothing would ever close
     * them and `value.call_active` would answer true forever. The cellular session stays:
     * telephony is a separate grant and still reports its own end.
     */
    fun forgetAppCalls() = synchronized(lock) {
        sessions.keys.retainAll { it == TELEPHONY_KEY }
        Unit
    }

    /** The call in progress, preferring a connected one over one still ringing. */
    fun current(): CallSnapshot? = synchronized(lock) {
        val session = sessions.values.lastOrNull { it.published == CallPhase.ACTIVE }
            ?: sessions.values.lastOrNull()
            ?: return null
        CallSnapshot(
            phase = session.published,
            packageName = session.call.packageName,
            appName = session.call.appName,
            caller = session.call.caller,
            incoming = session.call.incoming,
            video = session.call.video,
        )
    }

    internal fun size(): Int = synchronized(lock) { sessions.size }

    /**
     * A call announced. Publishes nothing when this call was already known, which is what
     * absorbs both a repeated notification and the second channel's account of a call the
     * first one already opened.
     */
    // A guard chain: already known, adopted, or new, and folding the three loses which
    // of them happened — which is the whole of why nothing is published twice.
    @Suppress("ReturnCount")
    private fun ring(
        key: String,
        call: ParsedCall,
        nowMs: Long,
        dialerPackage: String,
    ): CallTransition? {
        sessions[key]?.let {
            it.absorb(call)
            return null
        }
        adopt(key, dialerPackage)?.let {
            it.absorb(call)
            sessions[key] = it
            return null
        }
        val session = put(key, Session(call, published = CallPhase.RINGING))
        return session.transition(CallPhase.RINGING, nowMs)
    }

    /** A call connected. Publishes nothing when it was already known to be connected. */
    private fun connect(
        key: String,
        call: ParsedCall,
        nowMs: Long,
        dialerPackage: String,
    ): CallTransition? {
        val session = sessions[key]
            ?: adopt(key, dialerPackage)?.also { sessions[key] = it }
            // A connect with no ring before it is a call this phone placed. That is the
            // only signal either channel gives for direction, and it is reliable.
            ?: put(key, Session(call.copy(incoming = false)))
        session.absorb(call)
        if (session.published == CallPhase.ACTIVE) return null
        session.published = CallPhase.ACTIVE
        session.answeredAtMs = nowMs
        return session.transition(CallPhase.ACTIVE, nowMs)
    }

    /** A call over, or null when [key] names nothing this tracker was following. */
    private fun end(key: String, nowMs: Long): CallTransition? {
        val session = sessions.remove(key) ?: return null
        return session.transition(CallPhase.ENDED, nowMs)
    }

    /**
     * The session an app call from [dialerPackage] had already opened for this same call,
     * removed so the caller can re-key it.
     *
     * This is the race where the dialer's notification arrives before the broadcast.
     * Adopting rather than opening is what keeps one ringing phone from being reported
     * twice, and it carries the caller's name across.
     */
    @Suppress("ReturnCount") // Three ways there is nothing to adopt, and each is a different one.
    private fun adopt(key: String, dialerPackage: String): Session? {
        if (key != TELEPHONY_KEY || dialerPackage.isEmpty()) return null
        val existing = sessions.entries
            .firstOrNull { it.key != TELEPHONY_KEY && it.value.call.packageName == dialerPackage }
            ?.key
            ?: return null
        return sessions.remove(existing)
    }

    private fun put(key: String, session: Session): Session {
        sessions[key] = session
        while (sessions.size > capacity) sessions.remove(sessions.keys.first())
        return session
    }

    /** Fills in whatever this channel knew and the session did not. */
    private fun Session.absorb(call: ParsedCall) {
        this.call = this.call.copy(
            packageName = call.packageName.ifEmpty { this.call.packageName },
            appName = call.appName.ifEmpty { this.call.appName },
            caller = call.caller.ifEmpty { this.call.caller },
            // Direction is settled when the session opens and never revisited: a later
            // notification saying only "ongoing" carries no direction, and must not
            // overwrite what the ring-before-connect test established.
            incoming = this.call.incoming,
            video = this.call.video || call.video,
        )
    }

    private fun Session.transition(phase: CallPhase, nowMs: Long) = CallTransition(
        phase = phase,
        packageName = call.packageName,
        appName = call.appName,
        caller = call.caller,
        incoming = call.incoming,
        video = call.video,
        answered = answeredAtMs != null,
        durationSeconds = answeredAtMs
            ?.let { ((nowMs - it) / MILLIS_PER_SECOND).toInt().coerceAtLeast(0) }
            ?: 0,
        atMs = nowMs,
    )

    private companion object {
        const val MAX_TRACKED = 16
        const val MILLIS_PER_SECOND = 1000L

        /**
         * The cellular radio's one session. A notification key can never collide with it:
         * one is formatted `0|com.android.dialer|1|null|10123` and always contains a `|`.
         */
        const val TELEPHONY_KEY = "telephony"

        const val STATE_RINGING = "ringing"
        const val STATE_OFFHOOK = "offhook"
        const val STATE_IDLE = "idle"
    }
}
