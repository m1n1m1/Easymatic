package io.github.m1n1m1.easymatic.domain.model

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How one node differs from its model profile about what the AI may do.
 *
 * **The property everything else rests on is that a row left alone contributes
 * nothing.** A node that stored the effective list instead would look identical today
 * and diverge silently the first time somebody edited the profile — which is the
 * failure this type exists to make impossible, and the only one of its behaviours that
 * cannot be seen by looking at a screen.
 */
class ToolOverridesTest {

    private fun node(typeId: String, vararg pinned: Pair<String, String>) = ToolSpec(
        target = ToolTarget.Node(NodeTypeId(typeId)),
        pinned = pinned.associate { (k, v) -> ConfigKey(k) to v },
    )

    private val profile = listOf(
        node("action.light_scene", "scene" to "sh:hub|SCENE|s1|Dinner"),
        node("action.notify", "title" to "Home"),
    )

    // ---- applying ----------------------------------------------------------------

    @Test
    fun `nothing overridden leaves the profile exactly as it is`() {
        assertEquals(profile, ToolOverrides.parse("").applyTo(profile))
        assertTrue(ToolOverrides.parse("").isEmpty)
    }

    /** The request in one test: the author stops fixing the scene, the model chooses it. */
    @Test
    fun `an entry with nothing pinned replaces one that pinned something`() {
        val applied = ToolOverrides.parse("action.light_scene").applyTo(profile)
        val scene = applied.single { it.target == ToolTarget.Node(NodeTypeId("action.light_scene")) }
        assertEquals(emptyMap<ConfigKey, String>(), scene.pinned)
        // The rest of the profile is untouched.
        assertEquals("Home", applied.single { it.target != scene.target }.pinned[ConfigKey("title")])
    }

    @Test
    fun `a removal takes a tool the profile grants`() {
        val applied = ToolOverrides.parse("-action.notify").applyTo(profile)
        assertEquals(listOf(ToolTarget.Node(NodeTypeId("action.light_scene"))), applied.map { it.target })
    }

    @Test
    fun `an entry the profile does not grant is added`() {
        val applied = ToolOverrides.parse("action.send_sms").applyTo(profile)
        assertEquals(3, applied.size)
        assertTrue(applied.any { it.target == ToolTarget.Node(NodeTypeId("action.send_sms")) })
    }

    /**
     * The profile's order is kept and only a genuinely new entry lands at the end. The
     * tool list is sent to the model on every turn, so a list that reshuffled whenever
     * a pin changed would move what is cheapest to cache.
     */
    @Test
    fun `a replaced entry stays where the profile had it`() {
        val applied = ToolOverrides.parse("action.light_scene\naction.send_sms").applyTo(profile)
        assertEquals(
            listOf("action.light_scene", "action.notify", "action.send_sms"),
            applied.map { (it.target as ToolTarget.Node).typeId.value },
        )
    }

    // ---- writing the difference ---------------------------------------------------

    /** What the editor writes when the user changed nothing: nothing. */
    @Test
    fun `an unchanged list produces no override at all`() {
        assertTrue(ToolOverrides.between(profile, profile).isEmpty)
        assertEquals("", ToolOverrides.between(profile, profile).encode())
    }

    @Test
    fun `unpinning a field is written as a replacement and nothing else`() {
        val wanted = listOf(node("action.light_scene"), profile[1])
        val overrides = ToolOverrides.between(profile, wanted)
        assertEquals(listOf(ToolTarget.Node(NodeTypeId("action.light_scene"))), overrides.replaced.map { it.target })
        assertTrue(overrides.removed.isEmpty())
    }

    @Test
    fun `unticking a tool the profile grants is written as a removal`() {
        val overrides = ToolOverrides.between(profile, listOf(profile[0]))
        assertEquals(setOf(ToolTarget.Node(NodeTypeId("action.notify"))), overrides.removed)
        assertTrue(overrides.replaced.isEmpty())
    }

    @Test
    fun `ticking a tool the profile lacks is written as an addition`() {
        val added = node("action.send_sms")
        val overrides = ToolOverrides.between(profile, profile + added)
        assertEquals(listOf(added), overrides.replaced)
    }

    /**
     * **Round-trip, which is what makes "reset" trustworthy.** Whatever the editor
     * computes as the difference must re-apply to the same effective list, or a form
     * reopened after a save would show something else.
     */
    @Test
    fun `a difference re-applies to the list it was computed from`() {
        val wanted = listOf(node("action.light_scene"), node("action.send_sms", "to" to "123"))
        val encoded = ToolOverrides.between(profile, wanted).encode()
        assertEquals(wanted.toSet(), ToolOverrides.parse(encoded).applyTo(profile).toSet())
    }

    @Test
    fun `applying twice changes nothing the second time`() {
        val overrides = ToolOverrides.parse("action.light_scene\n-action.notify")
        val once = overrides.applyTo(profile)
        assertEquals(once, overrides.applyTo(once))
    }

    // ---- degradation ---------------------------------------------------------------

    /** [ToolSpec.parse]'s rule: this text is written a keystroke at a time. */
    @Test
    fun `a malformed line is ignored rather than taking the rest down`() {
        val overrides = ToolOverrides.parse("\n   \nnot-a-typeid\n-\naction.notify")
        assertEquals(listOf(ToolTarget.Node(NodeTypeId("action.notify"))), overrides.replaced.map { it.target })
        assertTrue(overrides.removed.isEmpty())
    }

    /** Both lines cannot be honoured, and refusing is the safe reading of the ambiguity. */
    @Test
    fun `a target named in both directions is removed`() {
        val overrides = ToolOverrides.parse("action.notify\n-action.notify")
        assertTrue(overrides.replaced.isEmpty())
        assertEquals(setOf(ToolTarget.Node(NodeTypeId("action.notify"))), overrides.removed)
        assertTrue(overrides.applyTo(profile).none { it.target == ToolTarget.Node(NodeTypeId("action.notify")) })
    }

    @Test
    fun `overrides reports which targets it speaks for`() {
        val overrides = ToolOverrides.parse("action.light_scene\n-action.notify")
        assertTrue(overrides.overrides(ToolTarget.Node(NodeTypeId("action.light_scene"))))
        assertTrue(overrides.overrides(ToolTarget.Node(NodeTypeId("action.notify"))))
        assertFalse(overrides.overrides(ToolTarget.Node(NodeTypeId("action.send_sms"))))
    }

    /**
     * A deleted profile answers with no tools, so a node still offers exactly what it
     * overrode rather than nothing — the failure worth reporting is the missing model,
     * and `RoutingAi` names it.
     */
    @Test
    fun `overrides survive a profile that is gone`() {
        val applied = ToolOverrides.parse("action.notify\n-action.light_scene").applyTo(emptyList())
        assertEquals(listOf(ToolTarget.Node(NodeTypeId("action.notify"))), applied.map { it.target })
    }
}
