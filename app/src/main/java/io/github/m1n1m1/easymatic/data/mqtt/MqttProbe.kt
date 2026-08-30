package io.github.m1n1m1.easymatic.data.mqtt

import io.github.m1n1m1.easymatic.domain.model.MqttAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One connection attempt against a broker that has not been stored yet.
 *
 * The Test button's whole implementation, and it is separate from [MqttConnections]
 * because everything that class does is keyed by *hub id* — and at this point there is no
 * hub. That is the same reason `SmartHomeSetup` exists at all: a credential being typed
 * has not been sealed, so nothing that reads the repository can see it.
 *
 * **Test earns its place** on `AiConnectionsScreen`'s and Home Assistant's reasoning:
 * without it the first proof an address and password work is a macro failing quietly at
 * three in the morning, which is the failure this integration exists not to have. It is
 * worth more here than for either of those, because a broker gives less to go on — there
 * is no web interface to have logged into and no key visibly minted, so an address typed
 * one digit wrong is otherwise indistinguishable from a working setup until something
 * silently fails to publish.
 *
 * It connects and disconnects, subscribing to nothing: what is being checked is that the
 * broker is reachable and accepts this login, and a broker that refuses a *subscription*
 * for want of an ACL is still one this app can publish through.
 */
internal object MqttProbe {

    /** Null when the broker answered, or the sentence saying why it did not. */
    suspend fun test(host: String, username: String, password: String): String? {
        val address = MqttAddress.parse(host) ?: return MqttAddress.REQUIREMENT
        return withContext(Dispatchers.IO) {
            val session = MqttSession(
                serverUri = address.serverUri,
                // A distinct client id, so testing never disconnects the live connection to
                // the same broker: a broker takes over a session when a second client
                // connects under an id it already holds, which would drop every armed
                // trigger for as long as the test took.
                clientId = PROBE_CLIENT_ID,
                username = username,
                password = password,
                listener = object : MqttSession.Events {
                    override fun onReady() = Unit
                    override fun onLost(reason: String) = Unit
                    override fun onArrival(arrival: MqttArrival) = Unit
                },
            )
            try {
                session.connect()
            } finally {
                session.close()
            }
        }
    }

    private const val PROBE_CLIENT_ID = "easymatic-test"
}
