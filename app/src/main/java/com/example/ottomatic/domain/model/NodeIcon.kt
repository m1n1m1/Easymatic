package com.example.ottomatic.domain.model

/**
 * Platform-agnostic icon for a node type. The UI layer maps every entry to a
 * concrete vector asset with an exhaustive `when`
 * (`feature/grapheditor/nodeIcon`), so an icon cannot be referenced by a node
 * without also being drawn — the previous string keys silently fell back to a
 * generic placeholder when misspelled.
 */
enum class NodeIcon {
    BOLT,
    SPLIT,
    TIMER,
    SCHEDULE,
    NOTIFICATION,
    SMS,
    SEND,
    HTTP,
    WIFI,
    BLUETOOTH,
    VOLUME,
    DND,
    LOCATION,
    BOOT,
    BATTERY_LEVEL,
    BATTERY_CHARGING,
}
