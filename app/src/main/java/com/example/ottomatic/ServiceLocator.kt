package com.example.ottomatic

import android.content.Context
import com.example.ottomatic.core.permissions.PermissionChecker
import com.example.ottomatic.core.service.DeviceState
import com.example.ottomatic.core.service.MacroControl
import com.example.ottomatic.core.service.SystemServices
import com.example.ottomatic.data.GeofencePlaceRepository
import com.example.ottomatic.data.WorkflowRepository
import com.example.ottomatic.data.permissions.AndroidPermissionChecker
import com.example.ottomatic.data.service.AndroidDeviceState
import com.example.ottomatic.data.service.AndroidMacroControl
import com.example.ottomatic.data.service.AndroidSystemServices
import com.example.ottomatic.data.trigger.AndroidTriggerHost
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.trigger.TriggerHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Minimal manual dependency container (Hilt deferred). Initialised once from
 * [MainActivity] with the application context. Holds the single shared
 * instances of every infrastructure component.
 */
object ServiceLocator {

    /**
     * Process-lifetime scope for work that must outlive the component that
     * started it — chiefly the graph editor's final save, which runs from
     * `onCleared()` after `viewModelScope` has already been cancelled.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var workflowRepository: WorkflowRepository
        private set

    /** The geofence place library, shared by the editor UI and the trigger host. */
    lateinit var geofencePlaceRepository: GeofencePlaceRepository
        private set

    lateinit var systemServices: SystemServices
        private set

    lateinit var deviceState: DeviceState
        private set

    lateinit var macroControl: MacroControl
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
        geofencePlaceRepository = GeofencePlaceRepository(appContext.filesDir)
        systemServices = AndroidSystemServices(appContext)
        deviceState = AndroidDeviceState(appContext)
        macroControl = AndroidMacroControl(appContext)
        executionContext = DefaultExecutionContext(
            systemServices = systemServices,
            deviceState = deviceState,
            macroControl = macroControl,
            logger = { msg -> android.util.Log.i("Ottomatic", msg) },
        )
        triggerHost = AndroidTriggerHost(appContext, geofencePlaceRepository)
        permissionChecker = AndroidPermissionChecker(appContext)
    }
}
