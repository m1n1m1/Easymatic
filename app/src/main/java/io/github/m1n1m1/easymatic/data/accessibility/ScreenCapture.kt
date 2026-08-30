package io.github.m1n1m1.easymatic.data.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import java.util.concurrent.Executors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * One frame of the screen, or the sentence explaining why there is not one.
 *
 * Public where [ScreenCapture] is internal, because it is the type `MediaImages` takes on
 * its constructor and that class is the facade's public implementation. Nothing outside
 * this package can produce one.
 */
sealed interface ScreenGrab {
    /** A **software** bitmap — see [ScreenCapture.grab]. The caller owns it. */
    data class Captured(val bitmap: Bitmap) : ScreenGrab

    data class Failed(val reason: String) : ScreenGrab
}

/**
 * Capturing the screen through the accessibility service.
 *
 * `AccessibilityService.takeScreenshot` is the only route an ordinary app has to its own
 * screen without a per-run consent dialog. MediaProjection is the other one and was
 * rejected for this: it needs a second foreground service (the engine's `specialUse` type
 * cannot carry `mediaProjection`) and an Activity started from the background to ask for
 * consent, which `AndroidSystemServices.canStartActivity`'s KDoc already documents as
 * blocked for a foreground service. `performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)`
 * was rejected too, for a plainer reason: it answers with a boolean and no picture, so a
 * node built on it could not say what it had produced.
 *
 * **Nothing here throws**, including [OutOfMemoryError] — a full-resolution frame is a
 * real allocation and `ImageEditor` already treats that error as an outcome rather than a
 * crash. Every failure is a [ScreenGrab.Failed] carrying a sentence a person can act on.
 *
 * **Serialised process-wide.** The platform rate-limits screenshots to roughly one a
 * second and answers `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` for the second one, so
 * two macros firing together queue here rather than one of them failing. `ImageEditor` and
 * `OverlayPrompts` hold the same shape for the same reason.
 */
internal object ScreenCapture {

    private val gate = Mutex()

    /** Callbacks arrive on this rather than the main thread; one thread is plenty. */
    private val callbackExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "easymatic-screenshot").apply { isDaemon = true }
    }

    /**
     * One frame of the default display.
     *
     * The returned bitmap is a **software** copy. `takeScreenshot` hands back a
     * `HardwareBuffer`, which is a native allocation the garbage collector does not
     * reclaim and which the caller would otherwise have to remember to close; copying to
     * `ARGB_8888` and closing the buffer here means nothing downstream holds a graphics
     * resource and `Bitmap.compress` works on every version.
     */
    @Suppress("TooGenericExceptionCaught") // The contract is "never throws"; see the KDoc.
    suspend fun grab(): ScreenGrab = gate.withLock {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withLock ScreenGrab.Failed(TOO_OLD)
        val service = EasymaticAccessibilityService.instance ?: return@withLock ScreenGrab.Failed(NOT_ENABLED)
        try {
            takeOne(service)
        } catch (_: OutOfMemoryError) {
            ScreenGrab.Failed(TOO_BIG)
        } catch (failure: Exception) {
            ScreenGrab.Failed(failed(failure))
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @Suppress("TooGenericExceptionCaught") // The contract is "never throws"; see the KDoc.
    private suspend fun takeOne(service: AccessibilityService): ScreenGrab =
        suspendCancellableCoroutine { continuation ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                callbackExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val grab = try {
                            // Close the buffer whatever happens: it is native memory, and
                            // leaking one per capture is a phone that stops capturing.
                            screenshot.hardwareBuffer.use { buffer ->
                                val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                hardware?.copy(Bitmap.Config.ARGB_8888, false)
                                    ?.let { ScreenGrab.Captured(it) }
                                    ?: ScreenGrab.Failed(UNREADABLE)
                            }
                        } catch (_: OutOfMemoryError) {
                            // Caught by name: an Error slips past runCatching, and letting
                            // it out takes the engine down with every armed macro on it.
                            ScreenGrab.Failed(TOO_BIG)
                        } catch (error: Throwable) {
                            ScreenGrab.Failed(failed(error))
                        }
                        if (continuation.isActive) continuation.resume(grab)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) continuation.resume(ScreenGrab.Failed(reasonFor(errorCode)))
                    }
                },
            )
        }

    /**
     * A sentence per platform error code.
     *
     * Spelled out rather than collapsed into "the screenshot failed" because these are
     * genuinely different situations and only some of them are the user's to fix: a rate
     * limit clears by itself, a secure window never will, and no accessibility access is a
     * switch in Settings.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonFor(errorCode: Int): String = when (errorCode) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> TOO_SOON
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> NOT_ENABLED
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> NO_DISPLAY
        else ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
            ) {
                SECURE
            } else {
                INTERNAL
            }
    }

    private fun failed(error: Throwable): String =
        error.message?.ifBlank { null }?.let { "The screen could not be captured: $it" } ?: INTERNAL

    private const val TOO_OLD =
        "Taking a screenshot needs Android 11 or newer"
    private const val NOT_ENABLED =
        "Easymatic needs accessibility access to take a screenshot — turn it on in " +
            "Settings, Accessibility, Easymatic"
    private const val TOO_SOON =
        "Another screenshot was taken less than a second ago, so this one was refused"
    private const val SECURE =
        "The app on screen does not allow screenshots"
    private const val NO_DISPLAY =
        "There was no screen to capture"
    private const val INTERNAL =
        "Android could not capture the screen"
    private const val UNREADABLE =
        "The captured screen could not be read"
    private const val TOO_BIG =
        "There was not enough memory to capture the screen"
}
