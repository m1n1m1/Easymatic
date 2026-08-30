package io.github.m1n1m1.easymatic.domain.model

import kotlinx.serialization.Serializable

/**
 * The glyph a macro shows for itself — on its row in the workflow list, on a
 * home-screen tile and on a pinned launcher shortcut.
 *
 * Closed, and mapped to a concrete asset by an exhaustive `when`
 * (`feature/widget/MacroIconRes`), for the reason [NodeIcon] is: a string key
 * silently falls back to a placeholder when misspelled, and here the placeholder
 * would be a home-screen icon that says nothing about what tapping it does.
 *
 * Unlike [NodeIcon] this maps to a **drawable resource** rather than a Compose
 * `ImageVector`, and that is not an accident. Neither Glance nor
 * `ShortcutInfoCompat` can consume an `ImageVector`, while the in-app picker
 * renders a drawable perfectly well with `painterResource` — so one mapping
 * serves all three surfaces instead of two that can drift apart.
 *
 * [BOLT] is the default because it is what `trigger.manual` already wears on the
 * canvas, so a macro that was never given an identity still looks like the thing
 * it is rather than like a missing asset.
 */
@Serializable
enum class MacroIcon {
    BOLT,
    FLASHLIGHT,
    VOLUME_OFF,
    VOLUME_UP,
    WIFI,
    BLUETOOTH,
    AIRPLANE,
    LOCATION,
    CAR,
    HOME,
    WORK,
    SUN,
    MOON,
    SLEEP,
    ALARM,
    TIMER,
    MUSIC,
    CAMERA,
    PHONE,
    MESSAGE,
    LOCK,
    BATTERY,
    COFFEE,
    STAR,
}
