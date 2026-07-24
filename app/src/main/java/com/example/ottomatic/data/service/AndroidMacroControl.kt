package com.example.ottomatic.data.service

import android.content.Context
import com.example.ottomatic.core.service.MacroControl
import com.example.ottomatic.engine.service.MacroEngineService

/**
 * Android-backed [MacroControl]. Dispatches the engine service's enable/disable
 * intents so the [com.example.ottomatic.data.WorkflowRepository] flag flip and
 * trigger (dis)arming happen on the engine service's supervisor scope.
 */
class AndroidMacroControl(private val context: Context) : MacroControl {

    override fun enable(macroId: String): Boolean = dispatch(MacroEngineService.ACTION_ENABLE, macroId)

    override fun disable(macroId: String): Boolean = dispatch(MacroEngineService.ACTION_DISABLE, macroId)

    private fun dispatch(action: String, macroId: String): Boolean = runCatching {
        MacroEngineService.start(context, action, macroId)
        true
    }.getOrDefault(false)
}
