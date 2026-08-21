package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeTypeDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four dialog nodes are one family, and have to be findable as one.
 *
 * They are no longer the whole of [NodeCategory.INTERACTION]: the three voice nodes joined
 * it in 2026-08, because "Ask the User" is what the category *is* and speaking to somebody
 * is asking them through a different channel. The four are still pinned as a contiguous,
 * ordered block at the head of it — what this file guards is that they stay together and
 * stay first, not that nothing else may ever be an interaction.
 *
 * The words people type looking for this are "popup", "dialog", "prompt" and
 * "ask" — and only one of them appears in any of the four *names*. The palette
 * searches descriptions and the category label as well, which is what makes the
 * other three work; this pins that they keep working. A node nobody can find is
 * exactly as useful as one that does not exist.
 */
class DialogDiscoverabilityTest {

    private val dialogs: List<NodeTypeDefinition> =
        NodeTypeRegistry.all.filter { it.typeId in DIALOG_TYPE_IDS }

    @Test
    fun `there are four of them and the registry has not quietly grown a fifth`() {
        assertEquals(
            listOf("action.dialog_message", "action.dialog_confirm", "action.dialog_input", "action.dialog_choice"),
            dialogs.map { it.typeId.value },
        )
    }

    @Test
    fun `every word somebody might type finds all four`() {
        for (term in listOf("dialog", "popup", "ask", "user")) {
            val found = NodeTypeRegistry.all.filter { it.matchesSearch(term) }.map { it.typeId }
            assertTrue(
                "searching '$term' missed ${dialogs.map { it.typeId } - found.toSet()}",
                found.containsAll(dialogs.map { it.typeId }),
            )
        }
    }

    @Test
    fun `they share a category, so the palette lists them together`() {
        assertEquals(setOf(NodeCategory.INTERACTION), dialogs.map { it.category }.toSet())
        // The palette renders in registry order, and the plainest leads: a message
        // is what somebody reaches for before they need an answer at all. The four stay
        // contiguous and stay at the head of the category — a voice node between
        // `dialog_confirm` and `dialog_input` would break the family up on the card list.
        val interaction = NodeTypeRegistry.all.filter { it.category == NodeCategory.INTERACTION }
        assertEquals(dialogs.map { it.typeId }, interaction.take(dialogs.size).map { it.typeId })
    }

    @Test
    fun `the voice nodes follow them, asking the same thing through a different channel`() {
        // Speaking is `dialog_message` with nowhere to draw, and listening is
        // `dialog_input` with the user's hands full — so they belong to this category
        // rather than to AUDIO, whose nodes have the *microphone* in common and produce
        // recordings. Order follows the dialogs' own rule: the one needing no answer first,
        // and a stop immediately after the node it exists to undo.
        val interaction = NodeTypeRegistry.all.filter { it.category == NodeCategory.INTERACTION }
        assertEquals(
            listOf("action.speak", "action.speak_stop", "action.listen"),
            interaction.drop(dialogs.size).map { it.typeId.value },
        )
    }

    @Test
    fun `each one says it needs to draw over other apps`() {
        // Without this the node looks configured and silently cancels every time.
        // `manifestPermission` is null on purpose: this is a Settings-page grant,
        // not something the runtime dialog can ask for.
        for (dialog in dialogs) {
            val requirement = dialog.permissionRequirements.single()
            assertEquals("${dialog.typeId}: type", PrerequisiteType.OVERLAY, requirement.type)
            assertEquals("${dialog.typeId}: rationale", "overlay.dialog", requirement.rationaleKey)
        }
    }

    @Test
    fun `three of them ask a question and one merely tells`() {
        // The split that decides whether a node has anything to branch on.
        val deciding = dialogs.filter { definition ->
            definition.ports.any { it.name.value == "confirmed" }
        }
        assertEquals(
            listOf("action.dialog_confirm", "action.dialog_input", "action.dialog_choice"),
            deciding.map { it.typeId.value },
        )
    }
}
