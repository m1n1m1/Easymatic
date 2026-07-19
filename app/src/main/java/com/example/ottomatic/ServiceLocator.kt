package com.example.ottomatic

import android.content.Context
import com.example.ottomatic.core.permissions.PermissionChecker
import com.example.ottomatic.core.service.SystemServices
import com.example.ottomatic.data.WorkflowRepository
import com.example.ottomatic.data.permissions.AndroidPermissionChecker
import com.example.ottomatic.data.service.AndroidSystemServices
import com.example.ottomatic.data.trigger.AndroidTriggerHost
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.trigger.TriggerHost

/**
 * Minimal manual dependency container (Hilt deferred). Initialised once from
 * [MainActivity] with the application context. Holds the single shared
 * instances of every infrastructure component.
 */
object ServiceLocator {

    lateinit var workflowRepository: WorkflowRepository
        private set

    lateinit var systemServices: SystemServices
        private set

    lateinit var executionContext: ExecutionContext
        private set

    lateinit var triggerHost: TriggerHost
        private set

    /**
     * Application-context-backed permission checker. Cannot report
     * `showRationale` (needs an Activity) — fine for status checks but for the
     * actual request flow use an Activity-backed checker in `MainActivity`.
     */
    lateinit var permissionChecker: PermissionChecker
        private set

    fun init(context: Context) {
        val appContext = context.applicationContext
        workflowRepository = WorkflowRepository(appContext.filesDir)
        systemServices = AndroidSystemServices(appContext)
        executionContext = DefaultExecutionContext(
            systemServices = systemServices,
            logger = { msg -> android.util.Log.i("Ottomatic", msg) },
        )
        triggerHost = AndroidTriggerHost(appContext)
        permissionChecker = AndroidPermissionChecker(appContext)
    }
}
