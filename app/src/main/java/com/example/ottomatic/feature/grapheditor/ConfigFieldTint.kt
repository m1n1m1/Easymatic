package com.example.ottomatic.feature.grapheditor

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Recolors a config field to say that its value comes from an edge rather than
 * from the form: the frame lights up in the port's type color while the value
 * itself is muted. Built by [wiredFieldTint]; `null` means the default palette.
 */
internal class ConfigFieldTint(
    val outline: Color,
    val label: Color,
    val text: Color,
)

/** The tint for a field being fed by a live edge of the given port color. */
internal fun wiredFieldTint(portColor: Color) = ConfigFieldTint(
    outline = portColor,
    label = portColor,
    text = EditorColors.textSecondary,
)

/** The [ConfigFieldTint] as overrides on the default text field palette. */
@Composable
internal fun ConfigFieldTint?.asTextFieldColors(): TextFieldColors {
    val defaults = OutlinedTextFieldDefaults.colors()
    return if (this == null) {
        defaults
    } else {
        defaults.copy(
            focusedIndicatorColor = outline,
            unfocusedIndicatorColor = outline,
            focusedLabelColor = label,
            unfocusedLabelColor = label,
            focusedTextColor = text,
            unfocusedTextColor = text,
        )
    }
}
