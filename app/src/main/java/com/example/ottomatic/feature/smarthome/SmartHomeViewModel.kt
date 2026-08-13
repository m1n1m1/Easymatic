package com.example.ottomatic.feature.smarthome

import android.content.Context
import com.example.ottomatic.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ottomatic.data.SmartHomeHubRepository
import com.example.ottomatic.data.hue.PairingStep
import com.example.ottomatic.data.hue.SmartHomeSetup
import com.example.ottomatic.domain.model.SmartHomeHub
import com.example.ottomatic.domain.model.SmartHomeKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which stage of adding a hub is on screen. */
enum class PairingStage {
    /** Choosing which bridge, from the browse or by typing an address. */
    CHOOSING,

    /** Counting down while the user walks to the bridge and presses the button. */
    LINKING,

    /** Paired. Naming it. */
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
)

data class SmartHomeUiState(
    val hubs: List<SmartHomeHub> = emptyList(),
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
 * asymmetry is the point: an armed `trigger.mail` holds an open IMAP connection
 * built from the account's settings, so editing them has to tear it down. Nothing
 * here is armed at all — there are no smart-home triggers, and a node resolves its
 * hub on every run.
 */
@Suppress("TooManyFunctions") // One entry point per control on the hub screen; the screen sets the count.
class SmartHomeViewModel(
    private val repository: SmartHomeHubRepository,
    private val setup: SmartHomeSetup,
    /** For the status lines it reports; a ViewModel has no composition to read them from. */
    private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SmartHomeUiState(hubs = repository.list()))
    val uiState: StateFlow<SmartHomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.hubs.collect { hubs -> _uiState.update { it.copy(hubs = hubs) } }
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

    /** Starts the pairing flow for [kind]. One kind today; the sheet is the seam. */
    fun startPairing(kind: SmartHomeKind) {
        _uiState.update { it.copy(choosingKind = false, pairing = PairingState(kind = kind)) }
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
