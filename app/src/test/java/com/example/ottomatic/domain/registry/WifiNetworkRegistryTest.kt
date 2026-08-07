package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.engine.trigger.WifiNetworkTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies `trigger.wifi_network` and `value.wifi_network` are wired into every
 * central registry, and pins the two declarations that would otherwise degrade
 * silently: the network field's editor, and the grant that names a network.
 */
class WifiNetworkRegistryTest {

    @Test
    fun `the trigger is registered with the correct typeId`() {
        val trigger = TriggerRegistry.byId(WifiNetworkTrigger.TYPE_ID)
        assertNotNull("trigger.wifi_network must be registered in TriggerRegistry", trigger)
        assertEquals(WifiNetworkTrigger.TYPE_ID, trigger!!.typeId)
    }

    @Test
    fun `node type definition is a trigger exposing one exec out and one typed data out`() {
        val def = NodeTypeRegistry.byId(WifiNetworkTrigger.TYPE_ID)
        assertNotNull("trigger.wifi_network must be registered in NodeTypeRegistry", def)
        assertEquals(NodeKind.TRIGGER, def!!.kind)
        val exec = def.ports.filter { it.kind == PortKind.EXECUTION }
        val dataOut = def.ports.filter { it.kind == PortKind.DATA && it.direction == Direction.OUT }
        val dataIn = def.ports.filter { it.kind == PortKind.DATA && it.direction == Direction.IN }
        assertEquals("exactly one EXECUTION port", 1, exec.size)
        assertEquals(PortName("out"), exec.first().name)
        assertEquals("exactly one DATA output port named 'network'", 1, dataOut.size)
        assertEquals(PortName("network"), dataOut.first().name)
        assertTrue("triggers must not expose data inputs", dataIn.isEmpty())
    }

    /**
     * Both defaults mean "no filter", which is what makes an untouched node run on
     * every connect and disconnect rather than on nothing at all.
     */
    @Test
    fun `config schema declares the network and event filters, both unset by default`() {
        val schema = ConfigSchemaRegistry.byId(WifiNetworkTrigger.TYPE_ID)
        assertNotNull("trigger.wifi_network must have a config schema", schema)
        val byKey = schema!!.fields.associateBy { it.key }
        assertEquals("", byKey[ConfigKey("ssid")]?.defaultValue)
        assertEquals("", byKey[ConfigKey("event")]?.defaultValue)
    }

    /**
     * The assertion that fails when somebody drops the annotation while editing the
     * config class: without it the field degrades into a plain text box that stores
     * the same string, so nothing else in the app would notice the chooser had gone.
     */
    @Test
    fun `the network is typed with a chooser beside it, not picked from a list`() {
        val schema = ConfigSchemaRegistry.byId(WifiNetworkTrigger.TYPE_ID)!!
        val field = schema.fields.first { it.key == ConfigKey("ssid") }
        assertEquals(ConfigFieldType.WIFI_NETWORK, field.type)
    }

    @Test
    fun `network port schema is derived from WifiNetworkEvent`() {
        val def = NodeTypeRegistry.byId(WifiNetworkTrigger.TYPE_ID)!!
        val port = def.ports.first { it.name == PortName("network") }
        val schema = port.schema as ItemSchema.Object
        assertEquals(setOf("event", "ssid", "timestamp"), schema.fields.keys)
    }

    /**
     * Location, on a Wi-Fi node, which reads like a mistake until you know that
     * knowing which network you are on is knowing roughly where you are.
     */
    @Test
    fun `both nodes declare the grant that names a network`() {
        for (typeId in listOf(WifiNetworkTrigger.TYPE_ID, VALUE_TYPE_ID)) {
            val requirements = NodeTypeRegistry.byId(typeId)!!.permissionRequirements
            assertEquals(
                "$typeId must declare exactly the fine-location grant",
                listOf(Permissions.ACCESS_FINE_LOCATION.manifest),
                requirements.map { it.manifestPermission },
            )
            assertEquals(PrerequisiteType.RUNTIME, requirements.single().type)
        }
    }

    /**
     * The value node this change made possible, and the reason the purity contract
     * dropped its no-permissions clause: a grant is not an effect, and declaring it
     * is what puts the node in the Problems panel and the Permissions screen.
     */
    @Test
    fun `the value reads the network name as text and needs no ports to do it`() {
        val def = NodeTypeRegistry.byId(VALUE_TYPE_ID)
        assertNotNull("value.wifi_network must be registered in NodeTypeRegistry", def)
        assertEquals(NodeKind.VALUE, def!!.kind)
        assertTrue("a value declares no exec ports", def.ports.none { it.kind == PortKind.EXECUTION })
        val output = def.ports.single()
        assertEquals(PortName("ssid"), output.name)
        assertEquals(ItemSchema.Primitive(String::class), output.schema)
    }

    private companion object {
        val VALUE_TYPE_ID = NodeTypeId("value.wifi_network")
    }
}
