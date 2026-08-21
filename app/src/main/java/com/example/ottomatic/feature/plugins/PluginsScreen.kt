package com.example.ottomatic.feature.plugins

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ottomatic.data.plugin.InstalledPlugin
import com.example.ottomatic.data.plugin.PluginRegistry
import com.example.ottomatic.feature.SettingsTopBar
import com.example.ottomatic.feature.grapheditor.EditorColors
import kotlinx.coroutines.launch

/**
 * Which plugin apps are installed, and which of them the user has let contribute
 * nodes.
 *
 * ## This screen *is* the trust gate
 *
 * A plugin that is merely installed contributes nothing: not a palette entry, not a
 * search hit, not a row in `NodeTypeRegistry.all`. Discovery produces candidates;
 * this switch is the decision. That is why the copy says plainly what enabling means,
 * and why it does **not** describe the switch as a sandbox — it is not one. An enabled
 * plugin is an app the user chose to install, running its own code in its own process
 * under its own manifest permissions. Ottomatic never lends it any of its own, which
 * is worth saying because a person reading "enable" could reasonably assume the
 * opposite.
 *
 * ## And it is where "why is my node not in the palette?" gets an answer
 *
 * A node the host would not take is listed with the sentence explaining it, and a
 * plugin that could not be read at all says so. Without this the failure is a node
 * that is simply absent — the single worst shape a plugin bug can take, because there
 * is nothing on screen to notice.
 */
@Composable
fun PluginsScreen(
    registry: PluginRegistry,
    onBack: () -> Unit,
) {
    val plugins by registry.installed.collectAsState()
    val scope = rememberCoroutineScope()

    // Re-read on every resume rather than only on entry: apps are installed, permissions
    // granted and plugins signed into by *leaving* this app — exactly as on the
    // Permissions screen, and the reason `MainActivity.onResume` re-reads its grants. It
    // is also what closes the loop on the Set up button below, which sends the user into
    // another app and has no way to know when they are done.
    LifecycleResumeEffect(registry) {
        val job = scope.launch { registry.refreshNow() }
        onPauseOrDispose { job.cancel() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorColors.canvasBackground),
    ) {
        SettingsTopBar(
            title = stringResource(R.string.plugins_plugins),
            contentDescription = stringResource(R.string.plugins_back),
            onBack = onBack,
        )

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            item { PluginsPreamble(anyInstalled = plugins.isNotEmpty()) }
            items(plugins, key = { it.packageName }) { plugin ->
                PluginRow(
                    plugin = plugin,
                    onToggle = { enable ->
                        scope.launch {
                            if (enable) registry.enable(plugin.packageName) else registry.disable(plugin.packageName)
                        }
                    },
                    // Nothing to do on the way back: the resume effect above re-reads when
                    // the user returns, which is what turns this button and the readiness
                    // line into one loop — sign in over there, come back, and the "not
                    // ready" sentence and the warning on every placed node clear together.
                    onOpenSettings = { registry.openSettings(plugin.packageName) },
                )
            }
        }
    }
}

@Composable
private fun PluginsPreamble(anyInstalled: Boolean) {
    Text(
        text = if (anyInstalled) {
            stringResource(R.string.plugins_a_plugin_adds_trigger_and)
        } else {
            stringResource(R.string.plugins_no_plugins_are_installed_a)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = EditorColors.textSecondary,
    )
}

@Composable
private fun PluginRow(
    plugin: InstalledPlugin,
    onToggle: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(EditorColors.nodeBackground)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = plugin.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = EditorColors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = plugin.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = EditorColors.textSecondary,
                )
            }
            Switch(checked = plugin.enabled, onCheckedChange = onToggle)
        }

        // Offered whether or not the plugin is enabled, because signing in before
        // switching it on is the natural order — and because withholding `@ApiToken` and
        // the credential libraries from plugins, which is right, is exactly what makes
        // this the *only* way in for an account.
        if (plugin.hasSettings) {
            TextButton(
                onClick = onOpenSettings,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.plugins_open_settings),
                    style = MaterialTheme.typography.labelLarge,
                    color = EditorColors.actionAccent,
                )
            }
        }

        // The plugin's own sentence about itself, and the counterpart of the warning the
        // Problems panel raises on every placed node. Here it reaches somebody who has
        // not opened a macro yet; there it reaches somebody who has and cannot see why
        // nothing happens.
        plugin.notReady?.let { why ->
            Text(
                text = why,
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.warnAccent,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (plugin.enabled) {
            Text(
                text = if (plugin.nodeCount == 0) {
                    stringResource(R.string.plugins_provides_no_nodes)
                } else {
                    pluralStringResource(
                        R.plurals.plugins_provides_nodes,
                        plugin.nodeCount,
                        plugin.nodeCount,
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // The permissions listed are the *plugin's*, granted to the plugin. Naming them
        // here is the honest disclosure the switch needs: this is what the app the user
        // is about to enable can already do.
        if (plugin.permissions.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = EditorColors.textSecondary.copy(alpha = 0.15f),
            )
            Text(
                text = stringResource(
                    R.string.plugins_this_app_asks_for,
                    plugin.permissions.joinToString { it.substringAfterLast('.') },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.textSecondary,
            )
        }

        plugin.problem?.let { problem ->
            Box(modifier = Modifier.padding(top = 10.dp)) {
                Text(
                    text = problem,
                    style = MaterialTheme.typography.bodySmall,
                    color = EditorColors.errorAccent,
                )
            }
        }

        // A rejected node is the case this whole screen exists for: without it, the
        // failure is a node that is simply missing from the palette.
        plugin.rejected.forEach { rejection ->
            Text(
                text = stringResource(
                    R.string.plugins_node_rejected,
                    rejection.typeId.substringAfterLast('/'),
                    rejection.reason,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.errorAccent,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
