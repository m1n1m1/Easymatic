package com.example.ottomatic.engine

import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.HttpResponseItem
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.asTyped
import com.example.ottomatic.engine.action.ConditionAction
import com.example.ottomatic.engine.action.HttpAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeContractsTest {

    @Test
    fun `wired text input overrides its typed config fallback`() {
        val input = NodeInput(
            node = node(config = mapOf("url" to "https://configured.example")),
            data = mapOf("url" to Item.of("https://wired.example")),
        )

        val decoded = HttpAction().contract.input(input)

        assertEquals("https://wired.example", decoded.url)
        assertEquals("GET", decoded.method)
    }

    @Test
    fun `contract encodes typed http output on its declared port`() {
        val output = HttpAction().contract.output(
            NodeOutput(HttpResponseItem(statusCode = 200, body = "ok", headers = emptyMap())),
        )

        assertEquals(listOf("out"), output.execOut)
        assertEquals(200, output.dataOut.getValue("response").asTyped<HttpResponseItem>().statusCode)
    }

    @Test
    fun `condition contract maps its typed branch to the matching execution port`() {
        val trueOutput = ConditionAction().contract.output(NodeOutput(true, ExecutionRoute.True))
        val falseOutput = ConditionAction().contract.output(NodeOutput(false, ExecutionRoute.False))

        assertEquals(listOf("true"), trueOutput.execOut)
        assertEquals(listOf("false"), falseOutput.execOut)
        assertTrue(trueOutput.dataOut.isEmpty())
        assertFalse(falseOutput.halt)
    }

    private fun node(config: Map<String, String> = emptyMap()) = WorkflowNode(
        id = "node",
        typeId = "action.http",
        name = "HTTP",
        x = 0f,
        y = 0f,
        config = config,
    )
}
