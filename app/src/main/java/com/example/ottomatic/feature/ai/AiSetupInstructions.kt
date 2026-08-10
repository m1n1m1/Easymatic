package com.example.ottomatic.feature.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ottomatic.domain.model.AiProvider
import com.example.ottomatic.feature.grapheditor.EditorColors

/**
 * How to get a key, in the order it is actually done.
 *
 * Written as steps rather than a paragraph because it is a procedure in another
 * app: somebody following it is switching back and forth and needs to find their
 * place again, which prose does not let them do. A self-hosted connection has no
 * console to open, so the button is simply absent there rather than pointing
 * somewhere generic.
 */
@Composable
internal fun SetupInstructions(provider: AiProvider, onOpen: () -> Unit) {
    Surface(
        color = EditorColors.nodeBackground,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (provider == AiProvider.OPENAI_COMPATIBLE) "Pointing at your own server" else "Getting a key",
                color = EditorColors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            provider.setupSteps().forEachIndexed { index, step -> Step(index + 1, step) }

            if (provider.consoleUrl().isNotBlank()) {
                Button(
                    onClick = onOpen,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EditorColors.actionAccent,
                        contentColor = EditorColors.textPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(text = "Open ${provider.consoleName()}", modifier = Modifier.padding(start = 8.dp))
                }
            }

            Text(text = provider.costNote(), color = EditorColors.textSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            color = EditorColors.actionAccent.copy(alpha = 0.18f),
            shape = CircleShape,
            modifier = Modifier.size(22.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "$number",
                    color = EditorColors.actionAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(
            text = text,
            color = EditorColors.textSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
