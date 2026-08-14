package com.example.ottomatic.engine.trigger

/**
 * What `TriggerHost.armHomeAssistantWatch` needs in order to route events to a node.
 *
 * The filters a user typed are deliberately **not** here, on [MailWatchSpec]'s
 * reasoning: `toState`, `fromState` and "fire on attribute-only changes" are predicates
 * the trigger applies per event, so editing one takes effect with no re-arm signal to
 * invent. What is here is what decides the *subscription* and the *routing table* —
 * which hub, and which entity or event type — because those cannot be decided per event
 * without delivering every event to every node first.
 */
sealed interface HaWatchSpec {

    /** Which hub this watch is on. */
    val hubId: String

    /** One entity's state changes. */
    data class StateWatch(override val hubId: String, val entityId: String) : HaWatchSpec

    /**
     * One named event type on the hub's event bus.
     *
     * The event type is part of the spec rather than a filter because it is what the
     * subscription itself names: subscribing to everything and discarding would deliver
     * hundreds of frames a minute to a process that wanted three of them.
     */
    data class EventWatch(override val hubId: String, val eventType: String) : HaWatchSpec
}

/**
 * The payload keys a Home Assistant event carries.
 *
 * Declared once and read from both ends — the `data/` side that fills them and the
 * triggers that read them — on [MailPayload]'s shape and for its reason: a key spelled
 * two ways compiles perfectly and delivers nothing.
 *
 * [ATTRIBUTES] and [EVENT_DATA] are **JSON text**, which is forced by
 * `TriggerEvent.payload` being `Map<String, String>` and is also the right answer: the
 * node exposes them as Text, and `transform.json_read` walks them with `brightness` or
 * `data.command`. That is `action.http`'s road and the rule `ApiInputs` already states —
 * structure arrives as JSON and is read with a transform.
 */
object HaPayload {
    const val HUB_ID = "hubId"
    const val ENTITY_ID = "entityId"
    const val FRIENDLY_NAME = "friendlyName"
    const val STATE = "state"
    const val PREVIOUS_STATE = "previousState"
    const val UNIT = "unit"
    const val ATTRIBUTES = "attributes"
    const val EVENT_TYPE = "eventType"
    const val EVENT_DATA = "data"
    const val ORIGIN = "origin"
    const val CHANGED_AT = "changedAt"
}
