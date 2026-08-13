package com.example.ottomatic.feature.apps

import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.ottomatic.data.apps.InstalledApp
import com.example.ottomatic.data.apps.InstalledApps
import com.example.ottomatic.data.apps.appLabel
import com.example.ottomatic.feature.grapheditor.EditorColors
import com.example.ottomatic.feature.grapheditor.EditorOverlay
import com.example.ottomatic.feature.grapheditor.PickerFieldChrome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ROW_SHAPE = RoundedCornerShape(16.dp)

/**
 * A `@Picker(APP)` or `@Picker(APP_FILTER)` field: shows the chosen app's name and
 * opens the list of installed apps on tap.
 *
 * [launchableOnly] restricts the list to apps that have a launcher activity, which
 * is what `action.launch_app` needs — offering a package it could never open is
 * offering a guaranteed failure. [allowAny] adds the row that clears the field,
 * which is what the two *filter* fields need, since "any app" is a valid answer to
 * them and blank is how they say it.
 */
@Composable
fun AppPickerField(
    value: String,
    onValueChange: (String) -> Unit,
    launchableOnly: Boolean,
    allowAny: Boolean,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }

    // Naming one app is a single package lookup rather than an enumeration, so the
    // field never waits on the list — but it still touches the resource system, so
    // it stays off the composition thread the way a sound's name does.
    val display by produceState(initialValue = "", value, context) {
        this.value = withContext(Dispatchers.IO) { appLabel(context, value) }
    }

    PickerFieldChrome(
        display = display,
        icon = Icons.Filled.Apps,
        enabled = true,
        onTap = { picking = true },
        labelSlot = labelSlot,
        colors = colors,
    )

    if (picking) {
        AppPickerOverlay(
            selected = value.takeIf { it.isNotBlank() },
            launchableOnly = launchableOnly,
            allowAny = allowAny,
            onPick = {
                onValueChange(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * The installed-app chooser.
 *
 * Follows [com.example.ottomatic.feature.geofence.GeofencePlacePickerOverlay]'s
 * deferred-pick idiom: the tap records the choice and closes, and [onPick] runs only
 * once the exit animation has finished, so the overlay is never torn out from under
 * its own transition.
 *
 * The one thing it does differently from every other picker is **load
 * asynchronously**. `GeofencePlaceRepository` reads its file synchronously in its
 * constructor precisely so a picker never renders an empty frame; there is no cheap
 * synchronous read to be had here, so a spinner on first open is the honest answer.
 * Afterwards [InstalledApps] has it cached for the process.
 */
@Composable
fun AppPickerOverlay(
    selected: String?,
    launchableOnly: Boolean,
    allowAny: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var picked by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val apps by produceState<List<InstalledApp>?>(initialValue = null, context) {
        value = InstalledApps.all(context)
    }

    EditorOverlay(
        title = stringResource(R.string.apps_choose_an_app),
        onClose = { picked?.let(onPick) ?: onDismiss() },
    ) { dismiss ->
        Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.apps_search)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val loaded = apps
            if (loaded == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = EditorColors.triggerAccent)
                }
                return@Column
            }
            AppList(
                apps = loaded.matching(query, launchableOnly),
                selected = selected,
                allowAny = allowAny,
                splitOther = !launchableOnly,
                onPick = {
                    picked = it
                    dismiss()
                },
            )
        }
    }
}

/**
 * Filtering, on **label and package name** both. Searching by package is how you
 * find `com.whatsapp` when you cannot remember what its icon is called, and it is
 * also the only way to tell two apps with the same label apart.
 */
private fun List<InstalledApp>.matching(query: String, launchableOnly: Boolean): List<InstalledApp> {
    val trimmed = query.trim()
    return filter { app ->
        (!launchableOnly || app.launchable) &&
            (
                trimmed.isEmpty() ||
                    app.label.contains(trimmed, ignoreCase = true) ||
                    app.packageName.contains(trimmed, ignoreCase = true)
                )
    }
}

@Composable
private fun AppList(
    apps: List<InstalledApp>,
    selected: String?,
    allowAny: Boolean,
    splitOther: Boolean,
    onPick: (String) -> Unit,
) {
    // Launchable apps first when the list holds both: a filter that buried Spotify
    // under forty vendor services would be a list nobody can use, even though those
    // services are exactly what the filter sometimes wants.
    val launchable = if (splitOther) apps.filter { it.launchable } else apps
    val other = if (splitOther) apps.filterNot { it.launchable } else emptyList()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (allowAny) {
            item(key = "any") { AnyAppRow(selected = selected == null, onClick = { onPick("") }) }
        }
        items(launchable, key = { it.packageName }) { app ->
            AppRow(app = app, selected = app.packageName == selected, onClick = { onPick(app.packageName) })
        }
        if (other.isNotEmpty()) {
            item(key = "other-header") { SectionHeader(stringResource(R.string.apps_other_apps)) }
            items(other, key = { it.packageName }) { app ->
                AppRow(app = app, selected = app.packageName == selected, onClick = { onPick(app.packageName) })
            }
        }
        if (apps.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.apps_no_apps_match_that),
                    style = MaterialTheme.typography.bodyMedium,
                    color = EditorColors.textSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = EditorColors.textSecondary,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun AnyAppRow(selected: Boolean, onClick: () -> Unit) {
    val accent = EditorColors.triggerAccent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(accent.copy(alpha = 0.08f))
            .border(if (selected) 2.dp else 1.dp, accent.copy(alpha = 0.35f), ROW_SHAPE)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Close, contentDescription = null, tint = accent)
        Text(
            text = stringResource(R.string.apps_any_app),
            style = MaterialTheme.typography.bodyLarge,
            color = accent,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun AppRow(app: InstalledApp, selected: Boolean, onClick: () -> Unit) {
    val accent = EditorColors.triggerAccent
    val borderColor = if (selected) accent else EditorColors.nodeBorder
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(EditorColors.nodeBackground)
            .border(if (selected) 2.dp else 1.dp, borderColor, ROW_SHAPE)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(packageName = app.packageName)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                color = EditorColors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One app's icon, decoded as its row is composed.
 *
 * A `LazyColumn` composes only what is on screen, so this loads a handful of icons
 * rather than every installed app's — which is the difference between a list that
 * opens instantly and one that decodes four hundred drawables first. A package with
 * no readable icon falls back to the generic glyph rather than to a hole.
 */
@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val accent = EditorColors.triggerAccent
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName, context) {
        value = withContext(Dispatchers.IO) { loadIcon(context, packageName) }
    }
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = icon
        if (bitmap == null) {
            Icon(
                imageVector = Icons.Filled.Android,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        } else {
            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

private fun loadIcon(context: Context, packageName: String): ImageBitmap? = runCatching {
    context.packageManager.getApplicationIcon(packageName).toBitmap().asImageBitmap()
}.getOrNull()
