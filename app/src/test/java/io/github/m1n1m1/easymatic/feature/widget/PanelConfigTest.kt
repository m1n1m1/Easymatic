package io.github.m1n1m1.easymatic.feature.widget

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel's configuration round-trip.
 *
 * Worth pinning because the bug it replaces was invisible in exactly this shape:
 * the previous config screen wrote its options and never read them back, so every
 * setting silently reverted the next time the screen opened — and then Save wrote
 * the reverted values over the real ones. Nothing threw, and nothing looked wrong
 * until you reopened the screen.
 *
 * A write followed by a read is the whole contract, and it is testable without a
 * launcher because [PanelConfig] owns both directions.
 */
class PanelConfigTest {

    @Test
    fun `defaults show everything`() {
        val config = PanelConfig()
        assertTrue(config.showStatus)
        assertTrue(config.showProblems)
        assertTrue(config.showLastRun)
        assertTrue(config.showTriggers)
        assertTrue(config.triggersAuto)
        assertFalse(config.isEmpty)
    }

    /** A panel that was never configured reads as the defaults, not as all-off. */
    @Test
    fun `empty preferences decode to the defaults`() {
        assertEquals(PanelConfig(), PanelConfig.from(mutablePreferencesOf()))
    }

    @Test
    fun `every flag survives a round trip`() {
        val original = PanelConfig(
            showStatus = false,
            showProblems = true,
            showLastRun = false,
            showTriggers = true,
            triggersAuto = false,
            triggerKeys = listOf("wf1:n1", "wf2:n7", "wf1:n3"),
        )
        val prefs = mutablePreferencesOf().apply { original.writeTo(this) }
        assertEquals(original, PanelConfig.from(prefs))
    }

    /**
     * The picked order is the point of picking, so it has to survive storage —
     * a set would have quietly reordered the grid on every reconfigure.
     */
    @Test
    fun `picked trigger order is preserved`() {
        val keys = listOf("wf3:n1", "wf1:n1", "wf2:n2")
        val prefs = mutablePreferencesOf().apply {
            PanelConfig(triggerKeys = keys).writeTo(this)
        }
        assertEquals(keys, PanelConfig.from(prefs).triggerKeys)
    }

    /** Keys contain a colon (`workflowId:nodeId`), so the separator must not. */
    @Test
    fun `keys containing colons round trip intact`() {
        val keys = listOf("8b1f-4c2a:node:with:colons", "wf:n")
        val prefs = mutablePreferencesOf().apply {
            PanelConfig(triggerKeys = keys).writeTo(this)
        }
        assertEquals(keys, PanelConfig.from(prefs).triggerKeys)
    }

    @Test
    fun `no keys stored decodes to an empty list rather than a blank entry`() {
        val prefs = mutablePreferencesOf().apply { PanelConfig(triggerKeys = emptyList()).writeTo(this) }
        assertEquals(emptyList<String>(), PanelConfig.from(prefs).triggerKeys)
    }

    @Test
    fun `a panel with every section off reports itself empty`() {
        val config = PanelConfig(
            showStatus = false,
            showProblems = false,
            showLastRun = false,
            showTriggers = false,
        )
        assertTrue(config.isEmpty)
    }

    @Test
    fun `auto mode takes every trigger and picked mode takes the chosen order`() {
        val all = listOf(ref("wf1", "n1"), ref("wf2", "n2"), ref("wf3", "n3"))

        assertEquals(all, PanelConfig(triggersAuto = true).selectTriggers(all))

        val picked = PanelConfig(triggersAuto = false, triggerKeys = listOf("wf3:n3", "wf1:n1"))
        assertEquals(listOf("wf3:n3", "wf1:n1"), picked.selectTriggers(all).map { it.key })
    }

    /**
     * A picked key whose macro was deleted is dropped rather than left as a gap.
     * A panel is a set of buttons, and a hole where a macro used to be is not one.
     */
    @Test
    fun `picked keys that no longer resolve are dropped`() {
        val all = listOf(ref("wf1", "n1"))
        val config = PanelConfig(triggersAuto = false, triggerKeys = listOf("gone:n9", "wf1:n1"))
        assertEquals(listOf("wf1:n1"), config.selectTriggers(all).map { it.key })
    }

    private fun ref(workflowId: String, nodeId: String) = ManualTriggerRef(
        workflowId = workflowId,
        nodeId = nodeId,
        macroName = "Macro",
        label = "Run",
        icon = io.github.m1n1m1.easymatic.domain.model.MacroIcon.BOLT,
        accent = io.github.m1n1m1.easymatic.domain.model.MacroAccent.SYSTEM,
        enabled = true,
    )
}
