package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.feature.permissions.descriptionRes
import com.example.ottomatic.feature.permissions.rationaleRes
import com.example.ottomatic.feature.permissions.titleRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `action.camera_photo`'s wiring, and the declarations that would otherwise degrade in
 * silence.
 *
 * Its own file rather than lines in `ImageRegistryTest` for `ScreenshotRegistryTest`'s
 * reason, which applies twice over here: this is the second node under Photos that does
 * **not** declare the media read, and it is the *first* node in the app to declare `CAMERA`
 * — a grant whose constant spent its whole life documented as "declared by no node".
 */
class CameraPhotoRegistryTest {

    private val action = NodeTypeId("action.camera_photo")

    private fun definition() = requireNotNull(NodeTypeRegistry.byId(action))

    @Test
    fun `it is registered`() {
        assertNotNull("$action must reach NodeTypeRegistry", NodeTypeRegistry.byId(action))
        assertNotNull("$action must reach ActionRegistry", ActionRegistry.byId(action))
    }

    /**
     * `NodeIcon.IMAGE` would be wrong and would look right: it is the family's icon for a
     * picture that already exists, and every other assertion here would still pass.
     */
    @Test
    fun `kind, category and icon agree`() {
        val def = definition()
        assertEquals(NodeKind.ACTION, def.kind)
        assertEquals(NodeCategory.IMAGES, def.category)
        assertEquals("this node makes a photograph, so it gets the lens", NodeIcon.CAMERA, def.icon)
    }

    /**
     * It declares `CAMERA` and **nothing else**.
     *
     * `READ_MEDIA_IMAGES` is deliberately absent: the node writes a row this app creates,
     * which needs no media grant on any version. Declaring it would put a permanent amber
     * badge in the Problems panel on a node that works perfectly — `action.screenshot`'s
     * point, restated because this is where somebody adds it "for consistency" with the
     * other five image nodes.
     */
    @Test
    fun `it declares camera access only`() {
        val declared = definition().permissionRequirements
        assertEquals(1, declared.size)
        assertEquals(PrerequisiteType.RUNTIME, declared.single().type)
        assertEquals(Permissions.CAMERA.manifest, declared.single().manifestPermission)
    }

    /**
     * Nothing here can need Android's write confirmation, so the overlay grant would be a
     * false badge — `action.image_edit`'s reasoning, for the same reason: everything this
     * node creates is a row Ottomatic owns.
     */
    @Test
    fun `it does not declare the overlay grant`() {
        val declared = definition().permissionRequirements.any { it.type == PrerequisiteType.OVERLAY }
        assertFalse("a row we create needs no confirmation, so OVERLAY would be a false badge", declared)
    }

    /**
     * Its copy resolves — all three pieces, because they fail in three different silent ways.
     *
     * A missing rationale renders **no card at all** on the node's config panel; a missing
     * title or description leaves a blank row on the Permissions screen. None of the three
     * fails at compile time.
     */
    @Test
    fun `its permission copy resolves`() {
        val requirement = definition().permissionRequirements.single()
        assertEquals("camera.photo", requirement.rationaleKey)
        assertNotNull("an unmatched rationaleKey renders no card, silently", rationaleRes(requirement))
        assertNotNull("the Permissions row would have no name", titleRes(requirement))
        assertNotNull("the Permissions row would have no body", descriptionRes(requirement))
    }

    @Test
    fun `it has one exec in, one exec out and a single result port`() {
        val def = definition()
        val execIn = def.ports.filter { it.kind == PortKind.EXECUTION && it.direction == Direction.IN }
        val execOut = def.ports.filter { it.kind == PortKind.EXECUTION && it.direction == Direction.OUT }
        val dataOut = def.ports.filter { it.kind == PortKind.DATA && it.direction == Direction.OUT }

        assertEquals(1, execIn.size)
        assertEquals("nothing here branches: a refusal is data, not a route", 1, execOut.size)
        assertEquals(listOf(PortName("state")), dataOut.map { it.name })
    }

    /**
     * The flash row's visibility rule names a real sibling **and a value that sibling can
     * actually hold**.
     *
     * Unchecked, the second half is the one that bites: a rule pointing at `"BACK"` rather
     * than the `@SerialName` `"back"` compiles, saves, and produces a form row that simply
     * never appears — with nothing anywhere saying why.
     */
    @Test
    fun `the flash row is shown only for the lens that has a flash`() {
        val fields = requireNotNull(ConfigSchemaRegistry.byId(action)).fields
        val flash = requireNotNull(fields.singleOrNull { it.key == ConfigKey("flash") })
        val rule = requireNotNull(flash.visibleWhen)
        assertEquals(ConfigKey("lens"), rule.key)

        val lens = requireNotNull(fields.singleOrNull { it.key == ConfigKey("lens") })
        val options = (lens.type as ConfigFieldType.ENUM).options.map { it.value }.toSet()
        assertTrue(
            "a visibility value its controller can never hold hides the row forever",
            options.containsAll(rule.values),
        )
    }

    /**
     * The back camera is the default, which is also what puts the flash row on screen in an
     * unconfigured form — the two facts are one decision and would drift apart separately.
     */
    @Test
    fun `the default lens is the back one`() {
        val fields = requireNotNull(ConfigSchemaRegistry.byId(action)).fields
        val lens = requireNotNull(fields.singleOrNull { it.key == ConfigKey("lens") })
        assertEquals("back", lens.defaultValue)

        val flash = requireNotNull(fields.singleOrNull { it.key == ConfigKey("flash") })
        assertTrue(
            "the unconfigured form must show the flash row",
            requireNotNull(flash.visibleWhen).values.contains(lens.defaultValue),
        )
    }
}
