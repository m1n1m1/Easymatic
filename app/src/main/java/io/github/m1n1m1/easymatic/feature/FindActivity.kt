package io.github.m1n1m1.easymatic.feature

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * The [Activity] hosting this [Context], or null when there is none.
 *
 * The first thing in the app that needs one, and it needs one because a handful of
 * platform APIs are bound to an Activity rather than a Context — `enableReaderMode`
 * among them, which is how the tag chooser reads a tag without the manifest
 * dispatch also firing a macro.
 *
 * Written by hand rather than taken from `LocalActivity`, which arrived in
 * activity-compose 1.10 and this build pins 1.8. And the unwrapping is not
 * ceremony: inside an `EditorOverlay` — a Compose `Dialog`, which hosts its content
 * in a `ContextThemeWrapper` — `LocalContext.current as? Activity` is **null**,
 * which is exactly where the tag chooser lives.
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
