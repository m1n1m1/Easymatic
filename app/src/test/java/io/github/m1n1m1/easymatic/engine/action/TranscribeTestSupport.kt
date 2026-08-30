package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.schema.Item
import io.github.m1n1m1.easymatic.engine.NodeInput
import io.github.m1n1m1.easymatic.engine.NodeOutput

/**
 * What the two answering transcription nodes hand back, read by name.
 *
 * They are `RawAction`s because they carry **two** DATA outputs — the words and the
 * language the recogniser says it used — and a typed `actionNode` offers only one. That
 * makes every assertion a map lookup, so the lookups live here rather than being spelt out
 * in three test files that would then disagree about which port is which.
 */
internal fun NodeOutput<Map<PortName, Item>>.answer(): String? =
    value[TRANSCRIPT_OUT]?.value as? String

/**
 * The detected language, or **null when the port carries nothing**.
 *
 * Null and `""` are different claims here and the tests rely on it: nothing reports a
 * language on the AI path or below Android 14, and `transcriptPorts` omits the port
 * entirely rather than sending an empty string — `action.listen`'s rule about a port nobody
 * can answer.
 */
internal fun NodeOutput<Map<PortName, Item>>.detectedLanguage(): String? =
    value[TRANSCRIPT_LANGUAGE_OUT]?.value as? String

/** A placed node to hand `executeRaw`, which none of these nodes reads. */
internal fun transcribeInput(typeId: String = "action.transcribe") = NodeInput(
    WorkflowNode(id = NodeId("n"), typeId = NodeTypeId(typeId), name = "n", x = 0f, y = 0f),
    emptyMap(),
)
