package com.example.ottomatic.plugin

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.example.ottomatic.nodeapi.plugin.PluginLimits
import com.example.ottomatic.nodeapi.wire.ChoiceListWire
import com.example.ottomatic.nodeapi.wire.LogLevelWire
import com.example.ottomatic.nodeapi.wire.LogLineWire
import com.example.ottomatic.nodeapi.wire.NodeCallWire
import com.example.ottomatic.nodeapi.wire.PLUGIN_PROTOCOL_VERSION
import com.example.ottomatic.nodeapi.wire.PluginJson
import com.example.ottomatic.nodeapi.wire.PluginManifestWire
import com.example.ottomatic.nodeapi.wire.PluginStatusWire
import com.example.ottomatic.nodeapi.wire.TriggerEventWire
import com.example.ottomatic.nodeapi.wire.ValueResultWire
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * The service a plugin app exports. Subclass it, point it at your nodes, done.
 *
 * ```kotlin
 * class MyPluginService : BaseOttomaticPluginService() {
 *     override val nodes = listOf(ShoutAction(), LoudnessValue(), ShortenTransform())
 * }
 * ```
 *
 * and in the manifest:
 *
 * ```xml
 * <service android:name=".MyPluginService" android:exported="true"
 *          android:permission="com.example.ottomatic.permission.BIND_PLUGIN">
 *     <intent-filter><action android:name="com.example.ottomatic.action.PLUGIN" /></intent-filter>
 * </service>
 * ```
 *
 * ## What it does with them
 *
 * It indexes [nodes] by their short typeId, prefixes each with **its own package
 * name** when it publishes the manifest, and strips that prefix again off every
 * incoming call. That is why a node declares `"shout"` rather than
 * `"plugin:com.acme.tools/shout"`: the namespacing the host's whole collision
 * argument rests on is derived on both sides rather than typed on either, so it
 * cannot be got wrong, and a plugin that is renamed keeps working.
 *
 * ## Threading
 *
 * Every binder method here runs on a binder thread and blocks it. That is correct and
 * deliberate: the host calls all of them off its own main thread under a timeout
 * chosen per method, and a plugin that takes too long is a plugin the host gives up
 * on rather than one that hangs anybody. `runBlocking` bridges a suspending node body
 * onto that thread; a node that wants to be quick should simply be quick.
 *
 * ## Failure
 *
 * Nothing here may throw across the binder. A node that throws is caught, reported as
 * a log line on the result, and answered with a null value or an unpulsed action —
 * because an exception crossing a binder arrives on the host as a
 * `DeadObjectException` or a `RuntimeException` with no plugin name in it, and the
 * one thing the host most needs to be able to say is *which plugin* went wrong.
 */
@Suppress("TooManyFunctions") // One per binder transaction, plus the marshalling either side.
abstract class BaseOttomaticPluginService : Service() {

    /**
     * Every node this plugin contributes.
     *
     * Each must be a [PluginAction], [PluginTrigger], [PluginValue] or
     * [PluginTransform]; anything else is ignored with a log line, because a silent
     * omission here shows up as a node missing from the palette with nothing anywhere
     * saying why.
     */
    abstract val nodes: List<Any>

    /**
     * Whether this plugin can currently do its work — override when it needs an account.
     *
     * The question no permission check reaches. A plugin holds every permission it asked
     * for, so Ottomatic's Problems panel is silent and correct while every node of a
     * signed-out plugin does nothing: "configured perfectly, does nothing", with nothing
     * anywhere saying why.
     *
     * Answer `PluginStatusWire(ready = false, message = "…")` and Ottomatic raises that
     * sentence — **your words, untranslated, exactly as your node names are** — as a
     * warning on every placed node of yours. It blocks nothing: the graph is fine, and
     * signing in somewhere else starts it working with no edit to the macro at all.
     *
     * Called off any binder thread, on every refresh, under a two-second bound. Keep it
     * local: read a token out of your own preferences rather than validating it over the
     * network. A plugin that does not answer in time leaves Ottomatic saying nothing,
     * which is the deliberate choice over badging every node on a guess.
     */
    open fun status(): PluginStatusWire = PluginStatusWire()

    private val armed = ConcurrentHashMap<String, PluginArm>()

    private val byLocalId: Map<String, Any> by lazy { nodes.associateBy { it.localTypeId() } }

    private val manifestJson: String by lazy {
        // Reported here rather than only in an author's test, because a contract problem
        // is otherwise invisible from this side: the node loads, its field renders, and
        // its chooser is empty forever. Logged rather than thrown — a throw would reach
        // Ottomatic as a dead transaction it can only report as "could not be reached".
        PluginNodeContracts.problems(nodes).forEach { Log.e(TAG, it) }
        PluginJson.encodeToString(
            PluginManifestWire.serializer(),
            PluginManifestWire(
                protocolVersion = PLUGIN_PROTOCOL_VERSION,
                pluginName = applicationInfo.loadLabel(packageManager).toString(),
                nodes = nodes.mapNotNull { it.declarationOrNull() },
            ),
        )
    }

    final override fun onBind(intent: Intent?): IBinder = binder

    final override fun onDestroy() {
        // The host re-arms rather than resumes after a binding drops, so anything still
        // registered here would be a duplicate — and, for a trigger holding a sensor or
        // a receiver, a leak that outlives the reason it existed.
        armed.values.forEach { runCatching { it.disarm() } }
        armed.clear()
        super.onDestroy()
    }

    private val binder = object : IOttomaticPlugin.Stub() {

        override fun declarations(): String = manifestJson

        override fun status(): String = runCatching {
            PluginJson.encodeToString(PluginStatusWire.serializer(), this@BaseOttomaticPluginService.status())
        }.getOrElse { cause ->
            // A status check that throws is not evidence the plugin is broken — it is
            // evidence this one call went wrong — so it degrades to saying nothing rather
            // than to claiming not-ready, which would badge every node the plugin has.
            Log.e(TAG, "status() failed", cause)
            PluginJson.encodeToString(PluginStatusWire.serializer(), PluginStatusWire())
        }

        @Suppress("UNCHECKED_CAST", "ReturnCount") // Not-a-chooser, not-a-node, and answered.
        override fun choices(typeId: String, source: String, requestJson: String): String {
            val node = byLocalId[typeId.localPart()]
            val chooser = node as? PluginChoiceSource<Any>
                ?: return failedChoices("'$typeId' offers no choices")
            val definition = node.definitionOrNull() as? PluginNodeDefinition<Any, Any>
                ?: return failedChoices("'$typeId' is not a node this plugin declares")
            return runCatching {
                val context = RecordingPluginContext(applicationContext)
                val config = definition.decodeConfig(decodeCall(requestJson))
                val options = runBlocking { chooser.choices(source, config, context) }
                PluginJson.encodeToString(
                    ChoiceListWire.serializer(),
                    ChoiceListWire(options = options.take(PluginLimits.MAX_CHOICES)),
                )
            }.getOrElse { cause ->
                Log.e(TAG, "Choices for $typeId/$source failed", cause)
                failedChoices("could not be listed: ${cause.message ?: cause.javaClass.simpleName}")
            }
        }

        override fun runAction(typeId: String, requestJson: String): String {
            val node = byLocalId[typeId.localPart()]
            val call = decodeCall(requestJson)
            @Suppress("UNCHECKED_CAST")
            val action = node as? PluginAction<Any, Any>
                ?: return failedAction("'$typeId' is not an action this plugin declares")
            return runCatching {
                PluginJson.encodeToString(
                    com.example.ottomatic.nodeapi.wire.ActionResultWire.serializer(),
                    runBlocking { action.dispatch(call, applicationContext) },
                )
            }.getOrElse { cause ->
                Log.e(TAG, "Action $typeId failed", cause)
                failedAction("threw ${cause.javaClass.simpleName}: ${cause.message}")
            }
        }

        override fun readValue(typeId: String, requestJson: String): String {
            @Suppress("UNCHECKED_CAST")
            val value = byLocalId[typeId.localPart()] as? PluginValue<Any, Any>
                ?: return failedValue("'$typeId' is not a value this plugin declares")
            return dispatchValue(typeId) { value.dispatch(decodeCall(requestJson), applicationContext) }
        }

        override fun runTransform(typeId: String, requestJson: String): String {
            @Suppress("UNCHECKED_CAST")
            val transform = byLocalId[typeId.localPart()] as? PluginTransform<Any, Any>
                ?: return failedValue("'$typeId' is not a transform this plugin declares")
            return dispatchValue(typeId) { transform.dispatch(decodeCall(requestJson), applicationContext) }
        }

        override fun armTrigger(
            typeId: String,
            armId: String,
            requestJson: String,
            callback: ITriggerCallback,
        ) {
            @Suppress("UNCHECKED_CAST")
            val trigger = byLocalId[typeId.localPart()] as? PluginTrigger<Any, Any>
            if (trigger == null) {
                runCatching { callback.onStopped(armId, "'$typeId' is not a trigger this plugin declares") }
                return
            }
            // Replacing an armId that is somehow still live rather than stacking on it:
            // the host owns armIds and only reuses one after disarming, so a survivor
            // here is a leak from a dropped binding.
            armed.remove(armId)?.let { runCatching { it.disarm() } }
            val context = RecordingPluginContext(applicationContext)
            runCatching {
                val config = trigger.definition.decodeConfig(decodeCall(requestJson))
                armed[armId] = trigger.arm(config, context) { payload ->
                    val event = trigger.definition.encodeEvent(payload)
                    runCatching {
                        callback.onFired(armId, PluginJson.encodeToString(TriggerEventWire.serializer(), event))
                    }
                }
            }.onFailure { cause ->
                Log.e(TAG, "Arming $typeId failed", cause)
                runCatching { callback.onStopped(armId, "could not arm: ${cause.message}") }
            }
        }

        override fun disarmTrigger(armId: String) {
            armed.remove(armId)?.let { runCatching { it.disarm() } }
        }
    }

    private fun dispatchValue(typeId: String, body: suspend () -> ValueResultWire): String =
        runCatching { PluginJson.encodeToString(ValueResultWire.serializer(), runBlocking { body() }) }
            .getOrElse { cause ->
                Log.e(TAG, "Read $typeId failed", cause)
                failedValue("threw ${cause.javaClass.simpleName}: ${cause.message}")
            }

    private fun decodeCall(requestJson: String): NodeCallWire =
        runCatching { PluginJson.decodeFromString(NodeCallWire.serializer(), requestJson) }
            .getOrDefault(NodeCallWire())

    /** An action that did nothing, saying why. The host lands it on the run log. */
    private fun failedAction(reason: String): String = PluginJson.encodeToString(
        com.example.ottomatic.nodeapi.wire.ActionResultWire.serializer(),
        com.example.ottomatic.nodeapi.wire.ActionResultWire(
            log = listOf(LogLineWire(LogLevelWire.ERROR, reason)),
        ),
    )

    /** A read that answered nothing, saying why. The consumer falls back. */
    private fun failedValue(reason: String): String = PluginJson.encodeToString(
        ValueResultWire.serializer(),
        ValueResultWire(log = listOf(LogLineWire(LogLevelWire.ERROR, reason))),
    )

    /**
     * An empty chooser, saying why.
     *
     * The reason travels *in the reply* rather than as a log line, because this is the one
     * transaction answered while somebody is looking at a dialog waiting for it. A run-log
     * entry would be the right shape for a failure nobody is watching and the wrong one
     * here.
     */
    private fun failedChoices(reason: String): String =
        PluginJson.encodeToString(ChoiceListWire.serializer(), ChoiceListWire(problem = reason))

    private fun Any.localTypeId(): String = definitionOrNull()?.typeId ?: ""

    private fun Any.definitionOrNull(): PluginNodeDefinition<*, *>? = when (this) {
        is PluginAction<*, *> -> definition
        is PluginTrigger<*, *> -> definition
        is PluginValue<*, *> -> definition
        is PluginTransform<*, *> -> definition
        else -> null
    }

    private fun Any.declarationOrNull() = definitionOrNull()?.declaration(packageName)
        ?: run {
            Log.w(TAG, "${javaClass.name} is in `nodes` but is not a plugin node; ignoring it")
            null
        }

    /** `plugin:com.acme.tools/shout` -> `shout`; anything unprefixed is passed through. */
    private fun String.localPart(): String = substringAfterLast('/')

    private companion object {
        const val TAG = "OttomaticPlugin"
    }
}
