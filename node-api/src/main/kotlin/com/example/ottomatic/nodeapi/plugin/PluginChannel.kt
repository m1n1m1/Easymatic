package com.example.ottomatic.nodeapi.plugin

/**
 * A way of talking to one plugin, in strings.
 *
 * ## Why this exists rather than the binder interface
 *
 * The obvious design has the four node bridges hold an `IOttomaticPlugin` directly.
 * It does not survive contact with the test suite: `android.os.Binder` and every
 * AIDL stub are `android.jar` stubs that throw `RuntimeException("Stub!")` under
 * plain JUnit, so every interesting test — a null read, a timeout, an undeclared
 * route, a dead plugin — would need a device and an installed second APK to run.
 * The failures worth testing are exactly the ones hardest to provoke on a device.
 *
 * So the bridges take this, `BinderPluginChannel` is its one Android implementation,
 * and a hand-rolled fake is the other. That is the seam `SystemServices` already is,
 * placed at the new boundary for the same reason.
 *
 * ## Strings, not typed DTOs
 *
 * The payloads are JSON documents rather than the `…Wire` classes themselves,
 * because this interface is the shape of the AIDL, and AIDL carries `String`. Making
 * it typed here would put the encode/decode on the far side of the fake, where the
 * tests could no longer reach the thing most likely to be wrong.
 *
 * ## Contract
 *
 * Every method may fail, and none may throw for an *expected* failure. A plugin
 * that is not installed, not bound, has died, or has taken too long is a null or an
 * error result, because the executor's own wrapper would otherwise log
 * "Action … failed" with nothing in it naming which plugin. `CancellationException`
 * is the one exception that must propagate.
 */
interface PluginChannel {

    /** The package this channel speaks to. Used to derive the typeId prefix. */
    val packageName: String

    /** The plugin's `PluginManifestWire` JSON, or null when it cannot be reached. */
    suspend fun declarations(): String?

    /** Runs an action. [request] is a `NodeCallWire`; the answer is an `ActionResultWire`. */
    suspend fun runAction(typeId: String, request: String): String?

    /** Reads a value. [request] is a `NodeCallWire`; the answer is a `ValueResultWire`. */
    suspend fun readValue(typeId: String, request: String): String?

    /** Runs a transform. [request] is a `NodeCallWire`; the answer is a `ValueResultWire`. */
    suspend fun runTransform(typeId: String, request: String): String?

    /**
     * Arms a trigger under [armId], which the host owns and the plugin only echoes.
     *
     * [onFired] receives a `TriggerEventWire` JSON per firing; [onStopped] is called
     * once when the trigger will not fire again — hardware absent, a permission the
     * plugin itself was refused — and is the difference between a trigger that is
     * quiet and one that is dead.
     *
     * Returns false when arming did not happen, so the caller can report it rather
     * than sit waiting for a subscription that was never made.
     */
    suspend fun armTrigger(
        typeId: String,
        armId: String,
        request: String,
        onFired: (String) -> Unit,
        onStopped: (String) -> Unit,
    ): Boolean

    /**
     * Releases [armId]. Must be safe to call for an armId that is already gone.
     *
     * Deliberately **not** suspending, and this is forced rather than chosen: the one
     * caller is a `callbackFlow`'s `awaitClose`, which runs during cancellation where
     * nothing may suspend. So it is fire-and-forget — the implementation dispatches
     * the binder call onto its own scope and does not wait for it.
     *
     * Nothing is lost by not waiting. A disarm that never lands is cleaned up by the
     * plugin's own service when the binding drops, and the host re-arms rather than
     * resumes, so a stale registration on the far side is replaced rather than
     * duplicated.
     */
    fun disarmTrigger(armId: String)
}
