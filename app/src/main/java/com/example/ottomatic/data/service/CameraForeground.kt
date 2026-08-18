package com.example.ottomatic.data.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The engine service's foreground-service type, borrowed for the length of one capture.
 *
 * ### Why anything is needed
 *
 * From Android 11 a foreground service's access to the camera follows its declared
 * *foreground service type*, not the app's `CAMERA` grant: a `specialUse` service can hold
 * the grant and still be refused the sensor. So while a photo is being taken the type has
 * to say `camera`.
 *
 * ### Why it cannot simply say so permanently
 *
 * From API 34 `startForeground` **throws** when a named type's permission is not held. A
 * service that always claimed `camera` would therefore fail to start — taking every armed
 * macro down with it — on every phone whose owner never granted camera access, for the sake
 * of one node out of a hundred. The claim has to be made only when it can be honoured.
 *
 * ### Why a registered callback rather than a service handle
 *
 * `data` may not reach `engine`, where the service lives. `OttomaticAccessibilityService`
 * can publish an instance because `ScreenCapture` sits in the same package; here the two
 * halves are on opposite sides of a dependency rule, so the service hands *in* the one
 * thing it can do rather than handing out itself.
 *
 * ### What it promises
 *
 * Almost nothing, and deliberately. [withCamera] promotes if it can, always runs the block,
 * and always reverts. A promotion that fails — no service running, as in an editor preview
 * run; the permission not granted; a platform policy refusal — is **not** an error: the
 * capture is attempted anyway, because a service started while the app was in the
 * foreground may well already have the access, and the camera itself then reports any
 * refusal in a sentence a person can act on. [ForegroundGrant]'s stance exactly: stop the
 * app refusing before the platform gets a chance to accept.
 */
internal object CameraForeground {

    /**
     * What the engine service can do about its own foreground type.
     *
     * Answers whether the camera type is held *after* the call, so a refusal and a success
     * are told apart by the one thing that knows — the service, which is where the
     * permission check and the platform call both live.
     */
    fun interface Types {
        fun setCamera(wanted: Boolean): Boolean
    }

    @Volatile
    private var host: Types? = null

    /** Registered by `MacroEngineService.onCreate`. */
    fun attach(types: Types) {
        host = types
    }

    /**
     * Cleared by `MacroEngineService.onDestroy`, comparing **identity**: a service that has
     * already been recreated must not have its successor's registration cleared by the
     * teardown of the one it replaced.
     */
    fun detach(types: Types) {
        if (host === types) host = null
    }

    /**
     * Runs [block] with the engine promoted to the camera type where that is possible.
     *
     * The revert runs in a `finally` and **swallows its own failure**: by then the service
     * may have been destroyed under us, and a throw there would replace a perfectly good
     * photograph with a crash.
     */
    suspend fun <T> withCamera(block: suspend () -> T): T {
        val types = host
        val promoted = types != null && set(types, wanted = true)
        try {
            return block()
        } finally {
            if (promoted && types != null) set(types, wanted = false)
        }
    }

    /**
     * `startForeground` posts a notification, so the call is made on the main looper; the
     * capture itself stays off it.
     */
    @Suppress("TooGenericExceptionCaught") // Any platform refusal means "not promoted".
    private suspend fun set(types: Types, wanted: Boolean): Boolean = withContext(Dispatchers.Main) {
        try {
            types.setCamera(wanted)
        } catch (_: Exception) {
            false
        }
    }
}
