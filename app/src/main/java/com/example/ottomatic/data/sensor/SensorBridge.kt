package com.example.ottomatic.data.sensor

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.engine.trigger.ScheduleHandle
import com.example.ottomatic.engine.trigger.ScreenOffMode
import com.example.ottomatic.engine.trigger.SensorKind
import com.example.ottomatic.engine.trigger.SensorRate
import com.example.ottomatic.engine.trigger.SensorSample
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap
import android.hardware.TriggerEvent as SensorTriggerEvent

/**
 * Owns the app's `SensorManager` registrations, multicasts each sensor to every
 * trigger that asked for it, and decides whether the accelerometer stays alive
 * once the screen goes off.
 *
 * **One registration per sensor, not per subscriber.** Two collectors of the
 * accelerometer at 20 ms and 5 ms would make the HAL run at 5 ms anyway while
 * the framework decimated for one of them — paying the high rate twice over. So
 * the stream is keyed by [SensorKind] alone and registered at the fastest rate
 * any live subscriber asked for. The price is that a detector cannot assume the
 * rate it requested is the rate it gets, which is why every detector derives its
 * timing from [SensorSample.elapsedMs] rather than from a sample count.
 *
 * **Screen-off is a policy, not a side effect.** With the screen off the
 * application processor suspends and non-wake-up sensors stop reporting unless
 * something holds a wake lock. [armScreenOffSensing] collects what the armed
 * triggers want and [reconcile] turns the strongest request into a concrete
 * registration, wake lock and significant-motion gate.
 *
 * Callbacks land on a dedicated [HandlerThread]: 200 Hz on the main looper would
 * compete with Compose for the frame budget.
 *
 * Unlike [com.example.ottomatic.data.trigger.ScreenBroadcastBridge] the sample
 * stream does not go through [TriggerBus]. The bus is `replay = 0`,
 * `extraBufferCapacity = 64` and `tryEmit`, so it would drop samples under load
 * and would wake every armed trigger in the process on every reading. The two
 * significant-motion paths *do* use it — they produce at most one event every
 * few seconds.
 */
@Suppress("TooManyFunctions") // Registration, screen-off policy and the SMD gate; each is small.
class SensorBridge(context: Context) {

    private val appContext = context.applicationContext

    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val powerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val thread = HandlerThread("ottomatic-sensors").apply { start() }

    private val handler = Handler(thread.looper)

    private val wakeLock: PowerManager.WakeLock =
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }

    /**
     * Concurrent so [listener] can find its stream without taking [lock] two
     * hundred times a second. The lock guards the bookkeeping, not the lookup.
     */
    private val streams = ConcurrentHashMap<SensorKind, KindStream>()

    private val lock = Any()

    /** Reference count per requested screen-off mode, across all armed triggers. */
    private val screenOffModes = mutableMapOf<ScreenOffMode, Int>()

    private var screenOn: Boolean = powerManager.isInteractive

    /** While the screen is off in [ScreenOffMode.WHEN_MOVING], until when to sample. */
    private var motionWindowUntilMs: Long = 0

    private var gatingListener: TriggerEventListener? = null

    /** The live subscribers and current registration for one sensor. */
    private class KindStream(val sensor: Sensor) {

        /**
         * DROP_OLDEST and never suspend: the sensor callback runs on the sensor
         * thread and must not be back-pressured by a slow collector. A gesture
         * detector that has fallen 256 samples behind has already lost.
         *
         * `replay = 1` so a subscriber immediately sees the current reading. The
         * platform publishes an on-change sensor's value the moment it is
         * registered, which is before the collector has subscribed — without the
         * replay that reading is dropped, and a proximity detector then treats
         * the user's first wave as its starting state instead of a gesture.
         */
        val flow = MutableSharedFlow<SensorSample>(
            replay = 1,
            extraBufferCapacity = BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        /** Reference count per requested rate. */
        val rates = mutableMapOf<SensorRate, Int>()

        var registeredAt: SensorRate? = null

        val wanted: SensorRate? get() = rates.keys.minByOrNull { it.periodUs }
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val kind = KIND_BY_SENSOR_TYPE[event.sensor.type] ?: return
            val stream = streams[kind] ?: return
            val values = event.values
            stream.flow.tryEmit(
                SensorSample(
                    x = values.getOrElse(0) { 0f },
                    y = values.getOrElse(1) { 0f },
                    z = values.getOrElse(2) { 0f },
                    elapsedMs = event.timestamp / NANOS_PER_MILLI,
                ),
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val on = when (intent.action) {
                Intent.ACTION_SCREEN_ON -> true
                Intent.ACTION_SCREEN_OFF -> false
                else -> return
            }
            synchronized(lock) {
                screenOn = on
                // Leaving the screen-off window behind: a new one has to be
                // earned by significant motion again.
                if (on) motionWindowUntilMs = 0
                reconcile()
            }
        }
    }

    init {
        // These two cannot be declared in the manifest; the platform ignores it.
        appContext.registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
    }

    /**
     * Samples from [kind] at no less than [rate], for as long as the returned
     * flow is collected. Empty when the device has no such sensor.
     */
    fun samples(kind: SensorKind, rate: SensorRate): Flow<SensorSample> = flow {
        val stream = acquire(kind, rate) ?: return@flow
        try {
            stream.flow.collect { emit(it) }
        } finally {
            release(kind, rate)
        }
    }

    /** See [com.example.ottomatic.engine.trigger.TriggerHost.armScreenOffSensing]. */
    fun armScreenOffSensing(mode: ScreenOffMode): ScheduleHandle {
        synchronized(lock) {
            screenOffModes[mode] = (screenOffModes[mode] ?: 0) + 1
            reconcile()
        }
        return ScheduleHandle {
            synchronized(lock) {
                val remaining = (screenOffModes[mode] ?: 0) - 1
                if (remaining <= 0) screenOffModes.remove(mode) else screenOffModes[mode] = remaining
                reconcile()
            }
        }
    }

    /**
     * Arms the significant-motion sensor for [nodeId] and re-arms it after every
     * fire, since the platform disables it on delivery.
     *
     * This reports through [TriggerBus]: it produces one event every few seconds
     * at most, so none of the reasons to keep sensors off the bus apply, and the
     * bus lets the event be addressed to its own node the way the geofence and
     * alarm receivers do.
     *
     * A device without the sensor gets a handle that does nothing, leaving the
     * trigger silent rather than failing to arm.
     */
    fun armSignificantMotion(nodeId: NodeId): ScheduleHandle {
        val sensor = significantMotionSensor ?: return ScheduleHandle { }
        val motionListener = object : TriggerEventListener() {
            override fun onTrigger(event: SensorTriggerEvent?) {
                TriggerBus.emit(
                    TriggerEvent(
                        source = TriggerSource.HARDWARE,
                        triggerNodeId = nodeId,
                        payload = mapOf(
                            KEY_TRIGGER_TYPE to SIGNIFICANT_MOTION_TYPE,
                            KEY_EVENT to SIGNIFICANT_MOTION_EVENT,
                            KEY_TIMESTAMP to System.currentTimeMillis().toString(),
                        ),
                    ),
                )
                // One-shot by contract: the platform disarms it on delivery.
                sensorManager.requestTriggerSensor(this, sensor)
            }
        }
        sensorManager.requestTriggerSensor(motionListener, sensor)
        return ScheduleHandle { sensorManager.cancelTriggerSensor(motionListener, sensor) }
    }

    /** See [com.example.ottomatic.engine.trigger.TriggerHost.sensorMaximumRange]. */
    fun maximumRange(kind: SensorKind): Float? = defaultSensor(kind)?.maximumRange

    private fun acquire(kind: SensorKind, rate: SensorRate): KindStream? = synchronized(lock) {
        val stream = streams[kind]
            ?: KindStream(defaultSensor(kind) ?: return null).also { streams[kind] = it }
        stream.rates[rate] = (stream.rates[rate] ?: 0) + 1
        reconcile()
        stream
    }

    private fun release(kind: SensorKind, rate: SensorRate) = synchronized(lock) {
        val stream = streams[kind] ?: return
        val remaining = (stream.rates[rate] ?: 0) - 1
        if (remaining <= 0) stream.rates.remove(rate) else stream.rates[rate] = remaining
        reconcile()
    }

    /**
     * Brings every platform resource in line with what is currently subscribed.
     * Callers must hold [lock].
     *
     * | screen | strongest mode | accelerometer | wake lock | motion gate |
     * |--------|----------------|---------------|-----------|-------------|
     * | on     | any            | registered    | released  | cancelled   |
     * | off    | `NEVER`        | unregistered  | released  | cancelled   |
     * | off    | `WHEN_MOVING`  | only in window| in window | armed       |
     * | off    | `ALWAYS`       | registered    | held      | cancelled   |
     */
    private fun reconcile() {
        val mode = strongestMode()
        val accelerometerAllowed = screenOn || when (mode) {
            ScreenOffMode.NEVER -> false
            ScreenOffMode.WHEN_MOVING -> inMotionWindow()
            ScreenOffMode.ALWAYS -> true
        }
        for ((kind, stream) in streams) {
            // Only the accelerometer is gated. Proximity prefers its wake-up
            // variant and light is not worth waking the CPU for.
            val wanted = if (kind == SensorKind.ACCELEROMETER && !accelerometerAllowed) null else stream.wanted
            applyRegistration(stream, wanted)
        }
        // Nothing below matters unless something actually wants the accelerometer
        // and the screen is off; with the screen on the CPU is awake anyway.
        val pending = !screenOn && streams[SensorKind.ACCELEROMETER]?.wanted != null
        updateWakeLock(hold = pending && accelerometerAllowed)
        updateMotionGate(arm = pending && mode == ScreenOffMode.WHEN_MOVING && !inMotionWindow())
    }

    private fun applyRegistration(stream: KindStream, wanted: SensorRate?) {
        if (wanted == stream.registeredAt) return
        // Registering the same listener/sensor pair twice is a no-op in the
        // framework, so a rate change has to unregister before it re-registers.
        sensorManager.unregisterListener(listener, stream.sensor)
        if (wanted != null) {
            sensorManager.registerListener(listener, stream.sensor, wanted.periodUs, handler)
        }
        stream.registeredAt = wanted
    }

    // No timeout on purpose: the lock is released by reconcile() when the screen
    // comes back on or the last interested trigger disarms, and a timeout would
    // silently stop a macro the user explicitly opted into.
    @SuppressLint("WakelockTimeout")
    private fun updateWakeLock(hold: Boolean) {
        if (hold && !wakeLock.isHeld) wakeLock.acquire()
        if (!hold && wakeLock.isHeld) wakeLock.release()
    }

    private fun updateMotionGate(arm: Boolean) {
        val sensor = significantMotionSensor
        if (arm && gatingListener == null && sensor != null) {
            val gate = object : TriggerEventListener() {
                override fun onTrigger(event: SensorTriggerEvent?) {
                    synchronized(lock) {
                        // One-shot: the platform has already disarmed it.
                        gatingListener = null
                        motionWindowUntilMs = SystemClock.elapsedRealtime() + MOTION_WINDOW_MS
                        reconcile()
                    }
                    // Close the window again, which re-arms the gate.
                    handler.postDelayed({ synchronized(lock) { reconcile() } }, MOTION_WINDOW_MS)
                }
            }
            gatingListener = gate
            sensorManager.requestTriggerSensor(gate, sensor)
        }
        if (!arm && gatingListener != null && sensor != null) {
            sensorManager.cancelTriggerSensor(gatingListener, sensor)
            gatingListener = null
        }
    }

    private fun inMotionWindow(): Boolean = SystemClock.elapsedRealtime() < motionWindowUntilMs

    private fun strongestMode(): ScreenOffMode =
        screenOffModes.keys.maxByOrNull { it.ordinal } ?: ScreenOffMode.NEVER

    private val significantMotionSensor: Sensor?
        get() = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    private fun defaultSensor(kind: SensorKind): Sensor? = when (kind) {
        SensorKind.ACCELEROMETER -> sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        // The wake-up proximity sensor keeps reporting with the screen off at no
        // extra cost, which is the whole reason a wave-to-act macro works while
        // the phone is face down on a desk. Not every device has one.
        SensorKind.PROXIMITY -> sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        SensorKind.LIGHT -> sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    }

    private companion object {

        const val BUFFER_CAPACITY = 256

        const val NANOS_PER_MILLI = 1_000_000L

        const val WAKE_LOCK_TAG = "ottomatic:sensors"

        // No timer of any kind lives here. A subscriber that needs to notice
        // time passing while an on-change sensor stays quiet asks for it with
        // `republishWhileSettling`, which knows how long it actually needs and
        // stops once that has elapsed.

        /** How long to sample after significant motion before going quiet again. */
        const val MOTION_WINDOW_MS = 8_000L

        const val SIGNIFICANT_MOTION_TYPE = "significant_motion"
        const val SIGNIFICANT_MOTION_EVENT = "moved"

        // Must match the payload keys the engine-side triggers read.
        const val KEY_TRIGGER_TYPE = "triggerType"
        const val KEY_EVENT = "event"
        const val KEY_TIMESTAMP = "timestamp"

        val KIND_BY_SENSOR_TYPE: Map<Int, SensorKind> = mapOf(
            Sensor.TYPE_ACCELEROMETER to SensorKind.ACCELEROMETER,
            Sensor.TYPE_PROXIMITY to SensorKind.PROXIMITY,
            Sensor.TYPE_LIGHT to SensorKind.LIGHT,
        )
    }
}
