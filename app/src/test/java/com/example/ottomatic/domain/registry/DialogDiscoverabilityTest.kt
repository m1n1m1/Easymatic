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
        // is what somebody reaches for before they need an answer at all.
        val names = NodeTypeRegistry.all.filter { it.category == NodeCategory.INTERACTION }.map { it.typeId }
        assertEquals(dialogs.map { it.typeId }, names)
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
