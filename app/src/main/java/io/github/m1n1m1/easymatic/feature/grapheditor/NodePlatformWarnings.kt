package io.github.m1n1m1.easymatic.feature.grapheditor

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.domain.model.NodeTypeDefinition
import io.github.m1n1m1.easymatic.domain.model.PlatformWarning

/** Shared by the picker and config form, including existing or imported nodes. Never blocks adding a node. */
@Composable
internal fun NodePlatformWarnings(definition: NodeTypeDefinition?) {
    val declared = definition?.platformWarnings.orEmpty()
    if (declared.isEmpty()) return
    val context = LocalContext.current
    val radioExempt = remember(context, declared) {
        declared.any { it.radioRestriction } && context.isRadioControlExempt()
    }
    val warnings = declared.filter {
        it.appliesTo(Build.VERSION.SDK_INT, context.applicationInfo.targetSdkVersion, radioExempt)
    }
    warnings.forEach { warning ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = EditorColors.triggerAccent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(warning.messageRes()),
                color = EditorColors.triggerAccent,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Android exempts system apps and device/profile owners from the radio API target restrictions. */
private fun Context.isRadioControlExempt(): Boolean {
    val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
    val policy = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    return applicationInfo.flags and systemFlags != 0 ||
        policy?.isDeviceOwnerApp(packageName) == true || policy?.isProfileOwnerApp(packageName) == true
}

@StringRes
internal fun PlatformWarning.messageRes(): Int = when (this) {
    PlatformWarning.WIFI_TOGGLE -> R.string.platform_warning_wifi
    PlatformWarning.BLUETOOTH_TOGGLE -> R.string.platform_warning_bluetooth
    PlatformWarning.DND_GLOBAL_CONTROL -> R.string.platform_warning_dnd
    PlatformWarning.SCREENSHOT_UNAVAILABLE -> R.string.platform_warning_screenshot
    PlatformWarning.PICTURE_BIN_UNAVAILABLE -> R.string.platform_warning_picture_bin
}
