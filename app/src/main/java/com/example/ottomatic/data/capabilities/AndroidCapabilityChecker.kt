package com.example.ottomatic.data.capabilities

import android.content.Context
import android.content.pm.PackageManager
import com.example.ottomatic.core.capabilities.CapabilityChecker
import com.example.ottomatic.core.capabilities.CapabilityStatus
import com.example.ottomatic.core.capabilities.DeviceCapability
import com.example.ottomatic.data.accessibility.OttomaticAccessibilityService

/**
 * Android implementation of [CapabilityChecker].
 *
 * The sibling of `AndroidPermissionChecker`, and shaped by one thing that has no
 * counterpart there: some of these questions can only be asked of a *bound* service,
 * so "cannot tell yet" is a real answer rather than a failure to look. See
 * [CapabilityStatus].
 */
class AndroidCapabilityChecker(private val context: Context) : CapabilityChecker {

    override fun status(capability: DeviceCapability): CapabilityStatus = when (capability) {
        DeviceCapability.FINGERPRINT_GESTURES -> fingerprintGestureStatus()
    }

    /**
     * A guard chain ordered by which rung reaches a definitive answer soonest.
     *
     * The feature check catches only the easy case — a phone with no reader at all —
     * and is deliberately *not* the answer on its own: most phones ship a reader,
     * report the feature, and still deliver no gestures, because the platform only
     * reports swipes from a physical sensor whose driver tracks them. In-display
     * optical and ultrasonic readers generally do not. `isGestureDetectionAvailable`
     * is the question that separates those, and only a connected service can ask it.
     *
     * So an unbound service is [CapabilityStatus.UNKNOWN] rather than unavailable.
     * Reporting unavailable there would badge the node on every phone until the user
     * granted accessibility access — including the phones where it works — and the
     * node already carries a prerequisite warning saying that access is missing.
     */
    @Suppress("ReturnCount") // A guard chain, and each exit is a different answer.
    private fun fingerprintGestureStatus(): CapabilityStatus {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
            return CapabilityStatus.UNAVAILABLE
        }
        val controller = OttomaticAccessibilityService.instance?.fingerprintGestureController
            ?: return CapabilityStatus.UNKNOWN
        return if (controller.isGestureDetectionAvailable) {
            CapabilityStatus.AVAILABLE
        } else {
            CapabilityStatus.UNAVAILABLE
        }
    }
}
