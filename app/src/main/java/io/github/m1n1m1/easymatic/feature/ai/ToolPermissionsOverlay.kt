package io.github.m1n1m1.easymatic.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.core.service.CallableMacro
import io.github.m1n1m1.easymatic.domain.model.ToolOverrides
import io.github.m1n1m1.easymatic.domain.model.ToolSpec
import io.github.m1n1m1.easymatic.domain.model.ToolTarget
import io.github.m1n1m1.easymatic.domain.registry.NodeTypeRegistry
import io.github.m1n1m1.easymatic.domain.registry.PluginNodes
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorOverlay
import io.github.m1n1m1.easymatic.feature.i18n.labelRes
import io.github.m1n1m1.easymatic.feature.i18n.rememberNodeText

/**
 * What one model profile is allowed to do.
 *
 * **A checklist over everything, not a list of what was added**, and that is what the
 * request was actually about: turning every tool on or off has to be one gesture, and
 * a list you build by adding rows one at a time cannot offer that at any level. So the
 * whole candidate set is on screen, grouped by the palette's own categories, with a
 * switch per group and one above them all.
 *
 * **The permissions live on the profile rather than on each node**, which is the other
 * half. Four macros sharing one assistant used to keep four copies of one permission
 * list, which drifted; now the answer is given once, where the model is described.
 *
 * The rows still open the pin form, unchanged: an opaque identifier is an author
 * decision, so "allow everything" can leave some rows badged as needing a visit. That
 * is stated on the rows rather than enforced by the switch, because refusing to tick
 * a row until it is configured would make the one-tap gesture impossible.
 *
 * Skinned from `EditorColors` like the rest of these screens, and a full-screen
 * [EditorOverlay] rather than something app-themed, because the pin form embeds
 * `ConfigFieldEditor` — forking a second copy of every config widget to match a
 * different palette is a cost with nothing on the other side of it.
 */
@Composable
internal fun ToolPermissionsOverlay(
    tools: String,
    macros: List<CallableMacro>,
    onChange: (String) -> Unit,
    onClose: () -> Unit,
    baseline: List<ToolSpec>? = null,
) {
    val text = rememberNodeText()
    // Keyed on the plugin set for the palette's own reason: `NodeTypeRegistry.all` is
    // the compiled registries plus whichever plugins are enabled right now, and
    // Compose cannot observe a plain function call.
    val plugins by PluginNodes.entries.collectAsState()
    val allowed = remember(tools, baseline) {
        // In baseline mode the page shows the *effective* list — the profile as this
        // node sees it — so ticking and pinning read the same as they do on a profile.
        // Only the writing side differs, and that is `emit` below.
        baseline?.let { ToolOverrides.parse(tools).applyTo(it) } ?: ToolSpec.parse(tools)
    }
    val macroGroupTitle = stringResource(R.string.ai_tools_macros)
    val categoryTitles = categoryTitles()
    val groups = remember(allowed, baseline, macros, plugins, categoryTitles) {
        toolGroups(
            allowed = allowed,
            macros = macros,
            categoryTitle = { categoryTitles.getValue(it) },
            macroGroupTitle = macroGroupTitle,
            nodeTitle = { typeId -> NodeTypeRegistry.byId(typeId)?.let(text::name).orEmpty() },
            baseline = baseline,
        )
    }
    var pinning by remember { mutableStateOf<ToolTarget?>(null) }

    /**
     * The effective list back out as whatever this caller stores — the full list for a
     * profile, the *difference* for a node.
     *
     * The difference is computed rather than accumulated, which is what makes a row
     * ticked back to the profile's state contribute no line at all: a node that no
     * longer differs stores nothing, and then follows the profile again.
     */
    fun emit(effective: List<ToolSpec>) {
        onChange(
            if (baseline == null) {
                ToolSpec.encode(effective)
            } else {
                ToolOverrides.between(baseline, effective).encode()
            },
        )
    }

    fun apply(targets: Collection<ToolTarget>, allow: Boolean) = emit(allowed.setAllowed(targets, allow))

    fun reset(targets: Collection<ToolTarget>) {
        baseline?.let { emit(allowed.resetTo(it, targets)) }
    }

    EditorOverlay(title = stringResource(R.string.ai_tools_title), onClose = onClose) { _ ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "all") {
                AllRow(
                    groups = groups,
                    allowedCount = allowed.size,
                    adjusting = baseline != null,
                    onAll = { allow -> apply(groups.flatMap { it.candidates }.map { it.target }, allow) },
                    onResetAll = { reset(groups.flatMap { it.candidates }.map { it.target }) },
                )
            }
            groups.forEach { group ->
                item(key = "group-${group.title}") {
                    GroupRow(
                        group = group,
                        onToggle = { allow -> apply(group.candidates.map { it.target }, allow) },
                    )
                }
                items(group.candidates, key = { "tool-${it.target.encode()}" }) { candidate ->
                    ToolRow(
                        candidate = candidate,
                        onToggle = { allow -> apply(listOf(candidate.target), allow) },
                        onOpen = { pinning = candidate.target },
                        onReset = { reset(listOf(candidate.target)) },
                    )
                }
            }
        }
    }

    pinning?.let { target ->
        allowed.firstOrNull { it.target == target }?.let { spec ->
            ToolPinOverlay(
                spec = spec,
                onChange = { updated -> emit(allowed.map { if (it.target == target) updated else it }) },
                onDismiss = { pinning = null },
            )
        }
    }
}

/**
 * "Allow everything", plus the count and what it costs.
 *
 * The cap is stated here rather than enforced silently, because every tool's name,
 * description and argument schema is sent on **every turn** — so a long list is not
 * merely untidy, it is the thing that makes the model choose badly. See
 * `ToolSpec.MAX_TOOLS`.
 */
@Composable
private fun AllRow(
    groups: List<ToolGroup>,
    allowedCount: Int,
    adjusting: Boolean,
    onAll: (Boolean) -> Unit,
    onResetAll: () -> Unit,
) {
    val total = groups.sumOf { it.candidates.size }
    val needingChoice = groups.sumOf { group -> group.candidates.count { it.needsChoice } }
    val overridden = groups.sumOf { group -> group.candidates.count { it.isOverridden } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        // Said once at the top rather than on every row: what is on this page belongs
        // to the model, and what a node does here is depart from it.
        if (adjusting) {
            Text(
                text = stringResource(R.string.ai_tools_adjusting),
                color = EditorColors.textSecondary,
                fontSize = 12.sp,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ai_tools_allow_everything),
                    color = EditorColors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.ai_tools_allowed_count, allowedCount, total),
                    color = EditorColors.textSecondary,
                    fontSize = 12.sp,
                )
            }
            Switch(checked = allowedCount == total && total > 0, onCheckedChange = onAll)
        }
        if (adjusting && overridden > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.ai_tools_adjusted_count, overridden),
                    color = EditorColors.warnAccent,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onResetAll) {
                    Text(
                        text = stringResource(R.string.ai_tools_reset_all),
                        color = EditorColors.actionAccent,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        if (allowedCount > ToolSpec.MAX_TOOLS) {
            Text(
                text = stringResource(R.string.ai_tools_over_the_cap, ToolSpec.MAX_TOOLS),
                color = EditorColors.errorAccent,
                fontSize = 12.sp,
            )
        } else if (allowedCount > CROWDED) {
            Text(
                text = stringResource(R.string.ai_tools_many_warning),
                color = EditorColors.warnAccent,
                fontSize = 12.sp,
            )
        }
        if (needingChoice > 0) {
            Text(
                text = stringResource(R.string.ai_tools_need_a_choice, needingChoice),
                color = EditorColors.warnAccent,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun GroupRow(group: ToolGroup, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(
            state = when (group.state) {
                GroupState.ALL -> ToggleableState.On
                GroupState.NONE -> ToggleableState.Off
                GroupState.SOME -> ToggleableState.Indeterminate
            },
            // Indeterminate resolves upwards, which is what "tick the rest of this
            // group" means; only a fully ticked group clears.
            onClick = { onToggle(group.state != GroupState.ALL) },
            colors = checkboxColors(),
        )
        Text(
            text = group.title,
            color = EditorColors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        )
        Text(
            text = "${group.allowedCount}/${group.candidates.size}",
            color = EditorColors.textSecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ToolRow(
    candidate: ToolCandidate,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(EditorColors.nodeBackground)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = candidate.allowed, onCheckedChange = onToggle, colors = checkboxColors())
        Column(
            modifier = Modifier
                .weight(1f)
                // Only the label opens the pin form; the checkbox is its own target, so
                // ticking a row and configuring it are never the same tap.
                .clickable(enabled = candidate.allowed, onClick = onOpen)
                .padding(vertical = 10.dp),
        ) {
            Text(
                text = candidate.title,
                color = EditorColors.textPrimary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            candidate.subtitle()?.let { (text, warn) ->
                Text(
                    text = text,
                    color = if (warn) EditorColors.warnAccent else EditorColors.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Reset takes the place of the chevron on a row that differs, because those are
        // the two things worth doing to it and only one fits.
        if (candidate.isOverridden) {
            IconButton(onClick = onReset) {
                Icon(
                    imageVector = Icons.Filled.Undo,
                    contentDescription = stringResource(R.string.ai_tools_reset),
                    tint = EditorColors.warnAccent,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else if (candidate.allowed) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = EditorColors.textSecondary,
                modifier = Modifier.padding(end = 8.dp).size(18.dp),
            )
        }
    }
}

/**
 * The second line of a row: what it has to say about itself, most urgent first.
 *
 * **Adjusted wins over the pin count**, because on a node page it is the one fact that
 * is not visible from the checkbox: a row that is ticked and pinned like every other
 * looks identical to one following the profile, and only one of them will stop
 * following it when the profile changes.
 */
@Composable
private fun ToolCandidate.subtitle(): Pair<String, Boolean>? {
    val spec = spec ?: return if (isOverridden) stringResource(R.string.ai_tools_adjusted_off) to true else null
    val loose = loosePickers(target, spec)
    return when {
        loose.isNotEmpty() ->
            stringResource(R.string.ai_tools_needs, loose.joinToString(", ")) to true
        isOverridden ->
            stringResource(R.string.ai_tools_adjusted) to true
        spec.pinned.isNotEmpty() ->
            stringResource(R.string.config_tools_fixed_count, spec.pinned.size) to false
        else -> null
    }
}

/** Every category's label, resolved once so the grouping can be built outside composition. */
@Composable
private fun categoryTitles(): Map<io.github.m1n1m1.easymatic.domain.model.NodeCategory, String> =
    io.github.m1n1m1.easymatic.domain.model.NodeCategory.entries.associateWith { stringResource(it.labelRes()) }

@Composable
private fun checkboxColors() = CheckboxDefaults.colors(
    checkedColor = EditorColors.actionAccent,
    uncheckedColor = EditorColors.textSecondary,
    checkmarkColor = EditorColors.canvasBackground,
)

/** Beyond this many tools the prompt is crowded enough to say so, well short of the cap. */
private const val CROWDED = 24
