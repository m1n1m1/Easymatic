package io.github.m1n1m1.easymatic.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.domain.model.ToolOverrides
import io.github.m1n1m1.easymatic.domain.model.ToolSpec
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors

/**
 * A `@Tools` field: how this node differs from its model profile about what the AI may
 * do.
 *
 * **The row is a summary and the page is the profile's own**, which is what "reuse the
 * permission page" means here rather than approximately: the node opens
 * [ToolPermissionsOverlay] with its profile as the baseline, ticks and pins exactly as
 * it would on the profile, and only the *writing* differs — what comes back is the
 * difference rather than the list. The user never learns there are two modes.
 *
 * The summary says the effective count and, separately, how much of it is this node's
 * doing. Both are needed: the first is what the model will be sent, the second is the
 * thing that will *stop following the profile* when somebody edits it later, and a
 * single number would hide whichever it did not show.
 *
 * **A profile that cannot be resolved leaves the row disabled rather than empty.** With
 * no model chosen there is nothing to adjust *against*, and offering the page anyway
 * would let somebody build a set of overrides that silently became a whole tool list
 * the moment a profile was picked.
 */
@Composable
internal fun ToolOverrideField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modelRef: String,
) {
    val connections = LocalAiConnections.current
    var editing by remember { mutableStateOf(false) }

    // Collected so the row follows a profile edited in another screen while this form
    // is open — the same reason the picker fields observe their libraries.
    val state = connections?.uiState?.collectAsState()
    val profileTools = remember(state?.value?.connections, modelRef) {
        connections?.modelProfile(modelRef)?.tools.orEmpty()
    }
    val ready = connections != null && modelRef.isNotBlank() && connections.modelProfile(modelRef) != null

    val baseline = remember(profileTools) { ToolSpec.parse(profileTools) }
    val overrides = remember(value) { ToolOverrides.parse(value) }
    val effective = remember(baseline, overrides) { overrides.applyTo(baseline) }

    // The callable macros are read when the page is opened, for the reason the profile
    // editor reads them there: it is a file read per macro and most visits never open
    // it. `editModel` is the profile editor's own trigger; this is the node's.
    LaunchedEffect(editing) { if (editing) connections?.loadCallableMacros() }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label, color = EditorColors.textPrimary, fontSize = 14.sp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(EditorColors.nodeBackground)
                .clickable(enabled = ready) { editing = true }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        !ready -> stringResource(R.string.ai_tools_choose_a_model_first)
                        effective.isEmpty() -> stringResource(R.string.ai_tools_none_allowed)
                        else -> stringResource(R.string.ai_tools_n_allowed, effective.size)
                    },
                    color = if (ready) EditorColors.textPrimary else EditorColors.textSecondary,
                    fontSize = 14.sp,
                )
                if (ready) {
                    Text(
                        text = if (overrides.isEmpty) {
                            stringResource(R.string.ai_tools_as_the_model_says)
                        } else {
                            stringResource(
                                R.string.ai_tools_adjusted_count,
                                overrides.replaced.size + overrides.removed.size,
                            )
                        },
                        color = if (overrides.isEmpty) EditorColors.textSecondary else EditorColors.warnAccent,
                        fontSize = 12.sp,
                    )
                }
            }
            if (ready) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = EditorColors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }

    if (editing && connections != null) {
        ToolPermissionsOverlay(
            tools = value,
            macros = state?.value?.callableMacros.orEmpty(),
            onChange = onValueChange,
            onClose = { editing = false },
            baseline = baseline,
        )
    }
}
