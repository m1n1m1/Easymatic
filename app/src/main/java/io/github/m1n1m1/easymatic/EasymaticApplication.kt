package io.github.m1n1m1.easymatic

import android.app.Application
import io.github.m1n1m1.easymatic.engine.service.MacroEngineService
import io.github.m1n1m1.easymatic.feature.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Initialises the [ServiceLocator] dependency container
 * once, on process start, and re-arms the engine for any persisted-enabled
 * macro so background execution resumes without user interaction.
 *
 * Moving [ServiceLocator.init] here (out of [MainActivity]) guarantees the
 * container is ready before any manifest receiver or the engine service runs,
 * which matters now that background components depend on it.
 */
class EasymaticApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // Here rather than in either of the components that know something
        // changed: the package rule keeps `data` and `engine` from seeing
        // `feature`, so the widgets subscribe to those two instead of being
        // called by them, and this root package is the one place allowed to
        // introduce them to each other.
        WidgetUpdater.attach(this, ServiceLocator.appScope)
        appScope.launch {
            if (ServiceLocator.workflowRepository.list().any { it.enabled }) {
                MacroEngineService.start(this@EasymaticApplication, MacroEngineService.ACTION_REARM_ALL)
            }
        }
    }
}
