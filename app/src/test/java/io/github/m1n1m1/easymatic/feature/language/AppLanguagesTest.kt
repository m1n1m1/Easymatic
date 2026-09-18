package io.github.m1n1m1.easymatic.feature.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * Pins [AppLanguages.tags] to the resource folders, the same way `BackupContentsTest`
 * pins the include list to the directories the repositories create: a ninth locale
 * fails here until the picker offers it, and a deleted one until the picker stops.
 */
class AppLanguagesTest {

    @Test
    fun `the picker offers exactly the locales the resources hold`() {
        val default = Properties()
            .apply { File(RES, "resources.properties").reader().use(::load) }
            .getProperty("unqualifiedResLocale")
        val translated = RES.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") && it.name != "values-night" }
            .filter { dir -> dir.listFiles().orEmpty().any { it.name.startsWith("strings") && it.extension == "xml" } }
            .map { it.name.removePrefix("values-").replace("-r", "-") }
        assertEquals((translated + default).toSet(), AppLanguages.tags.toSet())
    }

    @Test
    fun `an applied locale list resolves to one of the offered tags`() {
        assertNull(AppLanguages.selectedTag(""))
        assertEquals("de", AppLanguages.selectedTag("de"))
        assertEquals("de", AppLanguages.selectedTag("de-AT"))
        assertEquals("zh-CN", AppLanguages.selectedTag("zh-CN"))
        assertEquals("en-GB", AppLanguages.selectedTag("en-US,de"))
        assertNull(AppLanguages.selectedTag("it"))
    }

    @Test
    fun `every offered language has a name of its own`() {
        for (tag in AppLanguages.tags) {
            assertNotEquals(tag, AppLanguages.nameOf(tag))
        }
    }

    private companion object {
        val RES = File("src/main/res")
    }
}
