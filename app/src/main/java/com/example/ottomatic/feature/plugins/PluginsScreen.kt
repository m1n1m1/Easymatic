package com.example.ottomatic.feature.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ottomatic.data.plugin.InstalledPlugin
import com.example.ottomatic.data.plugin.PluginRegistry
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

    // Re-read on entry: apps are installed and permissions granted by leaving this
    // app, exactly as on the Permissions screen.
    LaunchedEffect(Unit) { registry.refreshNow() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorColors.canvasBackground),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = EditorColors.textPrimary,
                )
            }
            Text(
                text = "Plugins",
                style = MaterialTheme.typography.titleLarge,
                color = EditorColors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
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
                )
            }
        }
    }
}

@Composable
private fun PluginsPreamble(anyInstalled: Boolean) {
    Text(
        text = if (anyInstalled) {
            "A plugin adds trigger and action nodes to your palette. It is a separate app: it " +
                "runs its own code, in its own process, using only the permissions it asks you " +
                "for itself. Ottomatic does not share its permissions with it."
        } else {
            "No plugins are installed. A plugin is a separate app that adds trigger and action " +
                "nodes to your palette; install one and it will appear here."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = EditorColors.textSecondary,
    )
}

@Composable
private fun PluginRow(plugin: InstalledPlugin, onToggle: (Boolean) -> Unit) {
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

        if (plugin.enabled) {
            Text(
                text = when (plugin.nodeCount) {
                    0 -> "Provides no nodes"
                    1 -> "Provides 1 node"
                    else -> "Provides ${plugin.nodeCount} nodes"
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
                text = "This app asks for: " +
                    plugin.permissions.joinToString { it.substringAfterLast('.') },
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
                text = "${rejection.typeId.substringAfterLast('/')} was not added: ${rejection.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.errorAccent,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
