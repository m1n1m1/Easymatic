package io.github.m1n1m1.easymatic.engine.trigger.gesture

/**
 * What a detector reports when it recognises a gesture.
 *
 * [event] is the discriminator carried to
 * [io.github.m1n1m1.easymatic.domain.model.items.SensorReading.event], and matches
 * the lowercased name of whatever enum the detector classifies with, so the
 * trigger's optional filter is an enum comparison rather than a second list of
 * strings to keep in sync.
 *
 * [value] carries the gesture's magnitude where it has one — peak acceleration
 * for a shake, lux for a light threshold — and stays at zero otherwise.
 */
data class GestureFire(
    val event: String,
    val value: Float = 0f,
    val detail: String = "",
)

/**
 * Standard gravity, in m/s². Every accelerometer threshold in this package is
 * expressed against it: the sensor reports roughly this magnitude at rest, so a
 * threshold of 12 means "about 1.2 g of *net* acceleration".
 */
internal const val GRAVITY = 9.81f
