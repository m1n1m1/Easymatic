package io.github.m1n1m1.easymatic.engine.value

import io.github.m1n1m1.easymatic.core.service.UnknownDeviceState
import io.github.m1n1m1.easymatic.domain.model.config.NoConfig
import io.github.m1n1m1.easymatic.engine.DefaultExecutionContext
import io.github.m1n1m1.easymatic.engine.RecordingSystemServices
import io.github.m1n1m1.easymatic.engine.trigger.SensorKind
import io.github.m1n1m1.easymatic.engine.trigger.SensorReader
import io.github.m1n1m1.easymatic.engine.trigger.SensorSample
import io.github.m1n1m1.easymatic.engine.trigger.gesture.DeviceOrientation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sensor value nodes: `value.light`, `value.proximity`, `value.orientation`.
 *
 * The point of these being *values* rather than triggers is that they can be
 * read at a moment of the graph's choosing, so what is asserted here is the
 * reading itself — including the null that stands for "the device cannot tell
 * you", which a comparison downstream turns into a false verdict rather than a
 * guess.
 */
class SensorValuesTest {

    @Test
    fun `the light value reports lux`() = runBlocking {
        val lux = LightLevelValue().read(NoConfig, contextReading(sample(NIGHT_LUX, 0f, 0f)))

        assertEquals(NIGHT_LUX, lux!!, 0f)
    }

    /** No sensor is not darkness: an absent reading must stay absent. */
    @Test
    fun `an unreadable sensor yields no value`() = runBlocking {
        assertNull(LightLevelValue().read(NoConfig, contextReading(null)))
        assertNull(ProximityValue().read(NoConfig, contextReading(null)))
        assertNull(DeviceOrientationValue().read(NoConfig, contextReading(null)))
    }

    /**
     * Covered is decided against the sensor's own range, which is 3 cm on some
     * devices and 100 cm on others — the same rule `trigger.proximity` applies,
     * because both call [io.github.m1n1m1.easymatic.engine.trigger.gesture.ProximityDetector.isCovered].
     */
    @Test
    fun `proximity is covered relative to the sensor range`() = runBlocking {
        val covered = ProximityValue().read(NoConfig, contextReading(sample(0f, 0f, 0f), range = 100f))
        val clear = ProximityValue().read(NoConfig, contextReading(sample(100f, 0f, 0f), range = 100f))

        assertTrue(covered!!)
        assertFalse(clear!!)
    }

    /** A device whose proximity range is unknown cannot say what "covered" means. */
    @Test
    fun `proximity without a range yields no value`() = runBlocking {
        val covered = ProximityValue().read(NoConfig, contextReading(sample(0f, 0f, 0f), range = null))

        assertNull(covered)
    }

    @Test
    fun `orientation classifies a resting device`() = runBlocking {
        val faceUp = DeviceOrientationValue().read(NoConfig, contextReading(sample(0f, 0f, G)))
        val faceDown = DeviceOrientationValue().read(NoConfig, contextReading(sample(0f, 0f, -G)))
        val portrait = DeviceOrientationValue().read(NoConfig, contextReading(sample(0f, G, 0f)))

        assertEquals(DeviceOrientation.FACE_UP, faceUp)
        assertEquals(DeviceOrientation.FACE_DOWN, faceDown)
        assertEquals(DeviceOrientation.PORTRAIT, portrait)
    }

    /**
     * A device in motion, or held at an angle no axis dominates, reads as
     * unknown rather than as whichever orientation is marginally ahead. The
     * trigger refuses to commit there too; disagreeing would mean
     * `value.orientation` claiming a position `trigger.device_orientation` never
     * reported.
     */
    @Test
    fun `an ambiguous angle yields no orientation`() = runBlocking {
        val diagonal = sample(G / 2, G / 2, G / 2)

        assertNull(DeviceOrientationValue().read(NoConfig, contextReading(diagonal)))
    }

    private fun sample(x: Float, y: Float, z: Float) = SensorSample(x, y, z, elapsedMs = 0L)

    /** A context whose sensors report [sample] for every kind. */
    private fun contextReading(sample: SensorSample?, range: Float? = 5f) = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        deviceState = UnknownDeviceState,
        sensors = object : SensorReader {
            override suspend fun latest(kind: SensorKind): SensorSample? = sample
            override fun maximumRange(kind: SensorKind): Float? = range
        },
    )

    private companion object {
        const val G = 9.81f
        const val NIGHT_LUX = 12f
    }
}
