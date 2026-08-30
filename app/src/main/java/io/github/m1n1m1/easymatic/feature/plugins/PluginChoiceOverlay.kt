package io.github.m1n1m1.easymatic.feature.plugins

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.domain.registry.ConfigOption
import io.github.m1n1m1.easymatic.engine.plugin.ChoiceList
import io.github.m1n1m1.easymatic.engine.plugin.PluginChoiceReader
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorOverlay

/**
 * Picks the value of a `@PluginChoice` field, from a list the plugin answers.
 *
 * ## Why it fetches on open, every time
 *
 * The other eleven pickers read a hydrated registry — the user's places, their macros,
 * their tags — which is in memory because Easymatic owns it. This one asks another app,
 * over a binder, about the user's data on somebody's server, and there is no registry to
 * hydrate: a page list is not the kind of thing that has a moment when it is *known*.
 * Caching it would mean confidently offering a page that was deleted last week, which is
 * worse than the alternative failure — a chooser that takes a second, which is one the
 * person looking at it can see and wait out.
 *
 * So: a spinner, then a list, and the read is redone the next time it opens.
 *
 * ## What it does when the plugin cannot answer
 *
 * Says so, in the plugin's own words, **and leaves the stored value alone**. That is the
 * whole reason this stayed a read-only chooser rather than degrading to a text field when
 * offline: a field somebody can type into is a field that will eventually hold a
 * mistyped id, which is the failure the chooser exists to prevent, arriving by a side
 * door. The value that is already there survives, so a macro configured last week is not
 * silently emptied by a plugin that happens to be signed out today.
 */
@Composable
fun PluginChoiceOverlay(
    typeId: NodeTypeId,
    source: String,
    title: String,
    config: Map<ConfigKey, String>,
    selected: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    /**
     * An answer the caller already has, in which case the plugin is not asked at all.
     *
     * One case uses it, and it is the reason this parameter beats a second composable: a
     * `SCREEN`-mode field whose plugin exports no chooser Activity has a problem to report
     * and no list to fetch. Reporting it here rather than under the closed field keeps
     * every chooser failure in the one place somebody is already looking.
     */
    preloaded: ChoiceList? = null,
) {
    var answer by remember { mutableStateOf(preloaded) }
    var picked by remember { mutableStateOf<String?>(null) }

    // Keyed on everything the answer depends on, so a scoped chooser reopened after its
    // scope changed asks again rather than showing the previous workspace's pages.
    LaunchedEffect(typeId, source, config, preloaded) {
        if (preloaded == null) answer = PluginChoiceReader.read(typeId, source, config)
    }

    EditorOverlay(
        title = title,
        onClose = { picked?.let(onPick) ?: onDismiss() },
    ) { dismiss ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            when (val current = answer) {
                null -> Loading()
                else -> ChoiceBody(
                    answer = current,
                    selected = selected,
                    onSelect = { option ->
                        picked = option.value
                        dismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun Loading() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = EditorColors.actionAccent)
    }
}

/**
 * The list, the plugin's problem, or both.
 *
 * Both is a real state and not a defensive one: a plugin that can reach two workspaces
 * but not the third has options *and* something to say, and showing only one of them
 * would either hide two usable answers or hide the reason the third is missing.
 */
@Composable
private fun ChoiceBody(
    answer: ChoiceList,
    selected: String?,
    onSelect: (ConfigOption) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        answer.problem?.let { problem ->
            Text(
                text = problem,
                color = EditorColors.errorAccent,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
            HorizontalDivider(color = EditorColors.chromeBorder)
        }
        if (answer.options.isEmpty()) {
            // Distinct from the problem above, and worth its own sentence: "nothing to
            // choose from" is an answer the plugin gave, where a problem is one it could
            // not give. An empty screen says neither.
            if (answer.problem == null) {
                Text(
                    text = stringResource(R.string.plugin_choice_nothing_to_choose),
                    color = EditorColors.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            return@Column
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(answer.options, key = { it.value }) { option ->
                ChoiceRow(
                    option = option,
                    isSelected = option.value == selected,
                    onClick = { onSelect(option) },
                )
                HorizontalDivider(color = EditorColors.chromeBorder)
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    option: ConfigOption,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = option.label,
            color = if (isSelected) EditorColors.actionAccent else EditorColors.textPrimary,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
        )
        // The stored id under the label, because that is what the closed field shows when
        // the plugin cannot be reached — seeing it here is what makes that field readable
        // later rather than an opaque string somebody has to guess at.
        if (option.label != option.value) {
            Text(text = option.value, color = EditorColors.textSecondary)
        }
    }
}
