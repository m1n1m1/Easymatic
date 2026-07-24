package com.example.ottomatic.data.trigger

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter

/**
 * Process-wide store for named string variables. Backs the
 * [com.example.ottomatic.engine.trigger.VariableChangeTrigger] by emitting a
 * [TriggerEvent] (source [TriggerSource.VARIABLE]) whenever a variable's value
 * changes via [set].
 *
 * Variables are kept in memory only — persistence is a later-tier concern.
 */
object VariableStore {

    private val values = mutableMapOf<String, String>()

    private val _changes = MutableSharedFlow<TriggerEvent>(
        replay = 0,
        extraBufferCapacity = DEFAULT_BUFFER,
    )

    /** Flow of all variable-change events. Filter by `name` payload. */
    val changes: SharedFlow<TriggerEvent> = _changes.asSharedFlow()

    /** Returns the current value for [name], or empty string if unset. */
    fun get(name: String): String = values[name].orEmpty()

    /**
     * Sets [name] to [value]. If the value differs from the current one, a
     * [TriggerEvent] is emitted onto [changes].
     */
    fun set(name: String, value: String) {
        val previous = values[name]
        if (previous == value) return
        values[name] = value
        _changes.tryEmit(
            TriggerEvent(
                source = TriggerSource.VARIABLE,
                triggerNodeId = NodeId.BROADCAST,
                payload = mapOf(
                    "name" to name,
                    "value" to value,
                    "timestamp" to System.currentTimeMillis().toString(),
                ),
            ),
        )
    }

    /** Stream of change events filtered to [name] (used by the host). */
    fun changesFor(name: String): Flow<TriggerEvent> =
        _changes.asSharedFlow().filter { it.payload[KEY_NAME] == name }

    private const val KEY_NAME = "name"
    private const val DEFAULT_BUFFER = 64
}
