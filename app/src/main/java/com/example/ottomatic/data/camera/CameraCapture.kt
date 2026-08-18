package com.example.ottomatic.data.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import com.example.ottomatic.core.service.FlashMode
import com.example.ottomatic.data.service.CameraForeground
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** What `action.camera_photo` asked for, in the terms a camera understands. */
data class CameraShot(
    val front: Boolean = false,
    val flash: FlashMode = FlashMode.OFF,
    /** Already clamped by the facade. Spent with the camera open and metering. */
    val delayMs: Long = 0,
)

/**
 * One photograph, or the sentence explaining why there is not one.
 *
 * Public where [CameraCapture] is internal, on `ScreenGrab`'s reasoning: it is the type
 * `MediaImages` takes on its constructor, and nothing outside this package can produce one.
 *
 * [Captured.jpeg] is **already encoded** — some four megabytes for a fifty-megapixel sensor,
 * not the two hundred a decode would cost. Nothing on this path ever decodes it, which is
 * why there is no size cap here and no `ImageScalePlan`: `ImageLimits.MAX_DECODE_PIXELS`
 * counts *decoded* pixels at four bytes each, and there are none.
 */
sealed interface CameraGrab {
    /** Equality is never asked of this; the array exists to be written to a stream once. */
    data class Captured(val jpeg: ByteArray, val width: Int, val height: Int) : CameraGrab

    data class Failed(val reason: String) : CameraGrab
}

/**
 * Taking one photograph with the phone's own camera, through camera2 and with no UI.
 *
 * `ScreenCapture`'s counterpart for the world rather than the screen, and built to the same
 * contract: **nothing here throws**, every failure is a [CameraGrab.Failed] carrying a
 * sentence a person can act on, [OutOfMemoryError] is caught **by name** because it is an
 * `Error` and slips past every `runCatching`, and the whole thing is **serialised
 * process-wide** because a camera is exclusive hardware — two macros firing together queue
 * rather than one of them failing.
 *
 * ### Why camera2 rather than a camera app
 *
 * `ACTION_IMAGE_CAPTURE` hands the job to another app in the foreground and needs somebody
 * to press a shutter, so a macro built on it does nothing at all while the phone is in a
 * pocket. It also cannot honour either thing the node offers: lens choice is a hint most
 * camera apps ignore, and a delay is meaningless when a person is taking the picture. There
 * is no `androidx.camera` dependency in this app and this adds none — the torch already
 * talks to `CameraManager` directly.
 *
 * ### The metering stream is not optional
 *
 * A still request fired at a camera that was powered on a moment ago comes back dark or
 * green, because 3A has no history to work from. So a repeating request runs first — and it
 * must **not** target the JPEG reader, which would encode a full photograph per metering
 * frame. A small `YUV_420_888` reader whose images are closed on arrival is the headless
 * stand-in for the preview surface an app with a screen would use.
 *
 * ### Everything is bounded
 *
 * A camera that never calls back must not hang a macro. Open, configure, precapture and the
 * still are each timed out, so the worst case is roughly twenty-three seconds plus the
 * configured delay, followed by a sentence.
 *
 * ### It takes the torch out
 *
 * Opening a camera revokes any torch `AndroidSystemServices.setTorch` switched on, and it is
 * not switched back: restoring it would be a hidden side effect and would fight a flash
 * capture. `action.camera_photo`'s own flash field is the visible half of the same fact.
 *
 * There is no JVM-testable seam here — every line is platform — so this is verified on a
 * device rather than faked.
 */
@Suppress("TooManyFunctions") // One camera2 session, decomposed so each callback shim
// stays readable; merging them would produce one method nobody can follow.
internal object CameraCapture {

    /** The camera is exclusive hardware, so two macros firing together queue rather than fail. */
    private val gate = Mutex()

    /** camera2 wants a `Handler` on this API floor; one background looper serves every callback. */
    private val handler: Handler by lazy {
        Handler(HandlerThread("ottomatic-camera").apply { start() }.looper)
    }

    /** Whether this phone has a camera flash at all, for the node's degradation warning. */
    fun hasFlash(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)

    /**
     * One photograph.
     *
     * The permission is checked here rather than left to the platform because a
     * `SecurityException` out of `openCamera` says nothing a person can act on, where this
     * names the switch in Settings.
     */
    suspend fun take(context: Context, shot: CameraShot): CameraGrab = gate.withLock {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withLock CameraGrab.Failed(NO_PERMISSION)
        }
        // Promoted for exactly as long as the camera is open, and no longer: from Android 11
        // a foreground service's camera access follows its service *type*, and from API 34
        // claiming that type without the grant throws. See CameraForeground.
        CameraForeground.withCamera { shoot(context, shot) }
    }

    @SuppressLint("MissingPermission") // Checked in `take`, which is the only caller.
    @Suppress("ReturnCount", "TooGenericExceptionCaught", "LongMethod") // One exit per
    // platform refusal, each naming a different thing the user can do about it.
    private suspend fun shoot(context: Context, shot: CameraShot): CameraGrab {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return CameraGrab.Failed(NO_CAMERA)

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var still: ImageReader? = null
        var metering: ImageReader? = null
        return try {
            val id = idFor(manager, shot.front)
                ?: return CameraGrab.Failed(if (shot.front) NO_FRONT else NO_BACK)
            val traits = manager.getCameraCharacteristics(id)
            val size = largestJpeg(traits) ?: return CameraGrab.Failed(NO_SIZES)

            still = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
            metering = ImageReader.newInstance(
                METERING_SIDE,
                METERING_SIDE,
                ImageFormat.YUV_420_888,
                METERING_BUFFERS,
            ).apply {
                // Closed on arrival: the queue is two deep, and a full one stalls the
                // session — which shows up as a still capture that simply never happens.
                setOnImageAvailableListener({ reader ->
                    runCatching { reader.acquireLatestImage()?.close() }
                }, handler)
            }

            val opened = openDevice(manager, id)
            device = opened.getOrElse { return CameraGrab.Failed(it.message ?: INTERNAL) }
            session = configure(device, listOf(metering.surface, still.surface))
                ?: return CameraGrab.Failed(NO_SESSION)

            meter(session, metering.surface, traits, shot)
            val bytes = fireStill(session, still, traits, shot) ?: return CameraGrab.Failed(NO_FRAME)
            CameraGrab.Captured(bytes, size.width, size.height)
        } catch (failure: CameraAccessException) {
            CameraGrab.Failed(reasonFor(failure.reason))
        } catch (_: OutOfMemoryError) {
            // By name: an Error slips past runCatching, and letting it out of the coroutine
            // takes the engine down with every armed macro on it.
            CameraGrab.Failed(TOO_BIG)
        } catch (failure: Exception) {
            CameraGrab.Failed(failed(failure))
        } finally {
            // Order matters: the session first, then the device, then the readers whose
            // surfaces they were holding. Leak the device and this phone's camera stays
            // locked against every app on it until the process dies.
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { still?.close() }
            runCatching { metering?.close() }
        }
    }

    /**
     * Opens the camera, or answers why not.
     *
     * A device that arrives **after** the timeout is still closed rather than dropped: an
     * orphaned `CameraDevice` is a camera nothing on the phone can open again.
     */
    @SuppressLint("MissingPermission") // Checked in `take`, which is the only entry point.
    private suspend fun openDevice(manager: CameraManager, id: String): Result<CameraDevice> =
        withTimeoutOrNull(OPEN_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                manager.openCamera(
                    id,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            if (continuation.isActive) {
                                continuation.resume(Result.success(camera))
                            } else {
                                camera.close()
                            }
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            if (continuation.isActive) {
                                continuation.resume(Result.failure(Refusal(DISCONNECTED)))
                            }
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            camera.close()
                            if (continuation.isActive) {
                                continuation.resume(Result.failure(Refusal(openReason(error))))
                            }
                        }
                    },
                    handler,
                )
            }
        } ?: Result.failure(Refusal(TOO_SLOW))

    @Suppress("DEPRECATION") // The `SessionConfiguration` overload is API 28 and this one is
    // identical in effect, so this stays a single code path across the supported range.
    private suspend fun configure(
        device: CameraDevice,
        surfaces: List<Surface>,
    ): CameraCaptureSession? = withTimeoutOrNull(SESSION_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            device.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (continuation.isActive) continuation.resume(session) else session.close()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        if (continuation.isActive) continuation.resume(null)
                    }
                },
                handler,
            )
        }
    }

    /**
     * Runs the camera long enough to expose, focus and white-balance — and spends the
     * configured delay while it does.
     *
     * [SETTLE_MS] is a floor rather than a courtesy: a sensor powered on a moment ago has
     * not metered yet, and a zero-delay photo taken without it comes out dark or green. The
     * user's own delay is spent **here**, with the camera open and metering, which is the
     * whole difference between this field and an `action.delay` wired in front of the node.
     */
    private suspend fun meter(
        session: CameraCaptureSession,
        surface: Surface,
        traits: CameraCharacteristics,
        shot: CameraShot,
    ) {
        val builder = session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, afMode(traits))
            applyFlash(this, traits, shot.flash)
        }
        session.setRepeatingRequest(builder.build(), null, handler)
        delay(SETTLE_MS + shot.delayMs)
        if (shot.flash != FlashMode.OFF) awaitPrecapture(session, builder)
    }

    /**
     * Asks auto-exposure to run its pre-flash and waits, briefly, for it to settle.
     *
     * Without this a forced flash frequently lands on the pre-flash rather than on the
     * exposure, which reads as the flash firing and the photo still being dark. A camera
     * that reports no AE state at all — some devices do not — resolves immediately rather
     * than burning the whole timeout, and a camera that never converges fires anyway: a
     * mediocre photograph beats no photograph.
     */
    private suspend fun awaitPrecapture(
        session: CameraCaptureSession,
        builder: CaptureRequest.Builder,
    ) {
        val settled = CompletableDeferred<Unit>()
        val triggered = AtomicBoolean(false)
        val watcher = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                if (!triggered.get()) return
                val state = result.get(CaptureResult.CONTROL_AE_STATE)
                if (state == null || state in SETTLED_AE_STATES) settled.complete(Unit)
            }
        }
        session.setRepeatingRequest(builder.build(), watcher, handler)
        builder.set(
            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
            CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START,
        )
        session.capture(builder.build(), watcher, handler)
        triggered.set(true)
        // Left IDLE afterwards so the still request does not re-trigger the pre-flash.
        builder.set(
            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
            CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE,
        )
        withTimeoutOrNull(PRECAPTURE_TIMEOUT_MS) { settled.await() }
    }

    private suspend fun fireStill(
        session: CameraCaptureSession,
        reader: ImageReader,
        traits: CameraCharacteristics,
        shot: CameraShot,
    ): ByteArray? {
        val arrived = CompletableDeferred<ByteArray?>()
        reader.setOnImageAvailableListener({ source ->
            // One copy, and it is unavoidable: the plane's direct buffer is invalid the
            // moment the Image is closed, and closing it is what frees the reader's single
            // slot. Four megabytes of encoded JPEG, not a bitmap.
            val bytes = runCatching {
                source.acquireNextImage()?.use { image ->
                    val plane = image.planes[0].buffer
                    ByteArray(plane.remaining()).also(plane::get)
                }
            }.getOrNull()
            arrived.complete(bytes)
        }, handler)

        val request = session.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            set(CaptureRequest.JPEG_ORIENTATION, uprightDegrees(traits))
            set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY)
            set(CaptureRequest.CONTROL_AF_MODE, afMode(traits))
            applyFlash(this, traits, shot.flash)
        }.build()

        session.stopRepeating()
        session.capture(request, null, handler)
        return withTimeoutOrNull(STILL_TIMEOUT_MS) { arrived.await() }
    }

    /**
     * How far a viewer must turn the JPEG to see it upright.
     *
     * **The device's rotation is deliberately not consulted.** The textbook formula adds the
     * display rotation, and a headless capture has no display to ask: the engine owns no
     * window, and `WindowManager` reports rotation 0 on a rotation-locked phone however it
     * is actually being held — so reading it would be wrong half the time rather than right.
     * With that term zero the formula collapses to the sensor's own orientation for both
     * lenses, which is what an unattended camera can honestly promise: upright with respect
     * to the phone's natural orientation.
     *
     * This is a **tag rather than a rotation of the pixels**, so it is exactly what
     * `ImageExif`, `action.image_edit` and every gallery already read.
     */
    private fun uprightDegrees(traits: CameraCharacteristics): Int =
        traits.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

    /**
     * Continuous focus where the phone offers it, which is what makes the metering period
     * focus as well as expose. A camera with no auto-focus at all — a fixed-focus front
     * lens — gets `OFF` rather than an unsupported mode, which some devices reject outright.
     */
    private fun afMode(traits: CameraCharacteristics): Int {
        val available = traits.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet().orEmpty()
        return when {
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in available ->
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE

            CameraMetadata.CONTROL_AF_MODE_AUTO in available -> CameraMetadata.CONTROL_AF_MODE_AUTO
            else -> CameraMetadata.CONTROL_AF_MODE_OFF
        }
    }

    /**
     * The flash, expressed as an auto-exposure mode.
     *
     * **Asked of the phone rather than assumed**: an unsupported AE mode is quietly ignored
     * on some devices and rejected on others, and "the flash did nothing and nothing said
     * why" is the outcome to avoid.
     *
     * `FLASH_MODE` stays `OFF` on purpose. It is consulted only while AE is `OFF` or plain
     * `ON`; with `ON_ALWAYS_FLASH` or `ON_AUTO_FLASH` the AE routine fires the unit itself,
     * and setting `FLASH_MODE_SINGLE` beside it double-fires on some hardware.
     */
    private fun applyFlash(
        builder: CaptureRequest.Builder,
        traits: CameraCharacteristics,
        flash: FlashMode,
    ) {
        val hasUnit = traits.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val wanted = when {
            !hasUnit || flash == FlashMode.OFF -> CameraMetadata.CONTROL_AE_MODE_ON
            flash == FlashMode.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            else -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
        }
        val available = traits.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toSet().orEmpty()
        builder.set(
            CaptureRequest.CONTROL_AE_MODE,
            if (wanted in available) wanted else CameraMetadata.CONTROL_AE_MODE_ON,
        )
        builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
    }

    /**
     * The **first** camera facing the requested way, which by convention is the main lens.
     *
     * `cameraIdList` already hides a multi-camera phone's physical sub-lenses behind one
     * logical camera, so there is nothing here for a user to choose between — which is why
     * the node's field is a two-valued enum rather than a picker over camera ids.
     */
    private fun idFor(manager: CameraManager, front: Boolean): String? {
        val wanted = if (front) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        return manager.cameraIdList.firstOrNull {
            runCatching {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)
            }.getOrNull() == wanted
        }
    }

    private fun largestJpeg(traits: CameraCharacteristics): Size? =
        traits.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)
            ?.maxByOrNull { it.width.toLong() * it.height }

    /**
     * A sentence per platform error code.
     *
     * Spelled out rather than collapsed into "the photo failed" on `ScreenCapture`'s rule:
     * these are genuinely different situations and only some are the user's to fix — another
     * app holding the camera clears by itself, a work-profile policy never will.
     */
    private fun reasonFor(code: Int): String = when (code) {
        CameraAccessException.CAMERA_IN_USE -> IN_USE
        CameraAccessException.MAX_CAMERAS_IN_USE -> TOO_MANY
        CameraAccessException.CAMERA_DISABLED -> DISABLED
        CameraAccessException.CAMERA_DISCONNECTED -> DISCONNECTED
        else -> INTERNAL
    }

    private fun openReason(code: Int): String = when (code) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> IN_USE
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> TOO_MANY
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> DISABLED
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> FAULT
        else -> INTERNAL
    }

    private fun failed(error: Throwable): String =
        error.message?.ifBlank { null }?.let { "The photo could not be taken: $it" } ?: INTERNAL

    /** Carries a sentence out of a callback without pretending to be a platform exception. */
    private class Refusal(message: String) : Exception(message)

    /** AE states that mean "exposed enough to fire". */
    private val SETTLED_AE_STATES = setOf(
        CameraMetadata.CONTROL_AE_STATE_CONVERGED,
        CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED,
        CameraMetadata.CONTROL_AE_STATE_LOCKED,
    )

    private const val NO_PERMISSION =
        "Ottomatic needs camera access to take a photo — turn it on in Settings, Apps, " +
            "Ottomatic, Permissions"
    private const val NO_CAMERA = "This phone has no camera Ottomatic can use"
    private const val NO_FRONT = "This phone has no front camera"
    private const val NO_BACK = "This phone has no back camera"
    private const val NO_SIZES = "This phone's camera offers no photo size Ottomatic can use"
    private const val NO_SESSION = "This phone's camera could not be set up to take a photo"
    private const val NO_FRAME = "The camera did not produce a photo"
    private const val IN_USE = "Another app is using the camera right now"
    private const val TOO_MANY = "Too many cameras are open on this phone right now"
    private const val DISABLED =
        "The camera has been switched off on this phone, usually by a work profile or a " +
            "device policy"
    private const val DISCONNECTED = "The camera was disconnected before the photo was taken"
    private const val FAULT = "This phone's camera reported a fault"
    private const val TOO_SLOW = "The camera did not respond in time"
    private const val TOO_BIG = "There was not enough memory to take a photo"
    private const val INTERNAL = "Android could not take a photo"

    private const val OPEN_TIMEOUT_MS = 5_000L
    private const val SESSION_TIMEOUT_MS = 5_000L
    private const val STILL_TIMEOUT_MS = 10_000L
    private const val PRECAPTURE_TIMEOUT_MS = 2_000L

    /** A floor under every capture: a sensor powered on a moment ago has not metered yet. */
    private const val SETTLE_MS = 700L

    /** Big enough for 3A to work from, small enough that the frames cost nothing. */
    private const val METERING_SIDE = 320
    private const val METERING_BUFFERS = 2
    /** A `Byte`, because that is what `CaptureRequest.JPEG_QUALITY` is keyed to. */
    private const val JPEG_QUALITY: Byte = 95
}
