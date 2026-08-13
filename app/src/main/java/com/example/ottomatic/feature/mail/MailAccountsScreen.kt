package com.example.ottomatic.feature.mail

import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ottomatic.feature.grapheditor.EditorColors

/**
 * The standalone mail account library, reached from the workflow list's overflow.
 *
 * Same argument as the Geofences and Tags screens: a library shared across macros
 * needs somewhere to be audited and pruned that is not inside one node's config
 * form. It carries one job neither of those has, though — a password expires or is
 * revoked, and when it does, every macro using the account stops at once. This is
 * where that is visible and where it is fixed.
 */
@Composable
fun MailAccountsScreen(
    viewModel: MailAccountsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorColors.canvasBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(color = EditorColors.chrome) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(60.dp)
                        .padding(start = 6.dp, end = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.mail_back),
                            tint = EditorColors.textPrimary,
                        )
                    }
                    Text(
                        text = stringResource(R.string.mail_mail_accounts),
                        color = EditorColors.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            MailAccountList(
                accounts = state.accounts,
                // Nothing to pick here, so tapping a row and tapping the pencil are
                // the same act — as on the Tags screen.
                onSelect = { account -> viewModel.editAccount(account.id) },
                onEdit = { account -> viewModel.editAccount(account.id) },
                onAdd = viewModel::addAccount,
                needsPassword = viewModel::needsPassword,
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }

    state.draft?.let { draft ->
        MailAccountEditorOverlay(
            draft = draft,
            viewModel = viewModel,
            onClose = viewModel::closeEditor,
        )
    }
}
