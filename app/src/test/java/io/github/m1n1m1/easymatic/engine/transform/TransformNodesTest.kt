package io.github.m1n1m1.easymatic.engine.transform

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.ValueType
import io.github.m1n1m1.easymatic.domain.model.schema.DateTime
import io.github.m1n1m1.easymatic.domain.registry.CONVERT_TO_KEY
import io.github.m1n1m1.easymatic.domain.registry.JSON_READ_TYPE_KEY
import io.github.m1n1m1.easymatic.engine.DefaultExecutionContext
import io.github.m1n1m1.easymatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** The two transforms that do real parsing work, exercised through their config. */
class TransformNodesTest {

    private val context = DefaultExecutionContext(RecordingSystemServices()) {}

    private val weather = """
        {"name":"Vienna","main":{"temp":21.5,"humidity":40},
         "items":[{"price":3},{"price":9}],"ok":true}
    """.trimIndent()

    // region Read from JSON

    @Test
    fun `a nested path reads the value`() {
        assertEquals(21.5, readJson(weather, "main.temp", ValueType.NUMBER))
    }

    @Test
    fun `a numeric segment indexes into an array`() {
        assertEquals(9, readJson(weather, "items.1.price", ValueType.WHOLE_NUMBER))
    }

    @Test
    fun `the bracket spelling means the same as the dotted one`() {
        assertEquals(3, readJson(weather, "items[0].price", ValueType.WHOLE_NUMBER))
    }

    @Test
    fun `a string comes out unquoted`() {
        assertEquals("Vienna", readJson(weather, "name", ValueType.TEXT))
    }

    @Test
    fun `an object on the path comes out as JSON so it can be read again`() {
        val nested = readJson(weather, "main", ValueType.TEXT) as String
        assertEquals(40, readJson(nested, "humidity", ValueType.WHOLE_NUMBER))
    }

    @Test
    fun `a missing path lands on the fallback`() {
        assertEquals(-1, readJson(weather, "main.pressure", ValueType.WHOLE_NUMBER, fallback = "-1"))
    }

    @Test
    fun `malformed json lands on the fallback rather than throwing`() {
        assertEquals("none", readJson("not json at all", "main.temp", ValueType.TEXT, fallback = "none"))
    }

    @Test
    fun `an empty path yields the whole document`() {
        assertEquals(true, readJson("true", "", ValueType.YES_OR_NO))
    }

    @Test
    fun `a date is read from either spelling an API uses`() {
        // Both go through the shared text form, so the leniency of `DateTime.parse`
        // is what saves the user from doing arithmetic on a foreign payload.
        assertEquals(
            DateTime(1_753_617_791_000),
            readJson("""{"dt":1753617791}""", "dt", ValueType.DATE_TIME),
        )
        assertEquals(
            DateTime(0),
            readJson("""{"dt":"1970-01-01T00:00:00Z"}""", "dt", ValueType.DATE_TIME),
        )
        assertEquals(
            DateTime.EPOCH,
            readJson("""{"dt":"whenever"}""", "dt", ValueType.DATE_TIME),
        )
    }

    private fun readJson(
        json: String,
        path: String,
        type: ValueType,
        fallback: String = "",
    ): Any? = runBlocking {
        val node = WorkflowNode(
            NodeId("j"), NodeTypeId("transform.json_read"), "Read", 0f, 0f,
            config = mapOf(
                ConfigKey("json") to json,
                ConfigKey("path") to path,
                JSON_READ_TYPE_KEY to type.name,
                ConfigKey("fallback") to fallback,
            ),
        )
        JsonReadTransform().transformRaw(node, emptyMap(), context)?.value
    }

    // endregion

    // region Build text

    @Test
    fun `slots are replaced by their values`() {
        assertEquals("Battery is 43% on Wi-Fi", buildText("Battery is {A}% on {B}", a = "43", b = "Wi-Fi"))
    }

    @Test
    fun `slot names are case and space insensitive`() {
        assertEquals("43-43", buildText("{A}-{ a }", a = "43"))
    }

    @Test
    fun `an unfilled slot disappears instead of printing its own name`() {
        assertEquals("Battery is %", buildText("Battery is {B}%", a = "43"))
    }

    @Test
    fun `a template with no slots is passed through`() {
        assertEquals("just words", buildText("just words", a = "43"))
    }

    private fun buildText(template: String, a: String = "", b: String = "", c: String = ""): String? =
        runBlocking {
            val node = WorkflowNode(
                NodeId("t"), NodeTypeId("transform.text"), "Text", 0f, 0f,
                config = mapOf(
                    ConfigKey("template") to template,
                    ConfigKey("a") to a,
                    ConfigKey("b") to b,
                    ConfigKey("c") to c,
                ),
            )
            BuildTextTransform().transformRaw(node, emptyMap(), context)?.value as String?
        }

    // endregion

    @Test
    fun `convert reads its target type from config`() = runBlocking {
        val node = WorkflowNode(
            NodeId("c"), NodeTypeId("transform.convert"), "Convert", 0f, 0f,
            config = mapOf(CONVERT_TO_KEY to ValueType.WHOLE_NUMBER.name, ConfigKey("fallback") to "5"),
        )
        assertEquals(5, ConvertTransform().transformRaw(node, emptyMap(), context)?.value)
    }
}
