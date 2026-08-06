package com.example.ottomatic.feature.macro

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ottomatic.domain.model.MacroAccent
import com.example.ottomatic.domain.model.MacroIcon
import com.example.ottomatic.feature.geofence.darkFieldColors
import com.example.ottomatic.feature.grapheditor.EditorColors

/**
 * The one dialog for "what is this macro called and what does it look like".
 *
 * It replaces the two rename dialogs that used to mirror each other by hand — the
 * workflow list's and `EditorTopBar`'s — because there are now three things to
 * edit and keeping two copies of a three-field form in step is exactly the drift
 * that comment was already worrying about.
 *
 * Name, icon and colour are one dialog rather than a rename plus an appearance
 * sheet because they answer one question. Nobody sets out to "change the icon";
 * they set out to make a macro recognisable, and its name is half of that.
 */
@Composable
fun EditMacroDialog(
    initialName: String,
    initialIcon: MacroIcon,
    initialAccent: MacroAccent,
    onConfirm: (name: String, icon: MacroIcon, accent: MacroAccent) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var icon by remember { mutableStateOf(initialIcon) }
    var accent by remember { mutableStateOf(initialAccent) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EditorColors.chrome,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The chip is the preview. A grid of swatches tells you what you
                // picked; only this tells you what it will look like put together,
                // and it is the same composable the list row draws.
                MacroIconChip(icon = icon, accent = accent)
                Spacer(Modifier.size(12.dp))
                Text("Edit macro", color = EditorColors.textPrimary, fontSize = 18.sp)
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                    // The geofence editor's field colours, not this dialog's own:
                    // the app is forced dark, so Material's defaults resolve
                    // against a light scheme and hand back the baseline purple for
                    // the focus ring, the label and the cursor.
                    colors = darkFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                SectionLabel("Icon")
                IconGrid(selected = icon, accent = accent, onSelect = { icon = it })
                SectionLabel("Colour")
                AccentRow(selected = accent, onSelect = { accent = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), icon, accent) },
                colors = editorTextButtonColors(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = editorTextButtonColors()) { Text("Cancel") }
        },
    )
}

/**
 * Dialog buttons in the app's own accent rather than Material's baseline purple.
 *
 * The app runs `OttomaticTheme(darkTheme = true, dynamicColor = false)`, whose
 * scheme is still the Android Studio template's — so every unstyled Material
 * control resolves to that template's purple, which appears nowhere else in the
 * app. Every other control here is coloured explicitly for the same reason
 * (`editorSwitchColors`, `darkFieldColors`); this is the button-shaped one.
 */
@Composable
fun editorTextButtonColors(): ButtonColors =
    ButtonDefaults.textButtonColors(contentColor = EditorColors.actionAccent)

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = EditorColors.textSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

/**
 * Every icon at once, in a fixed six-column grid.
 *
 * Bounded by [GRID_MAX_HEIGHT] and scrollable rather than sized to its content, so
 * adding a [MacroIcon] later cannot push the Save button off a short screen — the
 * failure a dialog that grows with its content eventually hits, silently, on
 * somebody else's phone.
 */
@Composable
private fun IconGrid(selected: MacroIcon, accent: MacroAccent, onSelect: (MacroIcon) -> Unit) {
    val color = accent.inAppColor()
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        modifier = Modifier.heightIn(max = GRID_MAX_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(MacroIcon.entries) { entry ->
            val isSelected = entry == selected
            // The outer Box is what makes the selection highlight square.
            // `LazyVerticalGrid` measures each item at a *fixed* cell width, so a
            // `size()` on the item itself cannot make it narrower than the cell —
            // the highlight stretched into a lozenge instead. Centring a
            // fixed-size child inside a full-width cell is the shape that survives.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(CELL_SIZE)
                        .background(
                            if (isSelected) color.copy(alpha = SELECTED_FILL_ALPHA) else Color.Transparent,
                            RoundedCornerShape(12.dp),
                        )
                        .clickable { onSelect(entry) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(entry.drawableRes()),
                        contentDescription = entry.name,
                        tint = if (isSelected) color else EditorColors.textSecondary,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
        }
    }
}

/**
 * The accent swatches, [MacroAccent.SYSTEM] first.
 *
 * SYSTEM leads because it is the default and because it is the only one that means
 * something different on a widget than in here — "take the wallpaper's colour" —
 * so it earns the position where a user looks first rather than being filed at the
 * end of a list of hues as if it were a tenth colour.
 */
@Composable
private fun AccentRow(selected: MacroAccent, onSelect: (MacroAccent) -> Unit) {
    // A grid rather than the single Row the name suggests: nine 28dp swatches plus
    // their gaps overflow a dialog's width on a narrow phone, and a colour picker
    // that clips its last two colours is worse than one that wraps.
    LazyVerticalGrid(
        columns = GridCells.Fixed(ACCENT_COLUMNS),
        modifier = Modifier.height(ACCENT_GRID_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(MacroAccent.entries) { entry ->
            val color = entry.inAppColor()
            // Centred inside the cell for the reason the icon grid is; without it
            // the selection ring is drawn round a cell-wide box and comes out an
            // oval rather than a circle.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(SWATCH_OUTER)
                        .border(
                            width = if (entry == selected) 2.dp else 0.dp,
                            color = if (entry == selected) EditorColors.textPrimary else Color.Transparent,
                            shape = CircleShape,
                        )
                        .clickable { onSelect(entry) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(SWATCH_INNER)
                            .background(color, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        // SYSTEM has no colour of its own, so the swatch says so
                        // with the letter rather than by showing a blue that is not
                        // the promise being made.
                        if (entry == MacroAccent.SYSTEM) {
                            Text(
                                text = "A",
                                color = EditorColors.canvasBackground,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val GRID_COLUMNS = 6
private const val ACCENT_COLUMNS = 5
private const val SELECTED_FILL_ALPHA = 0.2f
private val GRID_MAX_HEIGHT = 180.dp
private val CELL_SIZE = 40.dp
private val SWATCH_OUTER = 28.dp
private val SWATCH_INNER = 20.dp
private val ACCENT_GRID_HEIGHT = 62.dp
