package io.github.m1n1m1.easymatic.data.apps

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [InstalledApps] against a real `PackageManager` — the only place it can be tested,
 * since the JVM has no packages to enumerate.
 */
@RunWith(AndroidJUnit4::class)
class InstalledAppsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearCache() {
        InstalledApps.invalidate()
    }

    @Test
    fun listsInstalledAppsWithLabels() = runBlocking {
        val apps = InstalledApps.all(context)

        assertTrue("a device always has apps installed", apps.isNotEmpty())
        assertTrue("every app is named", apps.all { it.label.isNotBlank() })
        assertTrue("every app has a package", apps.all { it.packageName.isNotBlank() })
    }

    /**
     * The launchable set is what `action.launch_app` offers, and it must be a subset
     * of everything installed — the two filter fields deliberately offer the rest.
     */
    @Test
    fun launchableAppsAreASubsetOfEverythingInstalled() = runBlocking {
        val apps = InstalledApps.all(context)
        val launchable = apps.filter { it.launchable }

        assertTrue("this app itself has a launcher activity", launchable.isNotEmpty())
        assertTrue(launchable.size <= apps.size)
        assertTrue(
            "Easymatic must be offered as launchable",
            launchable.any { it.packageName == context.packageName },
        )
    }

    @Test
    fun appsAreSortedByLabel() = runBlocking {
        val labels = InstalledApps.all(context).map { it.label.lowercase() }
        assertEquals(labels.sorted(), labels)
    }

    /** Enumerating packages is slow enough that a second read must not repeat it. */
    @Test
    fun aSecondReadIsCached() = runBlocking {
        val first = InstalledApps.all(context)
        assertSame(first, InstalledApps.all(context))
    }

    @Test
    fun invalidateForcesAReload() = runBlocking {
        val first = InstalledApps.all(context)
        InstalledApps.invalidate()
        val second = InstalledApps.all(context)

        assertNotSame(first, second)
        assertEquals(first, second)
    }
}
