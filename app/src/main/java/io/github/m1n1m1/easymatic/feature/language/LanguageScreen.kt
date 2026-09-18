package io.github.m1n1m1.easymatic.feature.language

import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.feature.SettingsTopBar
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors

/**
 * Which language the app is shown in: the phone's, or one of the eight it ships.
 *
 * The choice is applied through `AppCompatDelegate`, so there is no repository behind
 * this screen and nothing in the Backup file: on API 33 and later the system stores
 * it (and shows the same choice under Settings → Apps → Easymatic → Language), below
 * that AppCompat keeps it in its own preferences. Either way it is per phone.
 *
 * Applying recreates the Activity, and the navigation state survives that, so the
 * user lands back here in the new language. Below API 33 only Activities follow the
 * choice: the home-screen widgets and a macro's own dialogs stay in the phone's
 * language, which the note at the top says on those phones alone.
 *
 * No ViewModel, on the Permissions screen's reasoning: the current value is one
 * synchronous platform read.
 */
@Composable
fun LanguageScreen(onBack: () -> Unit) {
    var selected by remember {
        mutableStateOf(AppLanguages.selectedTag(AppCompatDelegate.getApplicationLocales().toLanguageTags()))
    }
    val choose: (String?) -> Unit = { tag ->
        selected = tag
        AppCompatDelegate.setApplicationLocales(
            tag?.let(LocaleListCompat::forLanguageTags) ?: LocaleListCompat.getEmptyLocaleList(),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorColors.canvasBackground),
    ) {
        SettingsTopBar(
            title = stringResource(R.string.language_title),
            contentDescription = stringResource(R.string.language_back),
            onBack = onBack,
        )

        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            item { Note(stringResource(R.string.language_intro)) }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                item { Note(stringResource(R.string.language_widgets_note)) }
            }
            item {
                LanguageRow(
                    name = stringResource(R.string.language_system_default),
                    selected = selected == null,
                    onSelect = { choose(null) },
                )
            }
            items(AppLanguages.tags, key = { it }) { tag ->
                LanguageRow(
                    name = AppLanguages.nameOf(tag),
                    selected = selected == tag,
                    onSelect = { choose(tag) },
                )
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = EditorColors.textSecondary,
        modifier = Modifier.padding(horizontal = ROW_INSET, vertical = 8.dp),
    )
}

@Composable
private fun LanguageRow(name: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = ROW_INSET, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = EditorColors.actionAccent,
                unselectedColor = EditorColors.textSecondary,
            ),
        )
        Spacer(Modifier.width(16.dp))
        Text(text = name, color = EditorColors.textPrimary, fontSize = 16.sp)
    }
    HorizontalDivider(color = EditorColors.chromeBorder, thickness = 1.dp)
}

/** The horizontal inset every row under Setup uses. */
private val ROW_INSET = 18.dp
