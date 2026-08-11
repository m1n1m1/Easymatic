package com.example.ottomatic.plugin;

/**
 * How a plugin tells the host that one of its triggers fired.
 *
 * `oneway` is load-bearing rather than an optimisation. A blocking callback would let
 * a host busy walking a long macro stall the plugin's own event source, and would let
 * a slow plugin occupy one of the host's sixteen binder threads for as long as it
 * liked. Neither side can afford to wait on the other here: an event is a
 * notification, not a request.
 */
oneway interface ITriggerCallback {

    /**
     * One firing, as a TriggerEventWire JSON document.
     *
     * armId echoes back what the host passed to armTrigger, because a plugin may hold
     * several arms of the same trigger at once — the same macro enabled twice, or one
     * trigger armed from inside a loop.
     */
    void onFired(String armId, String eventJson);

    /**
     * This trigger will not fire again: the hardware is absent, a permission the
     * plugin itself needs was refused, or the source it watches has closed.
     *
     * The difference between a trigger that is quiet and one that is dead is
     * invisible from the outside, and a trigger that silently stopped firing is the
     * failure mode this whole integration is most likely to have. Say so.
     */
    void onStopped(String armId, String reason);
}
