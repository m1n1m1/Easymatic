package com.example.ottomatic.data.hue

import com.example.ottomatic.core.service.SmartHomeTargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the bridge's resource model into the shape the pickers use.
 *
 * Two of these pin mappings that fail *silently* rather than loudly: writing to a
 * room's own id is accepted and does nothing, and a light's room is two hops away
 * through its device — doing it in one produces lights with no room, which looks
 * like a cosmetic bug and is actually the picker's whole organisation gone.
 */
class HueResourcesTest {

    private val payload = """
        {"errors":[],"data":[
          {"type":"room","id":"room-1","metadata":{"name":"Kitchen"},
           "children":[{"rid":"dev-1","rtype":"device"}],
           "services":[{"rid":"grp-1","rtype":"grouped_light"}]},
          {"type":"zone","id":"zone-1","metadata":{"name":"Downstairs"},
           "children":[{"rid":"light-1","rtype":"light"}],
           "services":[{"rid":"grp-2","rtype":"grouped_light"}]},
          {"type":"room","id":"room-2","metadata":{"name":"Cupboard"},
           "children":[],"services":[]},
          {"type":"light","id":"light-1","metadata":{"name":"Ceiling"},
           "owner":{"rid":"dev-1","rtype":"device"},
           "on":{"on":true},"dimming":{"brightness":42.5},
           "color":{"xy":{"x":0.4,"y":0.4}},"color_temperature":{"mirek":370}},
          {"type":"light","id":"light-2","metadata":{"name":"Under-cupboard"},
           "owner":{"rid":"dev-2","rtype":"device"},"on":{"on":false}},
          {"type":"scene","id":"scene-1","metadata":{"name":"Dinner"},
           "group":{"rid":"room-1","rtype":"room"}},
          {"type":"entertainment_configuration","id":"ent-1"}
        ]}
    """.trimIndent()

    private val snapshot = HueResources.parseSnapshot(payload)

    /**
     * The single mapping most likely to be got wrong, and the one whose failure is
     * hardest to diagnose: writing to `room-1` is accepted by the bridge and changes
     * nothing at all.
     */
    @Test
    fun `a room is addressed by its grouped light service and not by its own id`() {
        val kitchen = snapshot.first { it.name == "Kitchen" }

        assertEquals(SmartHomeTargetKind.GROUP, kitchen.kind)
        assertEquals("grp-1", kitchen.rid)
    }

    /**
     * A room with nothing in it has no `grouped_light`, so there is no request that
     * could ever reach it. Listing it would offer a target that can only fail.
     */
    @Test
    fun `a room with nothing controllable is left out entirely`() {
        assertNull(snapshot.firstOrNull { it.name == "Cupboard" })
    }

    /**
     * A zone cuts across rooms, which is why it has no room of its own — the picker
     * uses this marker to keep the two in separate sections.
     */
    @Test
    fun `a zone is a group marked as one`() {
        val zone = snapshot.first { it.name == "Downstairs" }

        assertEquals(SmartHomeTargetKind.GROUP, zone.kind)
        assertEquals("grp-2", zone.rid)
        assertEquals(HueResources.ZONE, zone.room)
    }

    /** The light names a device, and the room lists devices. Two hops, not one. */
    @Test
    fun `a light gets its room through the device that owns it`() {
        assertEquals("Kitchen", snapshot.first { it.name == "Ceiling" }.room)
        assertEquals("", snapshot.first { it.name == "Under-cupboard" }.room)
    }

    /**
     * The same asymmetry, in the other direction: a room's members are its
     * children's *lights*, a zone's children already are lights. Getting this wrong
     * makes "only lights already on" quietly change nothing, because it would be
     * asking about device ids that no light answers to.
     */
    @Test
    fun `a group carries the lights inside it, through devices for a room`() {
        assertEquals(listOf("light-1"), snapshot.first { it.name == "Kitchen" }.memberRids)
        assertEquals(listOf("light-1"), snapshot.first { it.name == "Downstairs" }.memberRids)
        assertTrue(snapshot.first { it.name == "Ceiling" }.memberRids.isEmpty())
    }

    @Test
    fun `which lights are lit is read in one request`() {
        val payload = """{"data":[
            {"type":"light","id":"light-1","on":{"on":true}},
            {"type":"light","id":"light-2","on":{"on":false}},
            {"type":"light","id":"light-3"}]}"""

        val on = HueResources.parseOnByRid(payload)

        assertEquals(true, on["light-1"])
        assertEquals(false, on["light-2"])
        // A light reporting no `on` at all is not evidence that it is lit.
        assertEquals(false, on["light-3"])
    }

    /**
     * Carried so the picker can say, before the fact, that Set colour will do
     * nothing here. The bridge accepts a colour it cannot render and reports
     * success.
     */
    @Test
    fun `what a bulb can do is read from the resources it carries`() {
        val ceiling = snapshot.first { it.name == "Ceiling" }
        assertTrue(ceiling.supportsColour)
        assertTrue(ceiling.supportsTemperature)

        val white = snapshot.first { it.name == "Under-cupboard" }
        assertFalse(white.supportsColour)
        assertFalse(white.supportsTemperature)
    }

    /**
     * The room's *name* is what the picker groups by; the room's `grouped_light` is
     * what turning the scene off writes to. Both are worked out here, so the run
     * path never pays two extra GETs for something the snapshot already knows.
     */
    @Test
    fun `a scene carries both the room it belongs to and the way to switch it off`() {
        val scene = snapshot.first { it.kind == SmartHomeTargetKind.SCENE }

        assertEquals("Dinner", scene.name)
        assertEquals("Kitchen", scene.room)
        assertEquals("grp-1", scene.groupRid)
    }

    /** The fallback path, for a snapshot taken before scenes carried their group. */
    @Test
    fun `a scene's group can also be read one resource at a time`() {
        val scenePayload = """{"data":[{"type":"scene","id":"scene-1",
            "group":{"rid":"room-1","rtype":"room"}}]}"""
        val roomPayload = """{"data":[{"type":"room","id":"room-1",
            "services":[{"rid":"grp-1","rtype":"grouped_light"}]}]}"""

        assertEquals("room" to "room-1", HueResources.parseSceneGroup(scenePayload))
        assertEquals("grp-1", HueResources.parseGroupedLight(roomPayload))
        assertNull(HueResources.parseSceneGroup("""{"data":[{"type":"scene","id":"s"}]}"""))
    }

    /**
     * Newer firmware answers with resource types this build has never heard of, and
     * none of them is a reason to show an empty list.
     */
    @Test
    fun `unknown resource types are ignored rather than breaking the read`() {
        // Two groups, two lights and a scene: the entertainment configuration and
        // the empty room are both absent, for different reasons.
        assertEquals(5, snapshot.size)
    }

    @Test
    fun `a malformed payload reads as nothing rather than throwing`() {
        assertTrue(HueResources.parseSnapshot("not json").isEmpty())
        assertTrue(HueResources.parseSnapshot("").isEmpty())
    }

    @Test
    fun `one light's state is read whole`() {
        val state = HueResources.parseState(
            """{"data":[{"type":"light","id":"light-1","metadata":{"name":"Ceiling"},
               "owner":{"rid":"dev-1","rtype":"device"},"on":{"on":true},
               "dimming":{"brightness":42.5},"color":{"xy":{"x":0.4,"y":0.41}},
               "color_temperature":{"mirek":370}}]}""",
        )!!

        assertEquals("Ceiling", state.name)
        assertTrue(state.on)
        assertEquals(42.5, state.brightnessPercent, 0.001)
        assertEquals(0.4, state.x, 0.001)
        assertEquals("dev-1", state.ownerRid)
        assertEquals(370, state.mirek)
    }

    /**
     * A bulb switched off at the wall reports `connectivity_issue`. Anything this
     * build does not recognise is treated as not connected, because an unfamiliar
     * status is not evidence that the lamp is alive.
     */
    @Test
    fun `only a connected device counts as reachable`() {
        val payload = """{"data":[
            {"type":"zigbee_connectivity","owner":{"rid":"dev-1"},"status":"connected"},
            {"type":"zigbee_connectivity","owner":{"rid":"dev-2"},"status":"connectivity_issue"}]}"""

        assertTrue(HueResources.parseReachable(payload, "dev-1"))
        assertFalse(HueResources.parseReachable(payload, "dev-2"))
        assertFalse(HueResources.parseReachable(payload, "dev-3"))
    }

    @Test
    fun `the bridge id is read for the pairing cross-check`() {
        val payload = """{"data":[{"type":"bridge","id":"b1","bridge_id":"001788fffe1234ab"}]}"""

        assertEquals("001788fffe1234ab", HueResources.parseBridgeId(payload))
        assertEquals("", HueResources.parseBridgeId("{}"))
    }
}
