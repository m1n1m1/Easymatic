package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.core.capabilities.DeviceCapability
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.permissions.Permissions
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.NodeKind
import io.github.m1n1m1.easymatic.engine.ai.canRunAsTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two audio AI nodes' wiring, and mainly what is deliberately **absent** from it.
 *
 * `action.transcribe` looks like `action.listen` and must not be declared like it. That
 * node declares `DeviceCapability.SPEECH_RECOGNITION`, because it drives the platform
 * recogniser and a phone without one genuinely cannot run it. This one drives no
 * recogniser at all — it records and sends — so copying the capability across would put a
 * permanent amber badge in the Problems panel on phones that run the node perfectly. That
 * is `value.nfc`'s failure, and it is the mistake most likely to be made here.
 *
 * `action.transcribe_file` declares no permission for the mirror-image reason: it reads a
 * file that is already on the phone, through the same grants every `action.file_*` node
 * uses, and a microphone requirement on it would ask for access it never touches.
 */
class TranscribeRegistryTest {

    private val transcribe = NodeTypeId("action.transcribe_file")
    private val listen = NodeTypeId("action.transcribe")
    private val listenStart = NodeTypeId("action.transcribe_start")
    private val listenStop = NodeTypeId("action.transcribe_end")
    private val all = listOf(transcribe, listen, listenStart, listenStop)

    @Test
    fun `both are registered as actions`() {
        all.forEach {
            assertNotNull("$it must reach NodeTypeRegistry", NodeTypeRegistry.byId(it))
            assertNotNull("$it must reach ActionRegistry", ActionRegistry.byId(it))
        }
    }

    @Test
    fun `both sit in the AI category beside the other AI nodes`() {
        all.forEach {
            val definition = NodeTypeRegistry.byId(it)!!
            assertEquals(NodeKind.ACTION, definition.kind)
            assertEquals(NodeCategory.AI, definition.category)
        }
    }

    /**
     * The microphone node carries the microphone icon even though its category is AI. The
     * card is what a user reads first, and "this one opens the microphone" is the fact
     * worth showing there.
     */
    @Test
    fun `the icons say which one opens the microphone`() {
        assertEquals(NodeIcon.AI, NodeTypeRegistry.byId(transcribe)!!.icon)
        assertEquals(NodeIcon.MICROPHONE, NodeTypeRegistry.byId(listen)!!.icon)
        assertEquals(NodeIcon.MICROPHONE, NodeTypeRegistry.byId(listenStart)!!.icon)
        assertEquals(NodeIcon.MICROPHONE, NodeTypeRegistry.byId(listenStop)!!.icon)
    }

    /**
     * The recording family's constant, not a second one: the Permissions screen keys on it.
     *
     * **The stop node declares it too, even though it opens nothing.** The pair is one
     * capability from the user's point of view, and a grant-free stop node would let the
     * Permissions screen imply half of a two-node flow works without the microphone.
     */
    @Test
    fun `every listening node declares the same microphone grant the recording nodes use`() {
        listOf(listen, listenStart, listenStop).forEach { typeId ->
            val requirement = NodeTypeRegistry.byId(typeId)!!.permissionRequirements.single()

            assertEquals(typeId.value, Permissions.RECORD_AUDIO.manifest, requirement.manifestPermission)
            assertEquals(typeId.value, PrerequisiteType.RUNTIME, requirement.type)
            assertEquals(typeId.value, "audio.record", requirement.rationaleKey)
        }
    }

    /**
     * **The pair is set up on Start and collected at End**, which is `action.record_start`
     * and `action.record_stop`'s shape: every field on the start node, and the stop node
     * carrying only what is genuinely about stopping.
     *
     * It was the other way round — engine on Start, model on End — with each field where
     * the *code* used it. That is true and useless to fill in: one decision split across
     * two cards, and an End node asking for a model even when the phone was doing the work,
     * because a static form cannot know what Start chose.
     */
    @Test
    fun `the pair is configured on the start node`() {
        val startFields = ConfigSchemaRegistry.byId(listenStart)!!.fields.map { it.key.value }
        val stopFields = ConfigSchemaRegistry.byId(listenStop)!!.fields.map { it.key.value }

        assertTrue("using" in startFields)
        assertTrue("modelRef" in startFields)
        assertTrue("prompt" in startFields)
        // End keeps the one field that is about End: what lands on its port if it fails.
        assertEquals(listOf("fallback"), stopFields)
    }

    /**
     * **The model is hidden unless an AI model is doing the work**, which is what stops the
     * Problems panel demanding one for a node transcribing on the phone. `GraphValidator`
     * skips a field the form does not show, so the rule and the warning agree by
     * construction rather than by both being remembered.
     */
    @Test
    fun `the model field is shown only for the AI engine`() {
        val model = ConfigSchemaRegistry.byId(listenStart)!!.fields.single { it.key.value == "modelRef" }

        assertEquals("using", model.visibleWhen?.key?.value)
        assertEquals(setOf("ai"), model.visibleWhen?.values?.toSet())
    }

    /** Starting produces no answer; the sound has not been sent anywhere yet. */
    @Test
    fun `starting has no data output`() {
        val outputs = NodeTypeRegistry.byId(listenStart)!!.ports.filter { port ->
            port.kind == io.github.m1n1m1.easymatic.domain.model.PortKind.DATA &&
                port.direction == io.github.m1n1m1.easymatic.domain.model.Direction.OUT
        }

        assertTrue(outputs.isEmpty())
    }

    @Test
    fun `transcribing a file needs no permission of its own`() {
        assertTrue(NodeTypeRegistry.byId(transcribe)!!.permissionRequirements.isEmpty())
    }

    /** The one most likely to be added "for consistency" with `action.listen`. */
    @Test
    fun `neither declares a device capability`() {
        all.forEach {
            assertTrue(
                "$it must not require hardware — it drives no recogniser",
                NodeTypeRegistry.byId(it)!!.capabilities.isEmpty(),
            )
        }
        // The node it resembles, for contrast.
        assertEquals(
            listOf(DeviceCapability.SPEECH_RECOGNITION),
            NodeTypeRegistry.byId(NodeTypeId("action.listen"))!!.capabilities,
        )
    }

    /**
     * The transcript is a plain Text port on every node that answers, which is what lets it
     * go straight into `transform.text`, a notification or a mail body with nothing to
     * break apart first. A struct carrying the words beside the language would put an
     * `action.break` in front of every single use for a field most macros never read —
     * `AiReply`'s stated reasoning, one family along.
     */
    @Test
    fun `every answering node carries the transcript on the same named port`() {
        listOf(transcribe, listen, listenStop).forEach {
            val outputs = dataOutputsOf(it)
            assertTrue("$it must answer on a port called 'answer'", "answer" in outputs)
        }
    }

    /**
     * The two microphone nodes that answer also report **which language** the recogniser
     * used; the file node does not, because a model never says what it detected.
     */
    @Test
    fun `the two listening answers also carry the detected language`() {
        listOf(listen, listenStop).forEach {
            assertEquals(listOf("answer", "language"), dataOutputsOf(it))
        }
        assertEquals(listOf("answer"), dataOutputsOf(transcribe))
    }

    private fun dataOutputsOf(typeId: NodeTypeId): List<String> =
        NodeTypeRegistry.byId(typeId)!!.ports
            .filter { port ->
                port.kind == io.github.m1n1m1.easymatic.domain.model.PortKind.DATA &&
                    port.direction == io.github.m1n1m1.easymatic.domain.model.Direction.OUT
            }
            .map { it.name.value }

    /**
     * Both are ordinary actions, so a model allowed "everything" can reach them — which
     * includes switching the microphone on. That is the exposure `action.record_audio` and
     * `action.listen` already carry, and it is bounded the same way: the author ticks the
     * tool on the profile, and every call is written to the run log as it happens.
     */
    @Test
    fun `both can be offered to a model as tools`() {
        all.forEach {
            assertTrue("$it should be offerable", canRunAsTool(it, NodeTypeRegistry.byId(it)!!.kind))
        }
    }

    /**
     * **Every node that turns sound into text answers to "transcribe".**
     *
     * The palette searches the description as well as the name (`matchesSearch`), which is
     * what lets a node carry the words people actually type — and the trap here is that a
     * near miss reads as a hit. Three of these four said "transcript", which does **not**
     * contain "transcribe" under a substring match, so typing the obvious word found one
     * node out of four and made the other three look as though they did not exist.
     * `DialogDiscoverabilityTest`'s rule, one family along: a node nobody can find is
     * exactly as useful as one that does not exist.
     */
    @Test
    fun `transcribe finds every node that turns sound into text`() {
        val found = NodeTypeRegistry.all.filter { it.matchesSearch("transcribe") }.map { it.typeId }

        assertTrue("searching 'transcribe' missed ${all - found.toSet()}", found.containsAll(all))
    }

    /**
     * **The free option has to turn up beside the paid ones.**
     *
     * `action.listen` is the only node in the app that turns speech into text with no AI
     * connection, no key and no network, and it lives in a different category under a name
     * that says none of that. Somebody typing "transcribe" was shown the four nodes that
     * bill them and not the one that does not — which is the worst possible ordering,
     * because the person searching is exactly the person comparing.
     *
     * It is pinned here rather than beside the dialog family because the reason it matters
     * is what it sits *next to* in the results.
     */
    @Test
    fun `searching transcribe also offers the free on-device node`() {
        val found = NodeTypeRegistry.all.filter { it.matchesSearch("transcribe") }.map { it.typeId }

        assertTrue(
            "the keyless speech-to-text node must appear beside the ones that need a key",
            NodeTypeId("action.listen") in found,
        )
    }

    /**
     * "microphone" reaches the three that open one, and not the file node.
     *
     * The other direction is deliberately **not** symmetric. `action.transcribe_file` reads
     * a file that is already on the phone and opens no microphone, so working the word into
     * its description to make search symmetrical would be a small lie about what the node
     * does — and that string is the palette subtitle the user reads.
     */
    @Test
    fun `the word for opening a microphone finds only the nodes that open one`() {
        val listening = listOf(listen, listenStart, listenStop)
        val found = NodeTypeRegistry.all.filter { it.matchesSearch("microphone") }.map { it.typeId }

        assertTrue("searching 'microphone' missed ${listening - found.toSet()}", found.containsAll(listening))
        assertFalse("searching 'microphone' should not reach the file node", transcribe in found)
    }

    /**
     * **"Transcribing" does not contain "transcribe" under a substring match**, so the
     * display names alone would not answer the search that names this whole family. The
     * typeIds do, and that is the half worth pinning: a rename back to `action.ai_listen*`
     * would pass every other test in this file and quietly un-list three nodes again.
     */
    @Test
    fun `every node in the family is named so that searching transcribe reaches it`() {
        all.forEach { typeId ->
            assertTrue(
                "$typeId must answer a search for 'transcribe'",
                NodeTypeRegistry.byId(typeId)!!.matchesSearch("transcribe"),
            )
        }
    }

    /** A model must not be able to pick which model bills the user. */
    @Test
    fun `the model field cannot be filled in by a model`() {
        assertFalse(
            PickerOptions.of(
                io.github.m1n1m1.easymatic.domain.model.config.PickerKind.AI_MODEL,
                emptyList(),
            ).isNotEmpty(),
        )
    }
}
