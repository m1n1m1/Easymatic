package io.github.m1n1m1.easymatic.sample

import io.github.m1n1m1.easymatic.nodeapi.wire.PluginStatusWire
import io.github.m1n1m1.easymatic.plugin.BaseEasymaticPluginService

/**
 * The whole of the sample plugin's plumbing.
 *
 * One list. The service indexes it, publishes the manifest under this app's own
 * package name, strips the prefix off incoming calls and marshals every argument and
 * result — so there is no AIDL here, no JSON, no threading and no dispatch table.
 *
 * Phase 5 removes even this list: a `@EasymaticNode` annotation on each node class
 * will generate it, along with the `when` over typeId that is the only part of the
 * marshalling which cannot be written generically.
 */
class SamplePluginService : BaseEasymaticPluginService() {

    override val nodes = listOf(
        ShoutAction(),
        PostAction(),
        AttachAction(),
        DeviceNameValue(),
        InitialsTransform(),
        TemperatureTrigger(),
    )

    /**
     * Whether this plugin is ready to work — here, whether anybody has "signed in".
     *
     * The one override most real plugins will want, and the reason it exists: a plugin
     * talking to a third-party service holds every permission it declared and still does
     * nothing at all until somebody has an account. Easymatic's Problems panel cannot see
     * that from outside — the graph is perfect and the node is silent — so the plugin has
     * to say it, and this sentence lands on every placed node of this plugin as a warning
     * that blocks nothing.
     *
     * Kept local, as the contract asks: a preference read, not a network call. It is asked
     * on every refresh under a two-second bound, and a plugin that overruns leaves the
     * host saying nothing rather than guessing.
     */
    override fun status(): PluginStatusWire = when {
        SampleAccount.isSignedIn(applicationContext) -> PluginStatusWire()
        else -> PluginStatusWire(
            ready = false,
            message = "Nobody is signed in. Open Sample Tools' settings to sign in.",
        )
    }
}
