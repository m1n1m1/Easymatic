package io.github.m1n1m1.easymatic.data.mqtt

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/** One message off the wire, before anything has decided who wanted it. */
internal data class MqttArrival(
    val topic: String,
    val payload: String,
    val retained: Boolean,
    val qos: Int,
)

/**
 * One broker connection, with the client library's threading and error model turned into
 * this app's.
 *
 * The counterpart of `HaSocket`, and it exists for the same reason: everything above it
 * should see a connection that is either up or explained, never a library's exception
 * hierarchy. Three things about the wrapping are worth knowing, because each of them is a
 * failure that would otherwise be silent.
 *
 * **Subscriptions are re-sent on every reconnect, by us.** The client library reconnects
 * on its own and does *not* restore subscriptions when the session is a clean one, so a
 * connection that drops and comes back looks perfectly healthy in every log and delivers
 * nothing ever again. [onReady] is called on the first connect and on every reconnect
 * alike, and [MqttConnections] re-subscribes from it — which is why it does not
 * distinguish the two cases: a code path that runs only after a failure is a code path
 * that is broken.
 *
 * **The session is clean, and the client id is stable.** Those pull in opposite
 * directions and both are deliberate. A stable id means a broker's own connection list
 * names this phone once rather than accumulating a row per app start; a clean session
 * means the broker queues *nothing* while the phone is away. Queuing sounds like the
 * generous option and is not: a trigger fires on what happens while it is armed, and a
 * night's worth of held messages arriving at breakfast would run a macro dozens of times
 * for events long past.
 *
 * **A password is sent as it was stored and never logged.** Nothing here writes the
 * credential into a message, including into the sentence a failure reports — Paho's own
 * exception text does not contain it either, which is worth having checked.
 */
internal class MqttSession(
    private val serverUri: String,
    private val clientId: String,
    private val username: String,
    private val password: String,
    private val listener: Events,
) {

    /**
     * What a connection tells its owner, on `HaSocket.Events`' shape.
     *
     * One interface rather than three lambdas because they arrive on the library's own
     * threads and always together — and because [onReady] firing on a *reconnect* is the
     * thing a caller must not overlook, which a named member says and a positional lambda
     * does not.
     */
    interface Events {
        /** The first connect and every automatic reconnect alike. Re-subscribe from here. */
        fun onReady()

        /** The connection dropped. [reason] is a sentence naming why. */
        fun onLost(reason: String)

        fun onArrival(arrival: MqttArrival)
    }

    /**
     * Built in [connect] rather than here, because the constructor is itself failable —
     * it validates the address and throws on one it cannot parse. Constructing in a
     * field would make that failure arrive as an exception out of whatever happened to
     * build this object, which is a long way from the text field it is about.
     */
    private var client: MqttAsyncClient? = null

    val isConnected: Boolean get() = runCatching { client?.isConnected == true }.getOrDefault(false)

    /**
     * Opens the connection, or answers why it would not open.
     *
     * Blocking, and meant to be called on an IO dispatcher: the library's own async API
     * would hand back a token this has to wait on regardless, and waiting on it here is
     * what lets every caller above see one suspending function.
     */
    fun connect(): String? {
        val opened = runCatching { MqttAsyncClient(serverUri, clientId, MemoryPersistence()) }
            .getOrElse { return explain(it) }
        client = opened
        opened.setCallback(object : MqttCallbackExtended {

            override fun connectComplete(reconnect: Boolean, serverUri: String) = listener.onReady()

            override fun connectionLost(cause: Throwable?) = listener.onLost(explain(cause))

            override fun messageArrived(topic: String, message: MqttMessage) {
                listener.onArrival(
                    MqttArrival(
                        topic = topic,
                        // Decoded leniently: a topic that turns out to carry binary should
                        // show something odd on the canvas rather than stop the trigger.
                        payload = String(message.payload, Charsets.UTF_8),
                        retained = message.isRetained,
                        qos = message.qos,
                    ),
                )
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })

        return runCatching { opened.connect(options()).waitForCompletion(CONNECT_TIMEOUT_MS) }
            .fold(onSuccess = { null }, onFailure = ::explain)
    }

    fun subscribe(filter: String, qos: Int): String? = withClient {
        it.subscribe(filter, qos).waitForCompletion(ACTION_TIMEOUT_MS)
    }

    fun unsubscribe(filter: String): String? = withClient {
        it.unsubscribe(filter).waitForCompletion(ACTION_TIMEOUT_MS)
    }

    fun publish(topic: String, payload: String, qos: Int, retain: Boolean): String? = withClient {
        it.publish(topic, payload.toByteArray(Charsets.UTF_8), qos, retain)
            .waitForCompletion(ACTION_TIMEOUT_MS)
    }

    private fun withClient(block: (MqttAsyncClient) -> Unit): String? {
        val open = client ?: return NOT_OPEN
        return runCatching { block(open) }.fold(onSuccess = { null }, onFailure = ::explain)
    }

    /**
     * Closes the connection and releases the library's threads.
     *
     * Every step is guarded separately: a client that is already disconnected throws on
     * `disconnect` and would then never reach `close`, which leaks the ping and callback
     * threads for the life of the process.
     */
    fun close() {
        val open = client ?: return
        client = null
        runCatching { open.disconnect().waitForCompletion(ACTION_TIMEOUT_MS) }
        runCatching { open.close(true) }
    }

    private fun options() = MqttConnectOptions().apply {
        isCleanSession = true
        isAutomaticReconnect = true
        connectionTimeout = CONNECT_TIMEOUT_SECONDS
        keepAliveInterval = KEEPALIVE_SECONDS
        // Blank means anonymous, which is what a broker on a home network usually is.
        // Paho refuses a blank username outright rather than treating it as absent, so
        // the fields are only set when there is something to set.
        if (username.isNotBlank()) {
            userName = username
            this.password = this@MqttSession.password.toCharArray()
        }
    }

    /**
     * A sentence about [cause] the person who set the broker up can act on.
     *
     * The reason codes worth naming are the ones with a *different fix*: a refused
     * password is a field in this app, an unreachable address is the address or the
     * network, and a refused client id or protocol version is the broker's own
     * configuration. Everything else keeps the library's own message, which is more use
     * than a generic sentence would be.
     */
    private fun explain(cause: Throwable?): String = when ((cause as? MqttException)?.reasonCode) {
        // The constants are `short` and `getReasonCode` answers `int`, so every one of
        // them has to be widened before it can be compared. Kotlin refuses the mismatch,
        // which is the good outcome — in Java the `when` would simply never match.
        REASON_FAILED_AUTHENTICATION, REASON_NOT_AUTHORIZED ->
            "The broker refused the username or password"
        REASON_BROKER_UNAVAILABLE, REASON_SERVER_CONNECT_ERROR ->
            "Could not reach the broker at $serverUri — check the address and that this phone is on the network"
        REASON_INVALID_CLIENT_ID -> "The broker rejected this app's client id"
        REASON_INVALID_PROTOCOL_VERSION ->
            "The broker does not accept MQTT 3.1.1, which is what this app speaks"
        REASON_CLIENT_TIMEOUT, REASON_WRITE_TIMEOUT -> "The broker did not answer in time"
        else -> cause?.message ?: "The connection to the broker failed"
    }

    companion object {
        /**
         * The client id this phone connects under.
         *
         * Derived from the hub's own id rather than randomised, so a broker's connection
         * list shows one row per configured broker rather than a new one on every app
         * start — and so a broker configured to allow only known client ids can be told
         * what to expect. Truncated because the specification only guarantees 23
         * characters, and older brokers enforce it.
         */
        fun clientIdFor(hubId: String): String =
            (CLIENT_PREFIX + hubId.filter { it.isLetterOrDigit() }).take(MAX_CLIENT_ID)

        private const val CLIENT_PREFIX = "easymatic-"
        private const val MAX_CLIENT_ID = 23
        private const val CONNECT_TIMEOUT_SECONDS = 15
        private const val KEEPALIVE_SECONDS = 60
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val ACTION_TIMEOUT_MS = 10_000L
        private const val NOT_OPEN = "The connection to the broker is not open"

        private val REASON_FAILED_AUTHENTICATION = MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt()
        private val REASON_NOT_AUTHORIZED = MqttException.REASON_CODE_NOT_AUTHORIZED.toInt()
        private val REASON_BROKER_UNAVAILABLE = MqttException.REASON_CODE_BROKER_UNAVAILABLE.toInt()
        private val REASON_SERVER_CONNECT_ERROR = MqttException.REASON_CODE_SERVER_CONNECT_ERROR.toInt()
        private val REASON_INVALID_CLIENT_ID = MqttException.REASON_CODE_INVALID_CLIENT_ID.toInt()
        private val REASON_INVALID_PROTOCOL_VERSION = MqttException.REASON_CODE_INVALID_PROTOCOL_VERSION.toInt()
        private val REASON_CLIENT_TIMEOUT = MqttException.REASON_CODE_CLIENT_TIMEOUT.toInt()
        private val REASON_WRITE_TIMEOUT = MqttException.REASON_CODE_WRITE_TIMEOUT.toInt()
    }
}
