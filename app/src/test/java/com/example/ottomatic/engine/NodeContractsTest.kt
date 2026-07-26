package com.example.ottomatic.engine

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.HttpMethod
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.HttpResponseItem
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.asTyped
import com.example.ottomatic.engine.action.HttpAction
import com.example.ottomatic.engine.action.IfAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the derived node contract: a `@Wired` config property prefers the
 * item on its port over its form value, and a definition encodes typed results
 * onto the ports it declares.
 */
class NodeContractsTest {

    @Test
    fun `wired input overrides its typed config fallback`() {
        val definition = HttpAction().definition

        val decoded = definition.schema.decode(
            node = node(config = mapOf("url" to "https://configured.example")),
            data = mapOf(PortName("url") to Item.of("https://wired.example")),
        )

        assertEquals("https://wired.example", decoded.url)
        assertEquals(HttpMethod.GET, decoded.method)
    }

    @Test
    fun `config value is used when the port is unwired`() {
        val decoded = HttpAction().definition.schema.decode(
            node = node(config = mapOf("url" to "https://configured.example", "method" to "POST")),
        )

        assertEquals("https://configured.example", decoded.url)
        assertEquals(HttpMethod.POST, decoded.method)
    }

    @Test
    fun `unset properties fall back to their declared defaults`() {
        val decoded = HttpAction().definition.schema.decode(node())

        assertEquals("https://example.com", decoded.url)
        assertEquals(HttpMethod.GET, decoded.method)
        assertEquals("", decoded.body)
    }

    @Test
    fun `definition encodes a typed output on its declared port`() {
        val output = HttpAction().definition.encode(
            NodeOutput(HttpResponseItem(statusCode = 200, body = "ok", headers = emptyMap())),
        )

        assertEquals(listOf(PortName("out")), output.execOut)
        assertEquals(200, output.dataOut.getValue(PortName("response")).asTyped<HttpResponseItem>().statusCode)
    }

    @Test
    fun `a branching node maps its typed branch to the matching execution port`() {
        val definition = IfAction().definition

        val trueOutput = definition.encodeDynamic(NodeOutput(emptyMap(), ExecutionRoute.TRUE))
        val falseOutput = definition.encodeDynamic(NodeOutput(emptyMap(), ExecutionRoute.FALSE))

        assertEquals(listOf(PortName("true")), trueOutput.execOut)
        assertEquals(listOf(PortName("false")), falseOutput.execOut)
        assertTrue(trueOutput.dataOut.isEmpty())
        assertFalse(falseOutput.halt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a branching node cannot route to the plain out port`() {
        IfAction().definition.encodeDynamic(NodeOutput(emptyMap(), ExecutionRoute.OUT))
    }

    private fun node(config: Map<String, String> = emptyMap()) = WorkflowNode(
        id = NodeId("node"),
        typeId = NodeTypeId("action.http"),
        name = "HTTP",
        x = 0f,
        y = 0f,
        config = config.mapKeys { ConfigKey(it.key) },
    )
}
