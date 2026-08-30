package io.github.m1n1m1.easymatic.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.ServiceLocator
import io.github.m1n1m1.easymatic.core.permissions.Permissions
import io.github.m1n1m1.easymatic.core.service.CalendarInfo
import io.github.m1n1m1.easymatic.domain.model.CalendarRef
import io.github.m1n1m1.easymatic.domain.registry.CalendarDirectory
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorOverlay
import io.github.m1n1m1.easymatic.feature.grapheditor.PickerFieldChrome
import io.github.m1n1m1.easymatic.feature.permissions.rememberPermissionState

private val ROW_SHAPE = RoundedCornerShape(16.dp)

/**
 * A `@Picker(CALENDAR)` or `@Picker(CALENDAR_FILTER)` field: shows the chosen calendar's
 * name and opens the list of calendars on this phone on tap.
 *
 * [forFilter] is what separates the two kinds. A filter offers an "Any calendar" row and
 * lists read-only calendars too — watching the company holiday feed is exactly what one
 * is for. A target offers neither: "any calendar" is not a thing to add an appointment
 * to, and a calendar that refuses writes would be a guaranteed failure to offer.
 *
 * **The field itself never waits on anything**, unlike
 * [AppPickerField][io.github.m1n1m1.easymatic.feature.apps.AppPickerField], which resolves a
 * package name off the composition thread. That is the whole point of
 * [CalendarRef] carrying the display name inside the spec: rendering "Work" here must not
 * depend on `READ_CALENDAR` having been granted, because a config form is exactly where
 * somebody is standing *before* they grant it.
 */
@Composable
fun CalendarPickerField(
    value: String,
    onValueChange: (String) -> Unit,
    forFilter: Boolean,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    var picking by remember { mutableStateOf(false) }

    PickerFieldChrome(
        // A spec that will not parse shows its own raw text rather than "None selected",
        // because that is the case where seeing the string is the whole diagnosis.
        display = if (value.isBlank()) "" else CalendarRef.parse(value)?.displayName ?: value,
        icon = Icons.Filled.CalendarMonth,
        enabled = true,
        onTap = { picking = true },
        labelSlot = labelSlot,
        placeholder = if (forFilter) stringResource(R.string.calendar_any_calendar) else null,
        colors = colors,
    )

    if (picking) {
        CalendarPickerOverlay(
            selected = value.takeIf { it.isNotBlank() },
            allowAny = forFilter,
            writableOnly = !forFilter,
            onPick = {
                onValueChange(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * The calendar chooser.
 *
 * Follows [io.github.m1n1m1.easymatic.feature.apps.AppPickerOverlay]'s shape — the deferred
 * pick, the spinner on first open, `produceState` rather than a ViewModel. No
 * `CompositionLocal` and no ViewModel, on the rule those exist for: a picker gets one so
 * it can *edit* its library and see its own in-flight edits, and a calendar is not
 * editable from here. It is made in the phone's calendar app, and Easymatic never writes
 * a calendar row in its life.
 *
 * **The third state is what no other picker in the app has**: not granted. The installed
 * apps sit behind an install-time permission, so `AppPickerOverlay` has only *loading* and
 * *loaded*. Here a denied `READ_CALENDAR` would render "there are no calendars on this
 * phone" — a lie, and one the user cannot act on from where they are standing. So the
 * overlay says what is missing and offers the prompt.
 */
@Composable
fun CalendarPickerOverlay(
    selected: String?,
    allowAny: Boolean,
    writableOnly: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf<String?>(null) }
    val permission = rememberPermissionState(listOf(Permissions.READ_CALENDAR))
    val granted = permission.allGranted

    val calendars by produceState<List<CalendarInfo>?>(initialValue = null, granted) {
        value = if (!granted) emptyList() else ServiceLocator.calendars.calendars().calendars
    }

    // Publishing what the chooser just read is what lets the validator and PickerOptions
    // answer at all: nothing else in the app enumerates calendars, so this is where the
    // directory is filled. Only ever with a real read — an ungranted one would publish an
    // empty list, and `isHydrated` exists precisely so that is not mistaken for "none".
    val loaded = calendars
    LaunchedEffect(loaded, granted) {
        if (granted && loaded != null) CalendarDirectory.hydrate(loaded)
    }

    EditorOverlay(
        title = stringResource(R.string.calendar_choose_a_calendar),
        onClose = { picked?.let(onPick) ?: onDismiss() },
    ) { dismiss ->
        Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            when {
                !granted -> AccessNotice(onGrant = permission::request)
                loaded == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = EditorColors.triggerAccent)
                }

                else -> CalendarList(
                    calendars = loaded.filter { !writableOnly || it.writable },
                    selected = selected,
                    allowAny = allowAny,
                    writableOnly = writableOnly,
                    onPick = {
                        picked = it
                        dismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun AccessNotice(onGrant: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.calendar_needs_access),
            style = MaterialTheme.typography.bodyMedium,
            color = EditorColors.textSecondary,
        )
        Button(onClick = onGrant) { Text(stringResource(R.string.calendar_grant_access)) }
    }
}

@Composable
private fun CalendarList(
    calendars: List<CalendarInfo>,
    selected: String?,
    allowAny: Boolean,
    writableOnly: Boolean,
    onPick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (allowAny) {
            item(key = "any") {
                CalendarRow(
                    title = stringResource(R.string.calendar_any_calendar),
                    subtitle = "",
                    selected = selected == null,
                    onClick = { onPick("") },
                )
            }
        }
        items(calendars, key = { it.ref }) { calendar ->
            CalendarRow(
                title = calendar.name.ifBlank { calendar.accountName },
                subtitle = calendar.accountName,
                selected = calendar.ref == selected,
                onClick = { onPick(calendar.ref) },
            )
        }
        if (calendars.isEmpty()) {
            item(key = "empty") {
                Text(
                    // Two sentences rather than one, because "no calendars" and "none you
                    // can write to" send somebody to look at two different things.
                    text = stringResource(
                        if (writableOnly) R.string.calendar_no_writable_calendars else R.string.calendar_no_calendars,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = EditorColors.textSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun CalendarRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(EditorColors.nodeBackground)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) EditorColors.triggerAccent else EditorColors.nodeBorder,
                shape = ROW_SHAPE,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = EditorColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank() && subtitle != title) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
