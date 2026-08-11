package com.example.ottomatic.plugin

import android.content.Context
import com.example.ottomatic.nodeapi.wire.LogLevelWire

/**
 * Everything a plugin node's body is given.
 *
 * It is deliberately two members, and the shorter list is the more important one.
 * There is no way from here to read a workflow variable, resolve a contact, control a
 * light, send mail, ask the user a question or reach any other Ottomatic facade —
 * because a plugin node runs in the plugin's own process under the plugin's own
 * manifest permissions, and lending it the host's would defeat the only real
 * safeguard the separate-APK design provides.
 *
 * That is not as restrictive as it sounds. Ottomatic's own nodes are given no more
 * than their decoded config either: `ExecutionContext`'s documented invariant is that
 * a node body never sees its own `WorkflowNode`, its config map, its workflow id or
 * its run id. A plugin node is held to the same contract, minus the facades.
 *
 * What it gets instead is [android] — the plugin's own application context — with
 * which it can do anything its own manifest permits. That is the point of shipping a
 * plugin as an app.
 */
interface PluginContext {

    /** The plugin's own application context. */
    val android: Context

    /**
     * Writes a line to the run log of the macro that is running this node, attributed
     * to the node on the canvas.
     *
     * Buffered and returned with the call's result rather than sent as it happens:
     * there is no back-channel during a call, and adding one would mean a second
     * binder interface for something that is never urgent. Bounded — see
     * `PluginLimits.MAX_LOG_LINES` — and the truncation is announced rather than
     * silent.
     */
    fun log(message: String, level: LogLevelWire = LogLevelWire.INFO)
}

/**
 * What an action answers with.
 *
 * [route] must name one of the node's declared execution outputs: `out` for a plain
 * action, or `true`/`false` for a branching one. A route the declaration does not
 * contain is refused rather than pulsed — the host `require`s the same of its own
 * nodes, and a plugin is not a reason to relax it.
 *
 * [halt] stops the branch, exactly as a first-party action's does.
 */
class PluginOutput<out O : Any>(
    val value: O,
    val route: String = "out",
    val halt: Boolean = false,
)

/** A trigger's registration, released when the host disarms or the binding drops. */
fun interface PluginArm {
    fun disarm()
}
