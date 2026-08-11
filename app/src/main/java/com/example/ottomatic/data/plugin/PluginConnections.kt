package com.example.ottomatic.data.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.example.ottomatic.plugin.IOttomaticPlugin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** The action a plugin app's service advertises. */
const val PLUGIN_SERVICE_ACTION = "com.example.ottomatic.action.PLUGIN"

/**
 * One live binding per plugin package.
 *
 * ## Never `BIND_IMPORTANT`
 *
 * `BIND_AUTO_CREATE` and nothing else. `BIND_IMPORTANT` and `BIND_ABOVE_CLIENT` both
 * lend the *host's* process importance to the bound one — which, for third-party code
 * bound by a foreground service, means handing an arbitrary app the scheduling
 * priority the user granted Ottomatic. A plugin gets an ordinary bound-service
 * lifetime, and if Android kills it under memory pressure that is the correct outcome.
 *
 * ## Kept warm, deliberately
 *
 * A binding is made on first use and held until the plugin is disabled or uninstalled.
 * That is not an optimisation for its own sake: a plugin *value* is pulled on the
 * execution path, once per consuming node, and a cold bind costs a process spawn of
 * 100–300 ms where a warm binder transaction costs well under a millisecond. A macro
 * reading a plugin value every five minutes would otherwise pay a process start every
 * time.
 *
 * There is deliberately **no lease counting and no idle unbind**. A lease scheme was
 * sketched and dropped: the leases would have to be taken by the arming path and
 * released by teardown, in a lifetime that already has three owners (`WorkflowRunner`'s
 * arm scope, the editor's composition, and an in-flight call), and getting one release
 * wrong leaks a bound service *silently and permanently* — the same failure the scheme
 * exists to prevent. Holding the binding instead costs an idle bound service per
 * enabled plugin, which Android may reclaim under memory pressure and which
 * `onConnectionChanged` then recovers from by re-arming. If that cost ever bites, the
 * thing to add is an idle unbind on a timer, which needs no correctness argument at
 * all — not leases.
 *
 * ## Waiting for a bind is bounded, and failing to get one is a result
 *
 * [binder] answers null rather than throwing or waiting indefinitely. The callers all
 * degrade on null — an action reports and pulses on, a read answers null and its
 * consumer falls back — which is the existing contract reached by a new route.
 */
class PluginConnections(private val context: Context) {

    private val mutex = Mutex()
    private val connections = mutableMapOf<String, Connection>()

    /** The live binder for [packageName], binding if necessary, or null. */
    suspend fun binder(packageName: String): IOttomaticPlugin? = mutex.withLock {
        connectionFor(packageName)
    }?.await()

    /** Drops the binding to [packageName] outright — on uninstall, or a forced disable. */
    suspend fun disconnect(packageName: String) {
        val connection = mutex.withLock { connections.remove(packageName) } ?: return
        connection.unbind()
    }

    /** Drops every binding. */
    suspend fun disconnectAll() {
        val all = mutex.withLock { connections.values.toList().also { connections.clear() } }
        all.forEach { it.unbind() }
    }

    /** Registers a listener told whenever a plugin's process dies or reconnects. */
    fun onConnectionChanged(listener: (packageName: String, connected: Boolean) -> Unit) {
        listeners += listener
    }

    private val listeners = mutableListOf<(String, Boolean) -> Unit>()

    /** Must be called holding [mutex]. */
    private fun connectionFor(packageName: String): Connection? =
        connections[packageName] ?: bind(packageName)?.also { connections[packageName] = it }

    /** Binds to [packageName], or null when the system would not let us. */
    private fun bind(packageName: String): Connection? {
        val connection = Connection(packageName)
        val intent = Intent(PLUGIN_SERVICE_ACTION).setPackage(packageName)
        val bound = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (bound) return connection
        // bindService can return false *after* registering the connection, so it has to
        // be unbound even on the failure path or the reference leaks for the process's
        // lifetime.
        Log.w(TAG, "Could not bind to $packageName")
        runCatching { context.unbindService(connection) }
        return null
    }

    private inner class Connection(private val packageName: String) : ServiceConnection {

        private var pending = CompletableDeferred<IOttomaticPlugin?>()
        private var binder: IOttomaticPlugin? = null

        suspend fun await(): IOttomaticPlugin? =
            binder ?: withTimeoutOrNull(BIND_TIMEOUT_MS) { pending.await() }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val stub = service?.let { IOttomaticPlugin.Stub.asInterface(it) }
            binder = stub
            if (!pending.isCompleted) pending.complete(stub)
            // Every death recipient the host needs is this callback pair; a separate
            // linkToDeath would fire for the same events and race this one.
            listeners.forEach { runCatching { it(packageName, true) } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            // A fresh promise, so a caller arriving during the gap waits for the
            // reconnection rather than being handed the completed null of the last one.
            pending = CompletableDeferred()
            listeners.forEach { runCatching { it(packageName, false) } }
        }

        override fun onBindingDied(name: ComponentName?) {
            onServiceDisconnected(name)
        }

        override fun onNullBinding(name: ComponentName?) {
            binder = null
            if (!pending.isCompleted) pending.complete(null)
        }

        fun unbind() {
            binder = null
            if (!pending.isCompleted) pending.complete(null)
            runCatching { context.unbindService(this) }
        }
    }

    companion object {
        private const val TAG = "PluginConnections"

        /** How long a caller waits for a cold process to start before giving up. */
        const val BIND_TIMEOUT_MS = 5_000L
    }
}
