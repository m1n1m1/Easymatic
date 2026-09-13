package io.github.m1n1m1.easymatic.feature.workflowlist

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import kotlinx.coroutines.launch

/**
 * The app bar: launcher icon, app name, and the search action.
 *
 * It is Material's [TopAppBar] with only the colours overridden, for the reason every bar
 * in this app overrides only colours — the palette is fixed dark and independent of
 * `MaterialTheme`, so the defaults would render light under it. It consumes the status
 * bar inset itself, as `SettingsTopBar` does, because it is the topmost thing here.
 *
 * The search action reports its own coordinates to the [SearchBarState]. That is what
 * `TopSearchBar` did for the collapsed pill this bar replaces, and it is the whole
 * contract the full-screen surface has with its collapsed half:
 * [ExpandedFullScreenSearchBar] reads `collapsedCoords` to know where to grow from and
 * where to shrink back to. Without it the surface would spring from the window's corner.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun WorkflowListTopBar(searchBarState: SearchBarState) {
    val scope = rememberCoroutineScope()
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        navigationIcon = {
            AppIcon(
                modifier = Modifier
                    .padding(start = 16.dp, end = 4.dp)
                    .size(32.dp),
            )
        },
        actions = {
            IconButton(
                onClick = { scope.launch { searchBarState.animateToExpanded() } },
                modifier = Modifier.onGloballyPositioned { searchBarState.collapsedCoords = it },
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = stringResource(R.string.workflowlist_search),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = EditorColors.chrome,
            titleContentColor = EditorColors.textPrimary,
            navigationIconContentColor = EditorColors.textPrimary,
            actionIconContentColor = EditorColors.textPrimary,
        ),
    )
}

/**
 * The launcher icon, drawn from its two adaptive layers.
 *
 * `painterResource` cannot load `@mipmap/ic_launcher`: it resolves to an
 * `AdaptiveIconDrawable`, which the painter refuses. The two layers it is built from are
 * ordinary vectors, so this stacks them the way a launcher does — background under
 * foreground, both scaled so the central 72dp a mask keeps fills the circle and the safe
 * margin around it is cropped away. `scale` draws past the layout bounds; the clip trims it.
 */
@Composable
private fun AppIcon(modifier: Modifier = Modifier) {
    Box(modifier = modifier.clip(CircleShape)) {
        val layer = Modifier
            .matchParentSize()
            .scale(ADAPTIVE_ICON_CANVAS_SCALE)
        Image(
            painter = painterResource(R.drawable.ic_launcher_background),
            contentDescription = null,
            modifier = layer,
        )
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = layer,
        )
    }
}

/** An adaptive icon's canvas is 108dp, of which a launcher mask shows the central 72dp. */
private const val ADAPTIVE_ICON_CANVAS_SCALE = 108f / 72f

/**
 * The query the list is currently narrowed to, shown once the search has collapsed.
 *
 * A filter that is still applied but no longer visible reads as macros gone missing, so
 * this chip stands in for the pill the old collapsed bar was. Tapping it reopens the
 * search with the query still in the field; the ✕ drops the filter without opening it.
 */
@Composable
internal fun ActiveFilterChip(
    query: String,
    onClear: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(EditorColors.chrome)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        InputChip(
            selected = true,
            onClick = onOpenSearch,
            label = { Text(query, maxLines = 1) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(InputChipDefaults.IconSize),
                )
            },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.workflowlist_clear_search),
                    modifier = Modifier
                        .size(InputChipDefaults.IconSize)
                        .clickable(onClick = onClear),
                )
            },
            colors = InputChipDefaults.inputChipColors(
                selectedContainerColor = EditorColors.nodeBackground,
                selectedLabelColor = EditorColors.textPrimary,
                selectedLeadingIconColor = EditorColors.textSecondary,
                selectedTrailingIconColor = EditorColors.textSecondary,
            ),
            border = InputChipDefaults.inputChipBorder(
                enabled = true,
                selected = true,
                selectedBorderColor = EditorColors.chromeBorder,
                selectedBorderWidth = 1.dp,
            ),
        )
    }
}
