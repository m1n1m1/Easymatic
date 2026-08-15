package com.example.ottomatic.feature.i18n

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.registry.PaletteGroup

/**
 * The palette's section headings.
 *
 * A **closed** set, so it takes the direct route rather than [NodeText]'s generated
 * keys: an exhaustive `when` is compile-checked in both directions — a new category
 * fails to compile until somebody has decided what it is called, and a missing
 * `R.string` fails the build rather than falling silently back to English. That is
 * the same shape as `AiProviderCopy` and `PermissionCopy`, and it is available here
 * only because the answer set is fixed. Node names, ports and config labels are
 * open-ended and annotation-bound, so they cannot have it.
 *
 * The enum's own `displayName` stays where it is: `:node-api` has no `android.jar`
 * and cannot reach `getString`, and `NodeSuggestions` still searches the English.
 */
@StringRes
@Suppress("CyclomaticComplexMethod") // A flat copy table, not branching logic.
internal fun NodeCategory.labelRes(): Int = when (this) {
    NodeCategory.MANUAL -> R.string.category_manual
    NodeCategory.TIME_SCHEDULE -> R.string.category_time_schedule
    NodeCategory.MESSAGING -> R.string.category_messaging
    NodeCategory.POWER_BATTERY -> R.string.category_power_battery
    NodeCategory.LOCATION -> R.string.category_location
    NodeCategory.AUTOMATION -> R.string.category_automation
    NodeCategory.VARIABLES -> R.string.category_variables
    NodeCategory.CONNECTIVITY -> R.string.category_connectivity
    NodeCategory.PHONE_MEDIA -> R.string.category_phone_media
    NodeCategory.DEVICE_STATE -> R.string.category_device_state
    NodeCategory.SENSORS -> R.string.category_sensors
    NodeCategory.SMART_HOME_EVENTS -> R.string.category_smart_home
    NodeCategory.FLOW_CONTROL -> R.string.category_flow_control
    NodeCategory.INTERACTION -> R.string.category_interaction
    NodeCategory.NETWORK -> R.string.category_network
    NodeCategory.NOTIFICATIONS -> R.string.category_notifications
    NodeCategory.TIMING -> R.string.category_timing
    NodeCategory.DEVICE_SETTINGS -> R.string.category_device_settings
    NodeCategory.SMART_HOME -> R.string.category_smart_home
    NodeCategory.AI -> R.string.category_ai
    NodeCategory.FILES -> R.string.category_files
    NodeCategory.DATA -> R.string.category_data
    NodeCategory.VALUE_POWER -> R.string.category_value_power
    NodeCategory.VALUE_CONNECTIVITY -> R.string.category_value_connectivity
    NodeCategory.VALUE_DEVICE -> R.string.category_value_device
    NodeCategory.VALUE_SENSORS -> R.string.category_value_sensors
    NodeCategory.VALUE_TIME -> R.string.category_value_time
    NodeCategory.VALUE_SMART_HOME -> R.string.category_smart_home
    NodeCategory.VALUE_VARIABLES -> R.string.category_value_variables
    NodeCategory.TRANSFORM_DATA -> R.string.category_transform_data
    // Never drawn — a plugin node needs a non-null category, but the palette heads its
    // group with the plugin's own name instead. One string for all four, because the
    // `when` must be total and four identical rows would only invite editing one of them.
    NodeCategory.PLUGIN_TRIGGER,
    NodeCategory.PLUGIN_ACTION,
    NodeCategory.PLUGIN_VALUE,
    NodeCategory.PLUGIN_TRANSFORM,
    -> R.string.category_plugin
}

/** The heading for a palette group, whichever kind it is. */
@Composable
internal fun PaletteGroup.label(): String = when (this) {
    is PaletteGroup.Builtin -> stringResource(category.labelRes())
    // A plugin's own app name. There is nothing to look up, and nothing that should be.
    is PaletteGroup.Plugin -> pluginName
}
