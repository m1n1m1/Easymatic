package com.example.ottomatic.core.service

/**
 * The read side of the device: what is true *right now*.
 *
 * [SystemServices] is deliberately write-only — every one of its members changes
 * something. Conditions need the opposite, and the two do not belong in one
 * facade: the setters need permissions and can fail loudly, whereas a reader is
 * cheap, side-effect free and safe to call on every trigger event.
 *
 * Every method returns null when the value cannot be read (subsystem absent,
 * permission not granted), mirroring the nullable-on-failure convention of the
 * `set*` calls. A condition reading null evaluates false and logs rather than
 * failing the run — an unknowable state is not a passing one.
 *
 * Implemented by `AndroidDeviceState` in `data/`.
 */
@Suppress("TooManyFunctions") // One reader per device property; the device sets the count.
interface DeviceState {

    /** Whether Wi-Fi is enabled. */
    fun isWifiEnabled(): Boolean?

    /**
     * The name of the Wi-Fi network currently joined, or null when the device is not
     * on Wi-Fi **or** the platform would not disclose the name.
     *
     * The two collapse into one answer on purpose: naming a network needs
     * `ACCESS_FINE_LOCATION`, and without it the platform reports a placeholder
     * rather than an error. Null for both keeps the "an unknowable state is not a
     * passing one" rule that every other reader here follows — the node that
     * *declares* the grant is where the missing permission gets said out loud.
     */
    fun currentWifiNetwork(): String?

    /**
     * Whether the NFC radio is switched on, or null when this phone has no NFC
     * chip at all.
     *
     * The two are worth telling apart here even though most callers will not:
     * "off" is one tap away from being on, and "absent" never will be. A
     * comparison sees false either way, which is the right answer to "is NFC on?"
     * in both cases.
     */
    fun isNfcEnabled(): Boolean?

    /** Whether Bluetooth is enabled. */
    fun isBluetoothEnabled(): Boolean?

    /** Whether airplane mode is on. */
    fun isAirplaneMode(): Boolean?

    /** Whether the device is charging (AC, USB or wireless). */
    fun isCharging(): Boolean?

    /** Battery charge as a percentage in 0..100. */
    fun batteryLevel(): Int?

    /** Whether the screen is on and interactive. */
    fun isScreenOn(): Boolean?

    /** Whether Do-Not-Disturb is active at any level. */
    fun isDndEnabled(): Boolean?

    /** The current ringer mode. */
    fun ringerMode(): RingerMode?

    /** Whether battery saver is on. */
    fun isPowerSaveMode(): Boolean?

    /** Whether a wired headset or headphones are plugged in. */
    fun isHeadsetPlugged(): Boolean?

    /** Whether the device is sitting in a dock. */
    fun isDocked(): Boolean?

    /** Whether the device is in night mode (dark theme). */
    fun isNightMode(): Boolean?

    /**
     * Whether the camera torch is lit, or null when this phone has no flash unit
     * and when the state has not been observed yet.
     *
     * The platform offers no synchronous getter, so the Android implementation
     * keeps a cache warmed by `CameraManager.registerTorchCallback` — the same
     * shape `value.ha_state` uses, and what makes this read cheap and repeatable
     * enough to sit on the pull side. It follows the torch wherever it was lit
     * from, so a quick-settings tile and [SystemServices.setTorch] are read back
     * the same way.
     */
    fun isTorchOn(): Boolean?
}

/**
 * A [DeviceState] that knows nothing, for environments with no device behind it
 * (engine-only unit tests, previews). Every read is null, so conditions built on
 * it evaluate false rather than inventing a state that was never observed.
 */
@Suppress("TooManyFunctions") // Mirrors the DeviceState facade.
object UnknownDeviceState : DeviceState {
    override fun isWifiEnabled(): Boolean? = null
    override fun currentWifiNetwork(): String? = null
    override fun isNfcEnabled(): Boolean? = null
    override fun isBluetoothEnabled(): Boolean? = null
    override fun isAirplaneMode(): Boolean? = null
    override fun isCharging(): Boolean? = null
    override fun batteryLevel(): Int? = null
    override fun isScreenOn(): Boolean? = null
    override fun isDndEnabled(): Boolean? = null
    override fun ringerMode(): RingerMode? = null
    override fun isPowerSaveMode(): Boolean? = null
    override fun isHeadsetPlugged(): Boolean? = null
    override fun isDocked(): Boolean? = null
    override fun isNightMode(): Boolean? = null
    override fun isTorchOn(): Boolean? = null
}
