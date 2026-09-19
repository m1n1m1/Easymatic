package io.github.m1n1m1.easymatic.feature.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.feature.SettingsTopBar
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors

/** Installed package metadata, so release and debug builds report their own version. */
@Composable
fun InfoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val packageInfo = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    Column(Modifier.fillMaxSize().background(EditorColors.canvasBackground)) {
        SettingsTopBar(
            title = stringResource(R.string.info_title),
            contentDescription = stringResource(R.string.info_back),
            onBack = onBack,
        )
        SelectionContainer {
            Column(
                Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    color = EditorColors.textPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(18.dp),
                )
                InfoRow(stringResource(R.string.info_version), packageInfo.versionName.orEmpty())
                InfoRow(
                    stringResource(R.string.info_build_number),
                    PackageInfoCompat.getLongVersionCode(packageInfo).toString(),
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text(text = label, color = EditorColors.textSecondary, fontSize = 12.sp)
        Text(text = value, color = EditorColors.textPrimary, fontSize = 16.sp)
    }
    HorizontalDivider(color = EditorColors.chromeBorder, thickness = 1.dp)
}
