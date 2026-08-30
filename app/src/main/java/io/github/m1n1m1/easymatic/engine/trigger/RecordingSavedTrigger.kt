package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.service.RecordingRecord
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.NoConfig
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.derivedOut
import io.github.m1n1m1.easymatic.domain.model.items.RecordingItem
import io.github.m1n1m1.easymatic.domain.model.schema.DateTime
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * `trigger.recording_saved` — starts when a recording this app made has been saved.
 *
 * **It exists because a recording can end where nobody is looking.** `action.record_audio`
 * hands its file straight to the next node and needs nothing here; the interesting endings
 * are the other ones. A recording started by `action.record_start` finishes when its time
 * limit runs out, or when an `action.record_stop` in a *different* macro ends it — and in
 * both cases the file would otherwise have nowhere to arrive. "Record while I am driving,
 * and mail me whatever was recorded when it stops" is one trigger and one action because of
 * this node, and two macros sharing a variable without it.
 *
 * **Only recordings this app made.** There is no platform broadcast for "some app finished
 * recording" and there could not sensibly be one, so this hears the three `action.record_*`
 * nodes and nothing else. It is not a way to find out that the voice recorder was used.
 *
 * **No config**, and none to add: there is one microphone, so there is one stream of
 * recordings, and filtering by folder would be filtering a list that rarely has two
 * different answers in it. A macro that cares can compare `path` in an `action.if`.
 *
 * **No permission declared, which is the deliberate half.** Every other node in the family
 * asks for `RECORD_AUDIO`, and this one is *told* that a recording finished rather than
 * listening to anything — a macro doing nothing but reacting works perfectly with the grant
 * refused, and badging it would be the Problems panel raising an alarm about a node that is
 * working. `value.nfc`'s argument, one family along.
 *
 * **Filtered by source alone**, unlike every other media trigger. `TriggerSource.MEDIA_STORE`
 * is routed to one node id because each node carries its own high-water mark over a
 * collection it did not fill; here the recorder ended the recording itself, and that fact is
 * equally new to every armed node. There is nothing to arm and nothing to cancel, so this
 * has no handle and no `finally`.
 *
 * The `path` projection is a `derivedOut` on `trigger.image_saved`'s reasoning: it is the
 * handle a sibling node takes — `action.file_transfer`, a mail attachment — so the
 * commonest wiring needs no `action.break`.
 */
class RecordingSavedTrigger : Trigger<NoConfig, RecordingItem> {

    override val definition = triggerNode<NoConfig, RecordingItem>(
        typeId = "trigger.recording_saved",
        displayName = "Recording Finished",
        description = "Starts when a recording made by this app has been saved",
        category = NodeCategory.PHONE_MEDIA,
        icon = NodeIcon.MICROPHONE,
        output = dataOut<RecordingItem>("recording", label = "Recording"),
        extraOutputs = listOf(
            derivedOut<String, RecordingItem>("path", label = "Path") { it.path },
        ),
    )

    override fun activate(
        config: NoConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<RecordingItem>> =
        host.busEvents()
            .filter { it.source == TriggerSource.RECORDING }
            .map { event -> NodeOutput(RecordingEventCodec.decode(event.payload).toItem()) }
}

/**
 * How a [RecordingRecord] crosses `TriggerBus` and comes back.
 *
 * `ImageEventCodec`'s arrangement and its reason: the bus payload is `Map<String, String>`,
 * so a struct has to be flattened and rebuilt, and one object owning both halves is what
 * stops a key being spelled two ways — which produces a port that is silently always empty
 * and which no test catches unless it happens to assert on that exact field.
 */
object RecordingEventCodec {

    private const val PATH = "path"
    private const val NAME = "name"
    private const val FOLDER = "folder"
    private const val MIME = "mimeType"
    private const val DURATION = "durationMs"
    private const val SIZE = "sizeBytes"
    private const val RECORDED = "recordedAt"

    fun encode(record: RecordingRecord): Map<String, String> = mapOf(
        PATH to record.path,
        NAME to record.name,
        FOLDER to record.folder,
        MIME to record.mimeType,
        DURATION to record.durationMs.toString(),
        SIZE to record.sizeBytes.toString(),
        RECORDED to record.recordedAtEpochMs.toString(),
    )

    /**
     * Rebuilds the record.
     *
     * Every numeric falls back to **-1 rather than 0** when the payload is missing or
     * unparseable, on `ImageEventCodec`'s rule: a zero here would read as a recording that
     * captured nothing, which is a much more alarming answer than "the payload does not say".
     */
    fun decode(payload: Map<String, String>): RecordingRecord = RecordingRecord(
        path = payload[PATH].orEmpty(),
        name = payload[NAME].orEmpty(),
        folder = payload[FOLDER].orEmpty(),
        mimeType = payload[MIME].orEmpty(),
        durationMs = payload[DURATION]?.toLongOrNull() ?: -1,
        sizeBytes = payload[SIZE]?.toLongOrNull() ?: -1,
        recordedAtEpochMs = payload[RECORDED]?.toLongOrNull() ?: -1,
    )
}

/**
 * The one place the facade's record becomes the graph's struct.
 *
 * `ImageRecord.toItem()`'s arrangement, and it exists for the boundary rather than for
 * tidiness: `core` may not import `domain`, so the record carries epoch millis and the item
 * carries [DateTime].
 */
internal fun RecordingRecord.toItem(): RecordingItem = RecordingItem(
    path = path,
    name = name,
    folder = folder,
    mimeType = mimeType,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    recordedAt = recordedAtEpochMs.takeIf { it > 0 }?.let { DateTime(it) },
)
