package com.example.ottomatic.domain.model

import com.example.ottomatic.domain.model.config.ValueType
import kotlinx.serialization.Serializable

/**
 * One named slot of the graph's writable state, declared before anything may use
 * it.
 *
 * A variable used to exist the moment some node wrote a name into it, which meant
 * a typo produced a second variable that silently read as unset and nothing in the
 * app could list what a macro actually remembered. Declaring it first turns both of
 * those into visible facts: the picker offers only what exists, and a reference to
 * something deleted is a warning in the Problems panel rather than an invisible
 * no-op.
 *
 * **Keyed by [id], exactly as [GeofencePlace] is, and for the same reason**: a
 * rename must not be a propagation. Everything that refers to a variable — a node's
 * config, the store's key, the change trigger's filter — carries the id, so [name]
 * is free to change at any time without touching another file. The cost is that the
 * id is unreadable, which is why every log line and every trigger payload resolves
 * the declaration and prints *this* field instead.
 *
 * [type] is the ordinary [ValueType], not a new enum. It already names the five
 * families a user can choose between, is already what `transform.convert` and a
 * `@Ports` row offer, and already has a **total** `convert` — which is what lets
 * `value.variable` hand back a typed item without a conversion node in the wire. It
 * does not make the store typed: what is *stored* is still flat text, and the
 * declared type governs only what may be *connected*.
 *
 * [initialValue] is what an unset variable reads as, and what a [constant] holds
 * always. A constant is never written to the store at all, so it cannot drift out
 * of agreement with its own declaration.
 */
@Serializable
data class VariableDeclaration(
    val id: String,
    val name: String,
    val type: ValueType = ValueType.TEXT,
    val initialValue: String = "",
    val constant: Boolean = false,
    val description: String = "",
)
