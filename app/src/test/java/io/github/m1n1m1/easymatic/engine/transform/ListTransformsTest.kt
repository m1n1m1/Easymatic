package io.github.m1n1m1.easymatic.engine.transform

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.schema.Item
import io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema
import io.github.m1n1m1.easymatic.domain.model.schema.asText
import io.github.m1n1m1.easymatic.domain.registry.LIST_IN
import io.github.m1n1m1.easymatic.engine.DefaultExecutionContext
import io.github.m1n1m1.easymatic.engine.ExecutableTransform
import io.github.m1n1m1.easymatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pull-side list operations.
 *
 * Each is a pure function, so the tests call them directly with an [Item] on the
 * list port rather than going through a graph — the graph half (how the output port
 * gets its type) is [io.github.m1n1m1.easymatic.domain.registry.EffectivePortsTest]'s
 * job.
 */
class ListTransformsTest {

    private val context = DefaultExecutionContext(RecordingSystemServices()) {}

    private fun textList(vararg values: String): Item =
        Item(values.toList(), ItemSchema.ListSchema(ItemSchema.Primitive(String::class)))

    private suspend fun ExecutableTransform.on(
        list: Item?,
        config: Map<ConfigKey, String> = emptyMap(),
    ): Item? {
        val node = WorkflowNode(NodeId("n"), NodeTypeId(typeId.value), "n", 0f, 0f, config = config)
        val data = list?.let { mapOf(LIST_IN to it) }.orEmpty()
        return transformRaw(node, data, context)
    }

    @Test
    fun `count answers how many`() = runBlocking {
        assertEquals(3, ListCountTransform().on(textList("a", "b", "c"))?.value)
    }

    @Test
    fun `count of nothing wired is zero, not a failure`() = runBlocking {
        // A pull-side read has no execution wire to route a failure down, so the
        // honest answer for an unwired list is the empty one.
        assertEquals(0, ListCountTransform().on(null)?.value)
    }

    @Test
    fun `join puts the list back together`() = runBlocking {
        val joined = ListJoinTransform().on(textList("a", "b"), mapOf(ConfigKey("separator") to " and "))
        assertEquals("a and b", joined?.value)
    }

    @Test
    fun `item at reads one element, keeping its type`() = runBlocking {
        val numbers = Item(listOf(10, 20, 30), ItemSchema.ListSchema(ItemSchema.Primitive(Int::class)))
        val found = ListItemTransform().on(numbers, mapOf(ConfigKey("index") to "1"))
        assertEquals(20, found?.value)
        assertEquals(ItemSchema.Primitive(Int::class), found?.schema)
    }

    @Test
    fun `item past the end reads as nothing so the consumer falls back`() = runBlocking {
        assertNull(ListItemTransform().on(textList("a"), mapOf(ConfigKey("index") to "5")))
    }

    @Test
    fun `contains and position agree with each other`() = runBlocking {
        val list = textList("red", "green", "blue")
        val find = mapOf(ConfigKey("value") to "green")
        assertEquals(true, ListContainsTransform().on(list, find)?.value)
        assertEquals(1, ListIndexOfTransform().on(list, find)?.value)
    }

    @Test
    fun `a missing item is not contained and has position -1`() = runBlocking {
        val find = mapOf(ConfigKey("value") to "purple")
        assertEquals(false, ListContainsTransform().on(textList("red"), find)?.value)
        assertEquals(-1, ListIndexOfTransform().on(textList("red"), find)?.value)
    }

    @Test
    fun `sorting as text and as numbers give different, both-correct answers`() = runBlocking {
        // The whole reason "compare as" is a question rather than a guess.
        val list = textList("10", "9", "100")
        val asText = ListSortTransform().on(list, mapOf(ConfigKey("compareAs") to "TEXT"))
        assertEquals(listOf("10", "100", "9"), asText?.value)
        val asNumber = ListSortTransform().on(list, mapOf(ConfigKey("compareAs") to "NUMBER"))
        assertEquals(listOf("9", "10", "100"), asNumber?.value)
    }

    @Test
    fun `sorting descending reverses the order`() = runBlocking {
        val sorted = ListSortTransform().on(
            textList("b", "a", "c"),
            mapOf(ConfigKey("direction") to "DESCENDING"),
        )
        assertEquals(listOf("c", "b", "a"), sorted?.value)
    }

    @Test
    fun `a sorted list keeps its element type`() = runBlocking {
        val sorted = ListSortTransform().on(textList("b", "a"))
        assertEquals(ItemSchema.ListSchema(ItemSchema.Primitive(String::class)), sorted?.schema)
    }

    @Test
    fun `slice takes a run out of the middle`() = runBlocking {
        val part = ListSliceTransform().on(
            textList("a", "b", "c", "d"),
            mapOf(ConfigKey("from") to "1", ConfigKey("count") to "2"),
        )
        assertEquals(listOf("b", "c"), part?.value)
    }

    @Test
    fun `slice past the end is clamped rather than refused`() = runBlocking {
        // "The first three" has to keep working on a list that turned out to have two.
        val part = ListSliceTransform().on(
            textList("a", "b"),
            mapOf(ConfigKey("from") to "0", ConfigKey("count") to "3"),
        )
        assertEquals(listOf("a", "b"), part?.value)
    }

    @Test
    fun `splitting text on a separator trims and drops the blanks`() = runBlocking {
        val split = SplitTextTransform().on(
            list = null,
            config = mapOf(ConfigKey("text") to "a, b , c,", ConfigKey("separator") to ","),
        )
        assertEquals(listOf("a", "b", "c"), split?.value)
    }

    @Test
    fun `splitting with no separator reads one item per line`() = runBlocking {
        // The default that makes the multiline field double as a list literal.
        val split = SplitTextTransform().on(null, mapOf(ConfigKey("text") to "one\ntwo\nthree"))
        assertEquals(listOf("one", "two", "three"), split?.value)
    }

    @Test
    fun `a split list is a list of text, not one piece of text`() = runBlocking {
        val split = SplitTextTransform().on(null, mapOf(ConfigKey("text") to "a\nb"))
        assertTrue(split?.schema.toString(), split?.schema is ItemSchema.ListSchema)
    }

    @Test
    fun `a list renders as compact JSON, so it can be read straight back`() = runBlocking {
        // What makes a list survive a variable and a notification unchanged.
        assertEquals("""["a","b"]""", textList("a", "b").asText())
    }

    @Test
    fun `join and split are inverses`() = runBlocking {
        val joined = ListJoinTransform().on(textList("a", "b", "c"), mapOf(ConfigKey("separator") to ","))
        val split = SplitTextTransform().on(
            list = null,
            config = mapOf(ConfigKey("text") to joined?.value.toString(), ConfigKey("separator") to ","),
        )
        assertEquals(listOf("a", "b", "c"), split?.value)
    }
}
