package io.github.m1n1m1.easymatic.engine.plugin

import io.github.m1n1m1.easymatic.nodeapi.plugin.PluginChannel
import kotlinx.coroutines.delay

/**
 * A plugin that never leaves this process.
 *
 * Hand-rolled rather than mocked, per the house style, and it is the whole reason
 * `PluginChannel` exists as an interface: `android.os.Binder` and every generated AIDL
 * stub throw `Stub!` under plain JUnit, so without this seam none of the interesting
 * failures — a timeout, a dead plugin, a reply that will not parse, a route the node
 * never declared — could be provoked without a device and a second installed APK.
 */
@Suppress("LongParameterList") // One canned reply per transaction; every one has a default.
class FakePluginChannel(
    override val packageName: String = "com.acme.tools",
    private var actionReply: String? = null,
    private var valueReply: String? = null,
    private var transformReply: String? = null,
    private var statusReply: String? = null,
    private var choicesReply: String? = null,
    /** Simulates a plugin that is slow, so the runner's timeouts can be exercised. */
    private var delayMs: Long = 0,
) : PluginChannel {

    var actionCalls: Int = 0
        private set
    var valueCalls: Int = 0
        private set
    var choiceCalls: Int = 0
        private set
    var lastRequest: String? = null
        private set

    /** The `source` argument of the last `choices` call — what the field asked for. */
    var lastChoiceSource: String? = null
        private set

    fun answersAction(json: String?) = apply { actionReply = json }
    fun answersValue(json: String?) = apply { valueReply = json }
    fun answersTransform(json: String?) = apply { transformReply = json }
    fun answersStatus(json: String?) = apply { statusReply = json }
    fun answersChoices(json: String?) = apply { choicesReply = json }
    fun takes(millis: Long) = apply { delayMs = millis }

    override suspend fun declarations(): String? = null

    override suspend fun status(): String? {
        if (delayMs > 0) delay(delayMs)
        return statusReply
    }

    override suspend fun choices(typeId: String, source: String, request: String): String? {
        choiceCalls++
        lastChoiceSource = source
        lastRequest = request
        if (delayMs > 0) delay(delayMs)
        return choicesReply
    }

    override suspend fun runAction(typeId: String, request: String): String? {
        actionCalls++
        lastRequest = request
        if (delayMs > 0) delay(delayMs)
        return actionReply
    }

    override suspend fun readValue(typeId: String, request: String): String? {
        valueCalls++
        lastRequest = request
        if (delayMs > 0) delay(delayMs)
        return valueReply
    }

    override suspend fun runTransform(typeId: String, request: String): String? {
        lastRequest = request
        if (delayMs > 0) delay(delayMs)
        return transformReply
    }

    override suspend fun armTrigger(
        typeId: String,
        armId: String,
        request: String,
        onFired: (String) -> Unit,
        onStopped: (String) -> Unit,
    ): Boolean = false

    override fun disarmTrigger(armId: String) = Unit
}
