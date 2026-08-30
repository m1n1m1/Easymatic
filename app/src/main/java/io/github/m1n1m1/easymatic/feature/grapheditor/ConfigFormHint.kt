package io.github.m1n1m1.easymatic.feature.grapheditor

import androidx.compose.ui.res.stringResource
import io.github.m1n1m1.easymatic.R
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.domain.registry.decode
import io.github.m1n1m1.easymatic.domain.registry.nodeSchema
import io.github.m1n1m1.easymatic.engine.trigger.GeofenceConfig
import io.github.m1n1m1.easymatic.engine.trigger.GeofenceTrigger

/**
 * A note about a config combination that is valid but does not mean what it
 * looks like, shown under the fields it concerns.
 *
 * Two cases now: a geofence trigger with every switch turned off still fires on
 * enter, and a transcription node set to run on the phone depends on a language
 * pack the phone may not have. Both are configurations that are *correct* and
 * that behave in a way the fields above do not show.
 */
@Composable
internal fun ConfigFormHint(node: WorkflowNode) {
    OfflineTranscriptionHint(node)
    if (node.typeId != GeofenceTrigger.TYPE_ID) return
    // Decoded rather than read key by key. The first version listed the switch
    // names here and repeated their defaults, so adding "on staying away" to the
    // form left a node configured entirely correctly being told it had chosen
    // nothing.
    if (GEOFENCE_SCHEMA.decode(node).hasChosenEvent) return
    Text(
        text = stringResource(R.string.grapheditor_nothing_selected_u2014_this_trigger),
        color = EditorColors.triggerAccent,
        fontSize = 12.sp,
    )
}

private val GEOFENCE_SCHEMA = nodeSchema<GeofenceConfig>()

/**
 * Warns that transcribing on the phone needs a language pack installed.
 *
 * **Inline rather than in the Problems panel**, and the precedent is exact:
 * `NodePermissionNotice.ContactsPermissionNotice` sits in the form because its
 * requirement is derived from a node's *config* rather than declared on its type, and
 * `validatePrerequisites`' own KDoc ratifies that split. The same holds here twice over —
 * only the phone-side engine needs a pack at all, so a node using an AI model must not be
 * badged, and `capabilities` on a `NodeTypeDefinition` is a flat declaration that cannot
 * say "only when this dropdown says so".
 *
 * It warns rather than refuses, on `AiBaseUrl.isCleartext`'s reasoning: the packs the
 * phone has are not knowable here — `SpeechLanguages` reports what the recogniser
 * *supports*, which is not what is *downloaded* — so the honest thing is to say what the
 * option depends on and let the run log name the language if it turns out to be missing.
 */
@Composable
private fun OfflineTranscriptionHint(node: WorkflowNode) {
    if (node.typeId !in OFFLINE_CAPABLE) return
    if (node.config[USING_KEY] != PHONE_VALUE) return
    Text(
        text = stringResource(R.string.grapheditor_transcribing_on_this_phone_needs),
        color = EditorColors.triggerAccent,
        fontSize = 12.sp,
    )
    // A second sentence rather than a second notice: both are about the same choice, and
    // two amber blocks under one dropdown reads as two problems.
    if (node.config[LANGUAGE_MODE_KEY] == DETECT_VALUE) {
        Text(
            text = stringResource(R.string.grapheditor_detecting_the_language_needs),
            color = EditorColors.triggerAccent,
            fontSize = 12.sp,
        )
    }
}

/** The nodes whose engine dropdown can select the phone's own recogniser. */
private val OFFLINE_CAPABLE = setOf(
    NodeTypeId("action.transcribe"),
    NodeTypeId("action.transcribe_start"),
)

private val USING_KEY = ConfigKey("using")
private val LANGUAGE_MODE_KEY = ConfigKey("languageMode")

/** `TranscribeLanguage.DETECT`'s serial name, which is what a stored config holds. */
private const val DETECT_VALUE = "detect"

/** `TranscribeUsing.PHONE`'s serial name, which is what a stored config holds. */
private const val PHONE_VALUE = "phone"
