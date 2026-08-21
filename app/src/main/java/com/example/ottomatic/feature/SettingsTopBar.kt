package com.example.ottomatic.feature

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ottomatic.feature.grapheditor.EditorColors

/**
 * The header every screen reached from Setup wears: back arrow, title, chrome surface.
 *
 * One composable rather than one copy per screen, for the reason `trimmedBottomInsets`
 * is one function — the rule is the same rule, and a copy can only drift. It already
 * had: the ten screens that hand-rolled this Row agreed on the height, the padding and
 * the 18sp title, while App access and Plugins had drifted onto `titleLarge` and, worse,
 * had lost `statusBarsPadding()` — so their title sat *under* the status bar. That is
 * not a styling difference anybody chose; it is what a duplicated layout does.
 *
 * The status-bar inset is consumed **here** rather than by the screen around it, because
 * this bar is the topmost thing on every one of those screens. The list below it takes
 * `navigationBarsPadding()` for the same reason at the other end.
 *
 * [contentDescription] stays a parameter because each screen names its own back action
 * in its own string file — the generated node strings' rule, one screen one prefix —
 * and there is nothing to gain by moving eleven identical strings into one.
 */
@Composable
internal fun SettingsTopBar(
    title: String,
    contentDescription: String,
    onBack: () -> Unit,
) {
    Surface(color = EditorColors.chrome) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(BAR_HEIGHT)
                .padding(start = 6.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = contentDescription,
                    tint = EditorColors.textPrimary,
                )
            }
            Text(
                text = title,
                color = EditorColors.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

private val BAR_HEIGHT = 60.dp
