package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.capabilities.DeviceCapability
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.engine.ai.canRunAsTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two audio AI nodes' wiring, and mainly what is deliberately **absent** from it.
 *
 * `action.ai_listen` looks like `action.listen` and must not be declared like it. That
 * node declares `DeviceCapability.SPEECH_RECOGNITION`, because it drives the platform
 * recogniser and a phone without one genuinely cannot run it. This one drives no
 * recogniser at all — it records and sends — so copying the capability across would put a
 * permanent amber badge in the Problems panel on phones that run the node perfectly. That
 * is `value.nfc`'s failure, and it is the mistake most likely to be made here.
 *
 * `action.ai_transcribe` declares no permission for the mirror-image reason: it reads a
 * file that is already on the phone, through the same grants every `action.file_*` node
 * uses, and a microphone requirement on it would ask for access it never touches.
 */
class AiAudioRegistryTest {

    private val transcribe = NodeTypeId("action.ai_transcribe")
    private val listen = NodeTypeId("action.ai_listen")
    private val listenStart = NodeTypeId("action.ai_listen_start")
    private val listenStop = NodeTypeId("action.ai_listen_stop")
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
     * The split that makes the pair readable: the start node opens the microphone and the
     * stop node asks the model, so only one of them names a model. Two model fields on one
     * pair could disagree, with nothing on either card saying which won.
     */
    @Test
    fun `only the stop node of the pair names a model`() {
        val startFields = ConfigSchemaRegistry.byId(listenStart)!!.fields.map { it.key.value }
        val stopFields = ConfigSchemaRegistry.byId(listenStop)!!.fields.map { it.key.value }

        assertFalse("modelRef" in startFields)
        assertTrue("modelRef" in stopFields)
        assertTrue("answer" !in startFields)
    }

    /** Starting produces no answer; the sound has not been sent anywhere yet. */
    @Test
    fun `starting has no data output`() {
        val outputs = NodeTypeRegistry.byId(listenStart)!!.ports.filter { port ->
            port.kind == com.example.ottomatic.domain.model.PortKind.DATA &&
                port.direction == com.example.ottomatic.domain.model.Direction.OUT
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
     * Both answer on one Text port, which is what lets a transcript go straight into
     * `transform.text`, a notification or a mail body with nothing to break apart first.
     */
    @Test
    fun `both answer on a single named data output`() {
        listOf(transcribe, listen, listenStop).forEach {
            val outputs = NodeTypeRegistry.byId(it)!!.ports.filter { port ->
                port.kind == com.example.ottomatic.domain.model.PortKind.DATA &&
                    port.direction == com.example.ottomatic.domain.model.Direction.OUT
            }
            assertEquals("$it answers on one port", 1, outputs.size)
            assertEquals("answer", outputs.single().name.value)
        }
    }

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
     * The other direction is deliberately **not** symmetric. `action.ai_transcribe` reads a
     * file that is already on the phone and opens no microphone, so putting "listen" or
     * "microphone" in its description to make the search symmetrical would be a small lie
     * about what the node does — and the palette subtitle is the same string.
     */
    @Test
    fun `the words for opening a microphone find only the nodes that open one`() {
        val listening = listOf(listen, listenStart, listenStop)
        for (term in listOf("listen", "microphone")) {
            val found = NodeTypeRegistry.all.filter { it.matchesSearch(term) }.map { it.typeId }
            assertTrue("searching '$term' missed ${listening - found.toSet()}", found.containsAll(listening))
            assertFalse("searching '$term' should not reach the file node", transcribe in found)
        }
    }

    /**
     * The near miss, pinned by name. A future reword back to "transcript" would pass every
     * other test in this file and silently un-list three nodes.
     */
    @Test
    fun `the word is transcribe, not transcript`() {
        listOf(listen, listenStart, listenStop).forEach { typeId ->
            val description = NodeTypeRegistry.byId(typeId)!!.description
            assertTrue(
                "$typeId must say \"transcribe\" — \"transcript\" does not match a search for it",
                description.contains("transcribe", ignoreCase = true),
            )
        }
    }

    /** A model must not be able to pick which model bills the user. */
    @Test
    fun `the model field cannot be filled in by a model`() {
        assertFalse(
            PickerOptions.of(
                com.example.ottomatic.domain.model.config.PickerKind.AI_MODEL,
                emptyList(),
            ).isNotEmpty(),
        )
    }
}
