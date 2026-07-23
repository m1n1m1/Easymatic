package com.example.ottomatic

import android.app.Application
import com.example.ottomatic.engine.service.MacroEngineService
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
class OttomaticApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        appScope.launch {
            if (ServiceLocator.workflowRepository.list().any { it.enabled }) {
                MacroEngineService.start(this@OttomaticApplication, MacroEngineService.ACTION_REARM_ALL)
            }
        }
    }
}
