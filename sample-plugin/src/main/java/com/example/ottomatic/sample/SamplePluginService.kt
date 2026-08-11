package com.example.ottomatic.sample

import com.example.ottomatic.plugin.BaseOttomaticPluginService

/**
 * The whole of the sample plugin's plumbing.
 *
 * One list. The service indexes it, publishes the manifest under this app's own
 * package name, strips the prefix off incoming calls and marshals every argument and
 * result — so there is no AIDL here, no JSON, no threading and no dispatch table.
 *
 * Phase 5 removes even this list: a `@OttomaticNode` annotation on each node class
 * will generate it, along with the `when` over typeId that is the only part of the
 * marshalling which cannot be written generically.
 */
class SamplePluginService : BaseOttomaticPluginService() {

    override val nodes = listOf(
        ShoutAction(),
        DeviceNameValue(),
        InitialsTransform(),
        TemperatureTrigger(),
    )
}
