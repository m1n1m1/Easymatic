package com.example.ottomatic.feature.grapheditor

import androidx.compose.foundation.MarqueeDefaults
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation

/**
 * A value too long for its field, scrolled rather than cut.
 *
 * The fields this is for hold **identifiers the user chose rather than typed** — a SAF document
 * path, a Home Assistant entity, a geofence place, a model id. Ellipsis is at its worst on
 * exactly those: they are long, they differ at the *end* (`sensor.hall_temperature` against
 * `sensor.hall_humidity`), and the head that survives the cut is the half they share. So the
 * field showed you the part that could not tell you which one you had picked.
 *
 * Scrolling rather than wrapping to two lines, because the answer is one value and not a
 * sentence: a wrapped path re-flows the whole form every time a different one is chosen, and a
 * row whose height depends on how deep a folder is nested is a form that will not sit still.
 *
 * It animates only while the text actually overflows and stops after
 * [MarqueeDefaults.Iterations] passes, so a field that fits costs nothing and one that does not
 * eventually settles instead of moving under the reader forever.
 */
fun Modifier.scrollingValue(): Modifier = basicMarquee()

/**
 * The chrome of a read-only [androidx.compose.material3.OutlinedTextField], around a value that
 * is a [Text] rather than an editable field.
 *
 * Built from [OutlinedTextFieldDefaults.DecorationBox] instead of an `OutlinedTextField`
 * because a text field's value is a `BasicTextField`, and a `BasicTextField` cannot be given
 * [scrollingValue] — a modifier that moves text has nothing to move when the text belongs to an
 * editor that scrolls itself under a cursor. Everything else is Material's own: the same
 * container, border, notch, focus colours and content padding, so this reads as the same widget
 * as every editable field beside it.
 *
 * There is nothing to focus and nothing to type, so [interactionSource] is a fresh empty one —
 * the border stays in its unfocused state, which is the truth about a field whose value is
 * chosen elsewhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadOnlyFieldChrome(
    value: String,
    colors: TextFieldColors,
    label: @Composable () -> Unit,
    placeholder: @Composable () -> Unit,
    trailingIcon: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    OutlinedTextFieldDefaults.DecorationBox(
        value = value,
        innerTextField = {
            // Placed by the decoration box, so it fills the slot the editor would have had.
            //
            // The colour is taken from [colors] rather than inherited. A real text field merges
            // its `TextFieldColors` into the inner editor's style; a plain [Text] does not, and
            // falls back to `LocalContentColor` — which is black, on a config sheet whose
            // palette is fixed dark regardless of the phone's theme. Unfocused because there is
            // nothing here to focus, and it is what a wired field's muted tint sets.
            Text(
                text = value,
                style = LocalTextStyle.current,
                color = colors.unfocusedTextColor,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .scrollingValue(),
            )
        },
        enabled = true,
        singleLine = true,
        visualTransformation = VisualTransformation.None,
        interactionSource = interactionSource,
        label = label,
        placeholder = placeholder,
        trailingIcon = trailingIcon,
        colors = colors,
        container = {
            OutlinedTextFieldDefaults.Container(
                enabled = true,
                isError = false,
                interactionSource = interactionSource,
                colors = colors,
            )
        },
    )
}
