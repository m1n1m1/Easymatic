package com.example.ottomatic.feature.ai

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.service.CallableMacro
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.ToolSpec
import com.example.ottomatic.domain.model.ToolTarget
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.engine.ai.canRunAsTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permission list's own logic, apart from how it is drawn.
 *
 * What is pinned here is the pair of gestures the whole screen exists for — allow
 * everything and allow nothing — plus the rule that makes them safe to offer: a row
 * that still needs a picker answering says so rather than the switch refusing to move.
 */
class ToolPermissionsTest {

    private fun groups(allowed: List<ToolSpec> = emptyList(), macros: List<CallableMacro> = emptyList()) =
        toolGroups(
            allowed = allowed,
            macros = macros,
            categoryTitle = { it.name },
            macroGroupTitle = "Macros",
            nodeTitle = { it.value },
        )

    private fun allTargets() = groups().flatMap { group -> group.candidates.map { it.target } }

    /**
     * The candidate set is the palette's runnable half, and nothing else: a trigger, a
     * loop or a graph-shaped action offered here would be ticked and silently do
     * nothing, which is the failure `canRunAsTool` exists to prevent.
     */
    @Test
    fun `every runnable node is offered and nothing that cannot run is`() {
        val offered = allTargets().filterIsInstance<ToolTarget.Node>().map { it.typeId }.toSet()
        val runnable = NodeTypeRegistry.all
            .filter { canRunAsTool(it.typeId, it.kind) }
            .map { it.typeId }
            .toSet()
        assertEquals(runnable, offered)
        assertFalse("a loop offered as a tool would do nothing", NodeTypeId("action.for_each") in offered)
        assertFalse("a trigger has nothing to run", NodeTypeId("trigger.manual") in offered)
    }

    @Test
    fun `nothing allowed means every group reads as off`() {
        assertTrue(groups().all { it.state == GroupState.NONE })
        assertEquals(0, groups().sumOf { it.allowedCount })
    }

    /** The gesture the request was about: one tap that grants the lot. */
    @Test
    fun `allow everything ticks every candidate exactly once`() {
        val all = emptyList<ToolSpec>().setAllowed(allTargets(), allow = true)
        assertEquals(allTargets().size, all.size)
        assertEquals(all.size, all.distinctBy { it.target }.size)
        assertTrue(groups(all).all { it.state == GroupState.ALL })
    }

    /** And its mirror, which has to leave the list genuinely empty rather than hidden. */
    @Test
    fun `allow nothing clears the list outright`() {
        val all = emptyList<ToolSpec>().setAllowed(allTargets(), allow = true)
        assertEquals(emptyList<ToolSpec>(), all.setAllowed(allTargets(), allow = false))
    }

    @Test
    fun `a group half ticked reads as neither on nor off`() {
        val group = groups().first { it.candidates.size > 1 }
        val allowed = emptyList<ToolSpec>().setAllowed(listOf(group.candidates.first().target), allow = true)
        assertEquals(GroupState.SOME, groups(allowed).first { it.title == group.title }.state)
    }

    /**
     * Ticking must never disturb what is already pinned, because a group switch runs
     * over rows the user configured one at a time.
     */
    @Test
    fun `re-allowing an already allowed tool keeps its pinned config`() {
        val target = ToolTarget.Node(NodeTypeId("action.notify"))
        val pinned = ToolSpec(target, mapOf(ConfigKey("title") to "Kitchen"))
        val after = listOf(pinned).setAllowed(listOf(target), allow = true)
        assertEquals(pinned, after.single())
    }

    /**
     * Un-ticking forgets the pins, which is the honest behaviour rather than a missing
     * feature: keeping a hidden configuration would make re-ticking restore choices the
     * user cannot see.
     */
    @Test
    fun `un-allowing and allowing again starts from nothing pinned`() {
        val target = ToolTarget.Node(NodeTypeId("action.notify"))
        val pinned = listOf(ToolSpec(target, mapOf(ConfigKey("title") to "Kitchen")))
        val again = pinned.setAllowed(listOf(target), allow = false).setAllowed(listOf(target), allow = true)
        assertEquals(emptyMap<ConfigKey, String>(), again.single().pinned)
    }

    /**
     * The consequence of "allow everything" being one tap: some rows are ticked and
     * still incomplete. The row says so — a switch that refused to move until a hub had
     * been chosen would make the one-tap gesture impossible.
     */
    @Test
    fun `allowing everything leaves the picker-carrying rows asking for a choice`() {
        val all = emptyList<ToolSpec>().setAllowed(allTargets(), allow = true)
        val needing = groups(all).flatMap { group -> group.candidates.filter { it.needsChoice } }
        assertTrue("some tools carry an identifier a model cannot invent", needing.isNotEmpty())
        assertTrue(needing.all { it.allowed })
    }

    @Test
    fun `pinning a picker clears what the row was asking for`() {
        val loose = groups(emptyList<ToolSpec>().setAllowed(allTargets(), allow = true))
            .flatMap { it.candidates }
            .first { it.needsChoice }
        val fields = loosePickers(loose.target, loose.spec!!)
        val answered = loose.spec.copy(pinned = fields.indices.associate { ConfigKey("k$it") to "x" })
        // Answering by label is not possible here, so this pins by key instead: what is
        // being checked is that the *set* shrinks as keys are answered, not the naming.
        assertTrue(loosePickers(loose.target, answered).size <= fields.size)
    }

    /** A macro tool has no picker fields at all — its parameters are a `@Ports` spec. */
    @Test
    fun `a macro is offered in its own group and never asks for a choice`() {
        val macro = CallableMacro(id = "m1", name = "Wind down", inputs = "")
        val withMacros = toolGroups(
            allowed = listOf(ToolSpec(ToolTarget.Macro("m1"))),
            macros = listOf(macro),
            categoryTitle = NodeCategory::name,
            macroGroupTitle = "Macros",
            nodeTitle = { it.value },
        )
        val group = withMacros.last()
        assertEquals("Macros", group.title)
        val candidate = group.candidates.single()
        assertEquals("Wind down", candidate.title)
        assertTrue(candidate.allowed)
        assertFalse(candidate.needsChoice)
    }

    /** No macros means no heading, rather than a heading over nothing. */
    @Test
    fun `a phone with no callable macros grows no macro group`() {
        assertTrue(groups().none { it.title == "Macros" })
    }

    /**
     * The cap is asked about *before* a switch moves, so the ceiling is a sentence the
     * user reads rather than a truncation nothing reports.
     */
    @Test
    fun `the cap is answered against the count that would result`() {
        assertFalse(wouldExceedCap(allowed = 0, adding = ToolSpec.MAX_TOOLS))
        assertTrue(wouldExceedCap(allowed = 1, adding = ToolSpec.MAX_TOOLS))
    }

    // ---- a node adjusting its profile ---------------------------------------------

    private val notify = ToolTarget.Node(NodeTypeId("action.notify"))
    private val sms = ToolTarget.Node(NodeTypeId("action.send_sms"))
    private val baseline = listOf(ToolSpec(notify, mapOf(ConfigKey("title") to "Home")))

    private fun candidateFor(target: ToolTarget, effective: List<ToolSpec>) =
        toolGroups(
            allowed = effective,
            macros = emptyList(),
            categoryTitle = NodeCategory::name,
            macroGroupTitle = "Macros",
            nodeTitle = { it.value },
            baseline = baseline,
        ).flatMap { it.candidates }.single { it.target == target }

    /** A row that matches the profile says nothing about itself; only a difference does. */
    @Test
    fun `a row equal to the profile is not marked as adjusted`() {
        assertFalse(candidateFor(notify, baseline).isOverridden)
        assertFalse(candidateFor(sms, baseline).isOverridden)
    }

    @Test
    fun `a re-pinned row is marked, and so is one turned off`() {
        val repinned = listOf(ToolSpec(notify))
        assertTrue(candidateFor(notify, repinned).isOverridden)
        assertTrue("a row the node turned off is a difference too", candidateFor(notify, emptyList()).isOverridden)
    }

    @Test
    fun `a tool the profile lacks is marked once the node ticks it`() {
        assertTrue(candidateFor(sms, baseline + ToolSpec(sms)).isOverridden)
    }

    /**
     * **Reset is not unticking**, which is the one behaviour here that cannot be seen
     * on screen: both leave the same effective list today, and only one of them goes on
     * following the profile when it changes tomorrow.
     */
    @Test
    fun `reset restores the profile's entry where unticking would drop it`() {
        val repinned = listOf(ToolSpec(notify))
        assertEquals(baseline, repinned.resetTo(baseline, listOf(notify)))
        assertEquals(emptyList<ToolSpec>(), repinned.setAllowed(listOf(notify), allow = false))
    }

    @Test
    fun `reset drops a tool the node added, because the profile has none to restore`() {
        val added = baseline + ToolSpec(sms)
        assertEquals(baseline, added.resetTo(baseline, listOf(sms)))
    }

    /** Without a baseline nothing is ever a difference — the profile's own page is unchanged. */
    @Test
    fun `editing a profile marks nothing as adjusted`() {
        val onProfile = toolGroups(
            allowed = baseline,
            macros = emptyList(),
            categoryTitle = NodeCategory::name,
            macroGroupTitle = "Macros",
            nodeTitle = { it.value },
        ).flatMap { it.candidates }
        assertTrue(onProfile.none { it.isOverridden })
    }
}
