package com.example.ottomatic.feature.smarthome

import android.content.Context
import com.example.ottomatic.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ottomatic.data.SmartHomeHubRepository
import com.example.ottomatic.data.homeassistant.HaAuthResults
import com.example.ottomatic.data.smarthome.PairingStep
import com.example.ottomatic.data.smarthome.SmartHomeSetup
import com.example.ottomatic.domain.model.HaBaseUrl
import com.example.ottomatic.domain.model.SmartHomeHub
import com.example.ottomatic.domain.model.SmartHomeKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Which stage of adding a hub is on screen.
 *
 * The two vendors use different subsets, which is why this is one enum rather than two:
 * both start at [CHOOSING] and end at [NAMING], and only Hue passes through [LINKING] —
 * a Home Assistant instance has no window to count down and mints nothing, so it goes
 * straight from the address and token to being connected. Splitting the enum would
 * duplicate the two stages they share to isolate the one they do not.
 */
enum class PairingStage {
    /** Choosing which hub: a browse and a typed address, plus a token for Home Assistant. */
    CHOOSING,

    /** Hue only. Counting down while the user walks to the bridge and presses the button. */
    LINKING,

    /** Connected. Naming it. */
    NAMING,
}

/**
 * The add-a-hub flow.
 *
 * [hubId] is set the moment pairing succeeds, which is *before* the name is typed:
 * a bridge issues an application key exactly once, so it is stored as soon as it
 * exists rather than held in memory until somebody finishes a form. Walking away
 * from the naming step leaves a working hub called "Hue Bridge", not a key that was
 * minted and thrown away.
 */
data class PairingState(
    val kind: SmartHomeKind = SmartHomeKind.HUE,
    val stage: PairingStage = PairingStage.CHOOSING,
    val host: String = "",
    val typedHost: String = "",
    val bridgeId: String = "",
    val secondsLeft: Int = 0,
    val hubId: String = "",
    val name: String = "",
    val error: String = "",
    /**
     * The secret being typed — a Home Assistant access token, or a broker's password.
     *
     * **One field for two vendors' credentials**, on `SmartHomeHub.secret`'s reasoning:
     * what they have in common is everything this field is for. Both are typed once, both
     * are sealed the moment they are, and neither is ever read back out.
     *
     * Held here **only while it is being typed**: once [connectHomeAssistant] or
     * [connectMqtt] has sealed it, the editor shows an empty box, on
     * `AiConnectionsViewModel`'s rule — a secret on screen is a secret in a screenshot, a
     * recents thumbnail and an accessibility tree.
     */
    val token: String = "",
    /**
     * The broker user to log in as. MQTT only, and blank for the anonymous brokers that
     * most home networks run.
     *
     * Beside [token] rather than folded into it because it is not a secret: it is the half
     * the user has to be able to read back when a connection is refused.
     */
    val username: String = "",
    /** A round trip is in flight — the buttons are disabled and one spins. */
    val working: Boolean = false,
    /** What Test said when it worked. Blank otherwise. */
    val tested: String = "",
)

/**
 * What Home Assistant said applies to one entity, and whether the asking has come back.
 *
 * [ids] empty with [answered] true is a real answer — this entity genuinely has no triggers —
 * where [answered] false means the question is still out. The picker needs the two apart: it
 * shows the wide list while waiting and the honest empty state afterwards, and collapsing them
 * would make every picker flash "nothing matches" for as long as the round trip takes.
 */
data class HaNarrowing(val ids: Set<String> = emptySet(), val answered: Boolean = false)

data class SmartHomeUiState(
    val hubs: List<SmartHomeHub> = emptyList(),
    /**
     * Which triggers and services each entity accepts, keyed `<hubId>|<entityId>`.
     *
     * Held in the ViewModel rather than in the snapshot on disk because it is **per entity**
     * and asked over the socket: caching every entity's trigger list at Refresh would be a few
     * hundred round trips for a list the user will look at one of. It survives the picker
     * closing and reopening, which is what makes going back to change an entity feel instant.
     */
    val haTriggers: Map<String, HaNarrowing> = emptyMap(),
    val haServices: Map<String, HaNarrowing> = emptyMap(),
    /** Which hub's detail sheet is open, or blank. */
    val detailId: String = "",
    val detailName: String = "",
    /** What the bridge is presenting now, when it disagrees with the pin. Blank otherwise. */
    val presentedCertificate: String = "",
    val busy: Boolean = false,
    val message: String = "",
    val choosingKind: Boolean = false,
    val pairing: PairingState? = null,
)

/**
 * Drives the smart-home hub library: the list, one hub's detail, and pairing.
 *
 * Shared by the standalone Smart home screen and by the two pickers a light node's
 * fields open — see [LocalSmartHome] for how a config form several composables deep
 * gets hold of it.
 *
 * **It does not re-arm the engine**, which `MailAccountsViewModel` does, and the
 * reasoning has changed even though the conclusion has not. It used to be that nothing
 * here was armed at all; since `trigger.ha_state` and `trigger.ha_event` arrived, that
 * is no longer true. What keeps re-arming unnecessary is where the connection lives:
 * an armed `trigger.mail` **holds** an IMAP connection built from the account's
 * settings, so editing them has to tear it down, whereas an armed Home Assistant
 * trigger registers a node id with `HaConnections` and holds nothing. The socket is
 * owned by the connection manager, which watches this same repository and reconnects on
 * its own when a hub's address or token changes — so an edit reaches the socket without
 * anything here knowing that it did. An action node still resolves its hub on every run.
 */
@Suppress("TooManyFunctions") // One entry point per control on the hub screen; the screen sets the count.
class SmartHomeViewModel(
    private val repository: SmartHomeHubRepository,
    private val setup: SmartHomeSetup,
    /** For the status lines it reports; a ViewModel has no composition to read them from. */
    private val appContext: Context,
) : ViewModel() {

    /**
     * The nonce of the sign-in currently in flight, or blank.
     *
     * Held here rather than in [SmartHomeUiState] because it is not something the screen
     * draws, and putting a security value in a state object that gets copied, logged and
     * inspected is how it ends up somewhere it should not be.
     */
    private var pendingState: String = ""

    private val _uiState = MutableStateFlow(SmartHomeUiState(hubs = repository.list()))
    val uiState: StateFlow<SmartHomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.hubs.collect { hubs -> _uiState.update { it.copy(hubs = hubs) } }
        }
        // Collected for the ViewModel's whole life rather than while a screen is up:
        // the browser resumes the app and the redirect activity fires immediately, which
        // can be before any composable is listening again after the process was
        // backgrounded.
        viewModelScope.launch {
            HaAuthResults.codes.collect { completeSignIn(it.code, it.state) }
        }
    }

    /** What a picker shows for a stored id. */
    fun hubById(id: String): SmartHomeHub? = repository.get(id)

    /** Whether [id] has lost its key — the list draws a chip for it. */
    fun needsPairing(id: String): Boolean = repository.needsPairing(id)

    /**
     * Re-reads every hub's resources in the background.
     *
     * Called when the library screen composes and when a picker opens, and its
     * failures are deliberately silent: a stale list of lights is useful and an
     * empty one is not, so a bridge that is off right now leaves yesterday's names
     * on screen rather than a blank sheet.
     */
    fun refreshAll() {
        viewModelScope.launch {
            repository.list().forEach { hub -> setup.refresh(hub.id) }
        }
    }

    /**
     * Asks Home Assistant which triggers or services apply to [entityId], once.
     *
     * Fired when a picker opens with an entity in scope, and **idempotent by key** — a
     * recomposition, a search keystroke or a reopened picker must not send the request again.
     * Nothing is reported when it fails: the picker falls back to the unscoped list, which is
     * the same thing it shows while the answer is still in flight.
     */
    fun narrowHomeAssistant(mode: HaPickerMode, hubId: String, entityId: String) {
        val key = "$hubId|$entityId"
        val asked = when (mode) {
            HaPickerMode.TRIGGER -> _uiState.value.haTriggers
            HaPickerMode.SERVICE -> _uiState.value.haServices
            // Neither list is per entity: an entity chooser is what *decides* the scope, and a
            // hub has no entity above it to be narrowed by.
            HaPickerMode.ENTITY, HaPickerMode.HUB -> return
        }
        if (hubId.isBlank() || entityId.isBlank() || asked.containsKey(key)) return
        // Recorded as in flight before suspending, so two composers cannot both send it.
        put(mode, key, HaNarrowing())
        viewModelScope.launch {
            val ids = when (mode) {
                HaPickerMode.TRIGGER -> setup.haTriggersFor(hubId, entityId)
                else -> setup.haServicesFor(hubId, entityId)
            }
            put(mode, key, HaNarrowing(ids.toSet(), answered = true))
        }
    }

    private fun put(mode: HaPickerMode, key: String, value: HaNarrowing) {
        _uiState.update {
            if (mode == HaPickerMode.TRIGGER) {
                it.copy(haTriggers = it.haTriggers + (key to value))
            } else {
                it.copy(haServices = it.haServices + (key to value))
            }
        }
    }

    /** Re-reads one hub because the user asked, and reports what happened. */
    fun refresh(hubId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = "") }
            val problem = setup.refresh(hubId)
            _uiState.update { it.copy(busy = false, message = problem ?:
                appContext.getString(R.string.smarthome_up_to_date)) }
        }
    }

    fun openDetail(hubId: String) {
        val hub = repository.get(hubId) ?: return
        _uiState.update {
            it.copy(detailId = hub.id, detailName = hub.name, message = "", presentedCertificate = "")
        }
        viewModelScope.launch {
            val presented = setup.currentCertificate(hubId)
            // Only worth showing when it disagrees: two identical fingerprints side
            // by side is noise, and the row exists to explain a mismatch.
            if (presented != null && !presented.equals(hub.certSha256, ignoreCase = true)) {
                _uiState.update { if (it.detailId == hubId) it.copy(presentedCertificate = presented) else it }
            }
        }
    }

    fun closeDetail() {
        _uiState.update { it.copy(detailId = "", presentedCertificate = "", message = "") }
    }

    fun detailNameChanged(name: String) {
        _uiState.update { it.copy(detailName = name) }
    }

    fun saveDetail() {
        val state = _uiState.value
        val hub = repository.get(state.detailId) ?: return
        viewModelScope.launch {
            repository.upsert(hub.copy(name = state.detailName.trim().ifBlank { hub.name }))
            closeDetail()
        }
    }

    fun trustPresentedCertificate() {
        val hubId = _uiState.value.detailId.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = "") }
            val problem = setup.trustCurrentCertificate(hubId)
            if (problem == null) setup.refresh(hubId)
            _uiState.update {
                it.copy(
                    busy = false,
                    presentedCertificate = if (problem == null) "" else it.presentedCertificate,
                    message = problem ?: appContext.getString(R.string.smarthome_trusted_works_again),
                )
            }
        }
    }

    fun delete(hubId: String) {
        viewModelScope.launch {
            repository.delete(hubId)
            closeDetail()
        }
    }

    /** Opens "what kind of hub?", which is the seam a second integration is added at. */
    fun openKindChooser() {
        _uiState.update { it.copy(choosingKind = true) }
    }

    fun closeKindChooser() {
        _uiState.update { it.copy(choosingKind = false) }
    }

    /** Starts the setup flow for [kind]. `AddHubSheet` is the seam that chooses. */
    fun startPairing(kind: SmartHomeKind) {
        _uiState.update { it.copy(choosingKind = false, pairing = PairingState(kind = kind)) }
    }

    fun tokenChanged(token: String) {
        editPairing { it.copy(token = token, error = "", tested = "") }
    }

    /**
     * Checks the address and token without storing anything.
     *
     * **Test earns its place**, on `AiConnectionsScreen`'s reasoning: without it the
     * first proof a credential works is a macro failing quietly at three in the
     * morning, which is exactly the failure this integration exists not to have.
     */
    fun testHomeAssistant() {
        val pairing = _uiState.value.pairing ?: return
        if (pairing.working) return
        editPairing { it.copy(working = true, error = "", tested = "") }
        viewModelScope.launch {
            val problem = setup.testHomeAssistant(pairing.typedHost, pairing.token)
            editPairing {
                it.copy(
                    working = false,
                    error = problem.orEmpty(),
                    tested = if (problem == null) appContext.getString(R.string.smarthome_ha_reached) else "",
                )
            }
        }
    }

    /**
     * Connects the instance, seals its token and reads its first snapshot.
     *
     * Hue's counterpart is [linkTo], and the difference in shape is the whole reason
     * `PairingStage.LINKING` is skipped here: there is no window to count down and
     * nothing is minted, so this either lands on [PairingStage.NAMING] or says why.
     */
    fun connectHomeAssistant() {
        val pairing = _uiState.value.pairing ?: return
        if (pairing.working) return
        editPairing { it.copy(working = true, error = "", tested = "") }
        viewModelScope.launch {
            val step = setup.connectHomeAssistant(pairing.typedHost, pairing.token)
            // The token is dropped from the state the moment it is sealed, so nothing
            // holds the plaintext once the repository has it.
            editPairing { it.copy(working = false, token = "") }
            settle(step)
        }
    }

    /** Whether the sign-in button should be drawn at all. See `HaOAuth.CLIENT_ID`. */
    val canSignIn: Boolean get() = setup.canSignInToHomeAssistant

    /**
     * Starts a sign-in and answers where to send the browser, or null when the address
     * will not do.
     *
     * The nonce is remembered here rather than passed through the browser and back
     * unchecked, which is the whole point of it: the redirect arrives on a custom URI
     * scheme any app may fire an intent at, so the only thing distinguishing this
     * flow's answer from somebody else's is a value only this side ever knew.
     */
    @Suppress("ReturnCount") // No flow open, then a bad address, then the URL.
    fun beginSignIn(): String? {
        val pairing = _uiState.value.pairing ?: return null
        val request = setup.homeAssistantSignIn(pairing.typedHost)
        if (request == null) {
            editPairing { it.copy(error = HaBaseUrl.REQUIREMENT) }
            return null
        }
        // Anything left over from an abandoned attempt would otherwise be replayed into
        // this one and rejected as a nonce mismatch.
        HaAuthResults.clear()
        pendingState = request.state
        editPairing { it.copy(working = true, error = "", tested = "") }
        return request.url
    }

    /**
     * Finishes a sign-in with what the browser came back with.
     *
     * A **mismatched nonce is discarded in silence**, not reported: it means this is not
     * the answer to the flow the user started, so there is nothing to tell them and
     * saying anything would be a message about somebody else's redirect.
     */
    fun completeSignIn(code: String, state: String) {
        val pairing = _uiState.value.pairing ?: return
        if (state != pendingState || pendingState.isBlank()) return
        pendingState = ""
        HaAuthResults.clear()
        viewModelScope.launch {
            val step = setup.completeHomeAssistantSignIn(pairing.typedHost, code)
            editPairing { it.copy(working = false) }
            settle(step)
        }
    }

    fun usernameChanged(username: String) {
        editPairing { it.copy(username = username, error = "", tested = "") }
    }

    /** [testHomeAssistant]'s counterpart, and it matters more here — see `MqttProbe`. */
    fun testMqtt() {
        val pairing = _uiState.value.pairing ?: return
        if (pairing.working) return
        editPairing { it.copy(working = true, error = "", tested = "") }
        viewModelScope.launch {
            val problem = setup.testMqtt(pairing.typedHost, pairing.username, pairing.token)
            editPairing {
                it.copy(
                    working = false,
                    error = problem.orEmpty(),
                    tested = if (problem == null) appContext.getString(R.string.mqtt_broker_reached) else "",
                )
            }
        }
    }

    /** Stores the broker, seals its password and listens briefly for its topics. */
    fun connectMqtt() {
        val pairing = _uiState.value.pairing ?: return
        if (pairing.working) return
        editPairing { it.copy(working = true, error = "", tested = "") }
        viewModelScope.launch {
            val step = setup.connectMqtt(pairing.typedHost, pairing.username, pairing.token)
            // Dropped from the state the moment it is sealed, so nothing holds the
            // plaintext once the repository has it.
            editPairing { it.copy(working = false, token = "") }
            settle(step)
        }
    }

    fun cancelPairing() {
        _uiState.update { it.copy(pairing = null) }
    }

    fun typedHostChanged(host: String) {
        editPairing { it.copy(typedHost = host, error = "") }
    }

    /**
     * Begins the link-button countdown against [host].
     *
     * The poll lives here rather than in the transport so that the countdown, the
     * cancel and the copy are all in one place — the transport does one request and
     * reports one outcome.
     */
    fun linkTo(host: String, bridgeId: String) {
        val target = host.trim()
        if (target.isEmpty()) return
        _uiState.update {
            it.copy(
                pairing = (it.pairing ?: PairingState()).copy(
                    stage = PairingStage.LINKING,
                    host = target,
                    bridgeId = bridgeId,
                    secondsLeft = PAIRING_WINDOW_SECONDS,
                    error = "",
                ),
            )
        }
        viewModelScope.launch { poll(target, bridgeId) }
    }

    fun pairedNameChanged(name: String) {
        editPairing { it.copy(name = name) }
    }

    /** Renames the hub pairing produced and closes the flow. */
    fun finishPairing() {
        val pairing = _uiState.value.pairing ?: return
        val hub = repository.get(pairing.hubId)
        viewModelScope.launch {
            if (hub != null && pairing.name.isNotBlank()) repository.upsert(hub.copy(name = pairing.name.trim()))
            _uiState.update { it.copy(pairing = null) }
        }
    }

    private suspend fun poll(host: String, bridgeId: String) {
        var elapsed = 0
        var outcome: PairingStep? = null
        // Cancelling closes the flow, so the stage check is also how a user who
        // walked away stops this loop rather than it running for the full minute.
        while (outcome == null && elapsed < PAIRING_WINDOW_SECONDS && isLinking()) {
            editPairing { it.copy(secondsLeft = PAIRING_WINDOW_SECONDS - elapsed) }
            val step = setup.pair(host, bridgeId)
            if (step is PairingStep.AwaitingButton) delay(POLL_INTERVAL_MS) else outcome = step
            elapsed++
        }
        if (isLinking()) settle(outcome)
    }

    private fun isLinking(): Boolean = _uiState.value.pairing?.stage == PairingStage.LINKING

    private fun settle(outcome: PairingStep?) {
        when (outcome) {
            is PairingStep.Paired -> editPairing {
                it.copy(
                    stage = PairingStage.NAMING,
                    hubId = outcome.hubId,
                    name = repository.get(outcome.hubId)?.name.orEmpty(),
                    error = "",
                )
            }
            is PairingStep.Failed -> editPairing {
                it.copy(stage = PairingStage.CHOOSING, error = outcome.error)
            }
            // Null or still waiting: the window elapsed with nobody pressing anything.
            else -> editPairing { it.copy(stage = PairingStage.CHOOSING, error =
                appContext.getString(R.string.smarthome_button_not_pressed)) }
        }
    }

    private fun editPairing(transform: (PairingState) -> PairingState) {
        _uiState.update { state -> state.copy(pairing = state.pairing?.let(transform)) }
    }

    companion object {

        /** How long the bridge leaves its link window open, near enough. */
        const val PAIRING_WINDOW_SECONDS = 60

        private const val POLL_INTERVAL_MS = 1_000L
        

        fun factory(
            repository: SmartHomeHubRepository,
            setup: SmartHomeSetup,
            appContext: Context,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SmartHomeViewModel(repository, setup, appContext) }
        }
    }
}
