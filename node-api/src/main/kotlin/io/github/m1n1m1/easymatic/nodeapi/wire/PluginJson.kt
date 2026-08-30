package io.github.m1n1m1.easymatic.nodeapi.wire

import kotlinx.serialization.json.Json

/**
 * The one codec for everything crossing the plugin boundary, used by both sides.
 *
 * JSON strings rather than `Parcelable`s, and that is a forward-compatibility
 * decision rather than a convenience. A `Parcelable` is read back by field order, so
 * a plugin built against protocol 1 talking to a host at protocol 2 does not fail —
 * it reads a shifted field and carries on with plausible nonsense. `ignoreUnknownKeys`
 * makes the same skew a no-op instead, which is the trade the persisted workflow
 * files under `{filesDir}/workflows` already make for the same reason.
 *
 * `encodeDefaults` is on so that every field is present on the wire even when it
 * holds its default. Without it a plugin's `route` would simply be absent from a
 * result, and "absent" and "the default" being the same thing is fine right up until
 * the default changes.
 */
val PluginJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = false
}
