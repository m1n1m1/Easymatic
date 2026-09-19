package io.github.m1n1m1.easymatic.domain.model

/**
 * Version-dependent limitations declared by a node and shown before it runs.
 * Unlike a missing capability, a warning may describe partially supported behaviour.
 * SDK numbers are data here; Android and translated messages belong to the host UI.
 */
@Suppress("MagicNumber") // Android SDK compatibility boundaries, kept together as a table.
enum class PlatformWarning(
    private val fromSdk: Int = 0,
    private val throughSdk: Int = Int.MAX_VALUE,
    private val fromTargetSdk: Int = 0,
    val radioRestriction: Boolean = false,
) {
    WIFI_TOGGLE(fromSdk = 29, fromTargetSdk = 29, radioRestriction = true),
    BLUETOOTH_TOGGLE(fromSdk = 33, fromTargetSdk = 33, radioRestriction = true),
    DND_GLOBAL_CONTROL(fromSdk = 35, fromTargetSdk = 35),
    SCREENSHOT_UNAVAILABLE(throughSdk = 29),
    PICTURE_BIN_UNAVAILABLE(throughSdk = 29),
    ;

    fun appliesTo(sdk: Int, targetSdk: Int, radioControlExempt: Boolean = false): Boolean =
        sdk in fromSdk..throughSdk && targetSdk >= fromTargetSdk &&
            !(radioRestriction && radioControlExempt)
}
