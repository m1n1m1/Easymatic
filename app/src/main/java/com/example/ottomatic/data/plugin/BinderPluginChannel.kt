package com.example.ottomatic.data.plugin

import android.os.RemoteException
import android.util.Log
import com.example.ottomatic.nodeapi.plugin.PluginChannel
import com.example.ottomatic.plugin.ITriggerCallback
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one Android implementation of [PluginChannel].
 *
 * Everything above this — the four bridges, the registry, the timeouts, the log replay
 * — talks to the interface instead, which is what makes all of it testable on the JVM
 * with a hand-rolled fake. That is not a stylistic preference: `android.os.Binder` and
 * every generated AIDL stub are `android.jar` stubs that throw `Stub!` under plain
 * JUnit, so without this seam the failures most worth testing — a timeout, a dead
 * process, a malformed reply — would each need a device and a second installed APK.
 *
 * Its whole job is three things. Get onto [io] and stay off the main thread, because a
 * binder call blocks until the far side answers and the far side is third-party code.
 * Turn a `RemoteException` or a dead binder into a null, because a plugin that has
 * gone away is an expected condition the callers already degrade on. And nothing else:
 * the timeouts live in `PluginNodeRunner` so a test can provoke them.
 */
class BinderPluginChannel(
    override val packageName: String,
    private val connections: PluginConnections,
    /** Where a fire-and-forget disarm runs; see [disarmTrigger]. */
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PluginChannel {

    override suspend fun declarations(): String? = call { it.declarations() }

    override suspend fun status(): String? = call { it.status() }

    override suspend fun choices(typeId: String, source: String, request: String): String? =
        call { it.choices(typeId, source, request) }

    override suspend fun runAction(typeId: String, request: String): String? =
        call { it.runAction(typeId, request) }

    override suspend fun readValue(typeId: String, request: String): String? =
        call { it.readValue(typeId, request) }

    override suspend fun runTransform(typeId: String, request: String): String? =
        call { it.runTransform(typeId, request) }

    override suspend fun armTrigger(
        typeId: String,
        armId: String,
        request: String,
        onFired: (String) -> Unit,
        onStopped: (String) -> Unit,
    ): Boolean {
        val callback = object : ITriggerCallback.Stub() {
            override fun onFired(firedArmId: String, eventJson: String) {
                // Guarded because this arrives on a binder thread from another app: an
                // exception here would be thrown on a thread nobody is watching.
                runCatching { if (firedArmId == armId) onFired(eventJson) }
                    .onFailure { Log.e(TAG, "Delivering an event from $packageName failed", it) }
            }

            override fun onStopped(stoppedArmId: String, reason: String) {
                runCatching { if (stoppedArmId == armId) onStopped(reason) }
                    .onFailure { Log.e(TAG, "Delivering a stop from $packageName failed", it) }
            }
        }
        return call { plugin ->
            plugin.armTrigger(typeId, armId, request, callback)
            true
        } ?: false
    }

    override fun disarmTrigger(armId: String) {
        // Launched rather than awaited: the caller is a `callbackFlow`'s `awaitClose`,
        // which runs during cancellation and may not suspend. `scope` outlives the arm
        // for exactly this reason.
        scope.launch { call<Unit> { it.disarmTrigger(armId) } }
    }

    private suspend fun <T> call(body: (com.example.ottomatic.plugin.IOttomaticPlugin) -> T): T? =
        withContext(io) {
            val plugin = connections.binder(packageName) ?: return@withContext null
            try {
                body(plugin)
            } catch (e: RemoteException) {
                // The plugin's process died mid-call. Expected, not exceptional: the
                // caller reports it naming the plugin and the macro carries on.
                Log.w(TAG, "$packageName went away mid-call", e)
                null
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                // A binder transaction can also surface a TransactionTooLargeException
                // or a DeadObjectException, both of which are RuntimeExceptions and
                // neither of which should reach the executor as a crash.
                Log.w(TAG, "Calling $packageName failed", e)
                null
            }
        }

    private companion object {
        const val TAG = "PluginChannel"
    }
}
