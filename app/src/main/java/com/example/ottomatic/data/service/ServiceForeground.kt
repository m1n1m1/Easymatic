package com.example.ottomatic.data.service

import android.content.pm.ServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The engine service's foreground-service types, borrowed for the length of one operation.
 *
 * ### Why anything is needed
 *
 * From Android 11 a foreground service's access to the camera and the microphone follows its
 * declared *foreground service type*, not the app's grant: a `specialUse` service can hold
 * `CAMERA` or `RECORD_AUDIO` and still be refused the hardware. So while a photo is being
 * taken the type has to say `camera`, and while a recording is running it has to say
 * `microphone` — otherwise the recording succeeds and contains silence, which is the worse
 * failure of the two because nothing anywhere reports it.
 *
 * ### Why they cannot simply be claimed permanently
 *
 * From API 34 `startForeground` **throws** when a named type's permission is not held. A
 * service that always claimed both would therefore fail to start — taking every armed macro
 * down with it — on every phone whose owner granted neither, for the sake of a handful of
 * nodes out of a hundred. A claim has to be made only when it can be honoured.
 *
 * ### Why one holder rather than one per type
 *
 * Both types are conferred by the same `startForeground` call, so two objects each calling
 * it with their own idea of the mask would silently drop the other's claim: taking a photo
 * during a recording would end the microphone type and leave the rest of the recording
 * silent. Claims are therefore **counted** here and applied as a set, which also makes two
 * overlapping recordings — or a capture inside one — cost one platform call each way.
 *
 * ### Why a registered callback rather than a service handle
 *
 * `data` may not reach `engine`, where the service lives. `OttomaticAccessibilityService`
 * can publish an instance because `ScreenCapture` sits in the same package; here the two
 * halves are on opposite sides of a dependency rule, so the service hands *in* the one thing
 * it can do rather than handing out itself.
 *
 * ### What it promises
 *
 * Almost nothing, and deliberately. [withCamera] and [withMicrophone] promote if they can,
 * always run the block, and always revert. A promotion that fails — no service running, as
 * in an editor preview run; the permission not granted; a platform policy refusal — is
 * **not** an error: the operation is attempted anyway, because a service started while the
 * app was in the foreground may well already have the access, and the hardware itself then
 * reports any refusal in a sentence a person can act on. [ForegroundGrant]'s stance exactly:
 * stop the app refusing before the platform gets a chance to accept.
 */
internal object ServiceForeground {

    /**
     * A borrowable foreground-service type.
     *
     * Carries its own platform constant so the mask is built in one place. The names are
     * this app's rather than the platform's because the set is what the *claim* is about;
     * `MacroEngineService` still decides whether a claim can be honoured, since the
     * permission check and the `startForeground` call both live there.
     */
    enum class Kind(val serviceType: Int) {
        CAMERA(ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA),
        MICROPHONE(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE),
    }

    /**
     * What the engine service can do about its own foreground types.
     *
     * Answers which of [claimed] is held *after* the call, so a refusal and a success are
     * told apart by the one thing that knows. A type the service could not honour simply
     * does not come back, and nothing here treats that as an error.
     */
    fun interface Types {
        fun setTypes(claimed: Set<Kind>): Set<Kind>
    }

    @Volatile
    private var host: Types? = null

    private val lock = Any()

    /** Serialises [apply], so two overlapping claims cannot leave a stale set applied. */
    private val applyLock = Mutex()

    /** How many operations currently want each type. */
    private val claims = mutableMapOf<Kind, Int>()

    /** What the service last told us it holds, so an unchanged set costs no platform call. */
    @Volatile
    private var applied: Set<Kind> = emptySet()

    /** Registered by `MacroEngineService.onCreate`. */
    fun attach(types: Types) {
        host = types
        applied = emptySet()
    }

    /**
     * Cleared by `MacroEngineService.onDestroy`, comparing **identity**: a service that has
     * already been recreated must not have its successor's registration cleared by the
     * teardown of the one it replaced.
     */
    fun detach(types: Types) {
        if (host === types) {
            host = null
            applied = emptySet()
        }
    }

    /** Runs [block] with the engine promoted to the camera type where that is possible. */
    suspend fun <T> withCamera(block: suspend () -> T): T = withType(Kind.CAMERA, block)

    /** Runs [block] with the engine promoted to the microphone type where that is possible. */
    suspend fun <T> withMicrophone(block: suspend () -> T): T = withType(Kind.MICROPHONE, block)

    /**
     * Claims [kind] for the length of [block].
     *
     * The release runs in a `finally` and **swallows its own failure**: by then the service
     * may have been destroyed under us, and a throw there would replace a perfectly good
     * photograph or recording with a crash.
     */
    private suspend fun <T> withType(kind: Kind, block: suspend () -> T): T {
        synchronized(lock) { claims[kind] = (claims[kind] ?: 0) + 1 }
        try {
            apply()
            return block()
        } finally {
            synchronized(lock) {
                val left = (claims[kind] ?: 1) - 1
                if (left <= 0) claims.remove(kind) else claims[kind] = left
            }
            apply()
        }
    }

    /**
     * Hands the current claim set to the service, unless it already holds exactly that.
     *
     * `startForeground` posts a notification, so the call is made on the main looper; the
     * capture or recording itself stays off it.
     */
    @Suppress("TooGenericExceptionCaught") // Any platform refusal means "not promoted".
    private suspend fun apply() {
        val types = host ?: return
        applyLock.withLock {
            val wanted = synchronized(lock) { claims.keys.toSet() }
            if (wanted == applied) return
            applied = withContext(Dispatchers.Main) {
                try {
                    types.setTypes(wanted)
                } catch (_: Exception) {
                    emptySet()
                }
            }
        }
    }
}
