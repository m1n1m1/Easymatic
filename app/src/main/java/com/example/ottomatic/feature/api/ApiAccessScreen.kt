package com.example.ottomatic.feature.api

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ottomatic.data.api.ApiCallers
import com.example.ottomatic.data.api.ApprovedCaller
import com.example.ottomatic.feature.SettingsTopBar
import com.example.ottomatic.feature.grapheditor.EditorColors
import java.text.DateFormat
import java.util.Date

/**
 * Which other apps may run this phone's macros.
 *
 * ## This screen *is* the trust gate, in its second half
 *
 * `PluginsScreen`'s counterpart for the opposite direction, and the same sentence
 * applies: approval is a decision, not a formality, and the copy says what it grants
 * rather than implying something narrower. The difference is that there is **no "Add"
 * here**. An app cannot be approved from this screen because approval requires the
 * system to vouch for who is asking, which only happens when the app itself starts
 * `ApiConsentActivity` for a result. This screen is where an approval is *reviewed and
 * withdrawn*.
 *
 * The other half of the gate is not on this screen at all: a macro is only reachable
 * because somebody placed a "Called by Another App" trigger on it. An approved app with
 * no such macro anywhere can do precisely nothing, which is why the preamble says so —
 * a row here reads far more alarming than it is without that sentence beside it.
 *
 * A row that disappears by itself is not a bug: `ApiCallers.isApproved` withdraws an
 * approval whose app has been re-signed by a different developer, so the entry is gone
 * the next time that app calls. Nothing is left saying "revoked", deliberately — the
 * app can simply ask again, and the honest state is that it is not approved.
 */
@Composable
fun ApiAccessScreen(
    callers: ApiCallers,
    reachableMacros: Int,
    onBack: () -> Unit,
) {
    val approved by callers.approved.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorColors.canvasBackground),
    ) {
        SettingsTopBar(
            title = stringResource(R.string.api_app_access),
            contentDescription = stringResource(R.string.api_back),
            onBack = onBack,
        )

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            item { AccessPreamble(anyApproved = approved.isNotEmpty(), reachableMacros = reachableMacros) }
            items(approved, key = { it.packageName }) { caller ->
                CallerRow(caller = caller, onRevoke = { callers.revoke(caller.packageName) })
            }
        }
    }
}

@Composable
private fun AccessPreamble(anyApproved: Boolean, reachableMacros: Int) {
    Column {
        Text(
            text = if (anyApproved) {
                stringResource(R.string.api_these_apps_can_see_and)
            } else {
                stringResource(R.string.api_no_other_app_can_run)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = EditorColors.textSecondary,
        )
        // The number that turns this screen from alarming into legible: with no such
        // macro, an approval grants the ability to do nothing at all.
        Text(
            text = if (reachableMacros == 0) {
                stringResource(R.string.api_no_macro_can_currently_be)
            } else {
                pluralStringResource(R.plurals.api_macros_callable, reachableMacros, reachableMacros)
            },
            style = MaterialTheme.typography.bodySmall,
            color = EditorColors.textSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun CallerRow(caller: ApprovedCaller, onRevoke: () -> Unit) {
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
                    text = caller.label.ifBlank { caller.packageName },
                    style = MaterialTheme.typography.titleSmall,
                    color = EditorColors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = caller.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = EditorColors.textSecondary,
                )
            }
            TextButton(onClick = onRevoke) { Text(stringResource(R.string.api_revoke)) }
        }
        if (caller.approvedAtMs > 0) {
            Text(
                text = stringResource(
                    R.string.api_allowed_on,
                    DateFormat.getDateInstance().format(Date(caller.approvedAtMs)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
