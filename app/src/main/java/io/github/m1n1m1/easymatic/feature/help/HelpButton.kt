package io.github.m1n1m1.easymatic.feature.help

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun HelpButton(onClick: () -> Unit) {
    val label = stringResource(R.string.help_open)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick) {
            Icon(Icons.AutoMirrored.Outlined.HelpOutline, label, tint = EditorColors.textPrimary)
        }
    }
}
