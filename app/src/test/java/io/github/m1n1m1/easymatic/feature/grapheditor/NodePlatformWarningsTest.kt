package io.github.m1n1m1.easymatic.feature.grapheditor

import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.domain.model.PlatformWarning
import io.github.m1n1m1.easymatic.domain.registry.NodeTypeRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodePlatformWarningsTest {
    @Test
    fun `warnings reach the metadata used by picker and config screen`() {
        val expected = mapOf(
            "action.wifi" to PlatformWarning.WIFI_TOGGLE,
            "action.bluetooth" to PlatformWarning.BLUETOOTH_TOGGLE,
            "action.dnd" to PlatformWarning.DND_GLOBAL_CONTROL,
            "action.screenshot" to PlatformWarning.SCREENSHOT_UNAVAILABLE,
            "action.image_delete" to PlatformWarning.PICTURE_BIN_UNAVAILABLE,
        )
        for ((typeId, warning) in expected) {
            val definition = requireNotNull(NodeTypeRegistry.byId(NodeTypeId(typeId)))
            assertEquals(typeId, listOf(warning), definition.platformWarnings)
        }
    }

    @Test
    fun `settings needing grants are not labelled as unsupported Android versions`() {
        for (typeId in listOf("action.brightness", "action.screen_timeout", "action.auto_rotate", "action.volume")) {
            val definition = requireNotNull(NodeTypeRegistry.byId(NodeTypeId(typeId)))
            assertTrue(typeId, definition.platformWarnings.isEmpty())
        }
    }

    @Test
    fun `every warning has its own translated message resource`() {
        val resources = PlatformWarning.entries.map { it.messageRes() }
        assertEquals(resources.size, resources.distinct().size)
        assertTrue(resources.all { it != 0 })
    }
}
