package com.example.ottomatic.feature.widget

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
import com.example.ottomatic.MainActivity
import com.example.ottomatic.R

/**
 * The outer card every widget draws on, and the tap that opens the app.
 *
 * Shared so the three widgets cannot end up with different corner radii or
 * different ideas of what "the background" is — the kind of drift that is invisible
 * in isolation and obvious the moment two of them sit side by side on a home
 * screen.
 */

/** The widget's own background: the system's surface colour, rounded. */
@Composable
fun GlanceModifier.widgetSurface(): GlanceModifier = this.background(
    ImageProvider(R.drawable.widget_surface),
    colorFilter = ColorFilter.tint(GlanceTheme.colors.widgetBackground),
)

/**
 * Opens the app.
 *
 * `SINGLE_TOP` and no `CLEAR_TOP`, matching `MacroEngineService.openAppIntent()`
 * and for the same reason recorded there: the whole app is one Activity holding a
 * Compose back stack, so clearing the top would tear it down and rebuild it —
 * throwing the user out of the editor they had open instead of returning them to
 * it.
 */
fun GlanceModifier.clickableToOpenApp(): GlanceModifier = this.clickable(
    actionStartActivity(
        Intent().apply {
            setClassName(APP_PACKAGE, MainActivity::class.java.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
    ),
)

private const val APP_PACKAGE = "com.example.ottomatic"
