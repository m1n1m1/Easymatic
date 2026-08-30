package io.github.m1n1m1.easymatic.feature.geofence

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.m1n1m1.easymatic.data.GeofencePlaceRepository
import io.github.m1n1m1.easymatic.data.location.LocationLookup
import io.github.m1n1m1.easymatic.data.location.PlaceSuggestion
import io.github.m1n1m1.easymatic.domain.model.GeofencePlace
import io.github.m1n1m1.easymatic.engine.service.MacroEngineService
import io.github.m1n1m1.easymatic.feature.geofence.map.MapPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The place being edited on the map. Separate from [GeofencePlace] because a
 * draft is allowed to be incomplete — a new place has no id and starts with a
 * blank name — and because the editor must be able to discard changes, which it
 * cannot do if it mutates the stored place in place.
 */
data class GeofenceDraft(
    /** Null for a place that has not been saved yet. */
    val id: String? = null,
    val name: String = "",
    val center: MapPoint = DEFAULT_CENTER,
    val radiusMeters: Float = GeofencePlace.RELIABLE_MIN_RADIUS_METERS,
    val address: String = "",
    /**
     * A one-shot request for the map to jump somewhere. Cleared once consumed,
     * so re-centring twice on the same spot works.
     */
    val recenterTo: MapPoint? = null,
) {
    val isNew: Boolean get() = id == null

    /** A draft is saveable once it has a name; the map always has a centre. */
    val canSave: Boolean get() = name.isNotBlank()
}

data class GeofencePlacesUiState(
    val places: List<GeofencePlace> = emptyList(),
    val draft: GeofenceDraft? = null,
    val searchQuery: String = "",
    val suggestions: List<PlaceSuggestion> = emptyList(),
    val locating: Boolean = false,
)

/**
 * Drives the geofence place library: the list, and the map editor for one
 * place.
 *
 * It is shared by the two places the library is reached from — the standalone
 * "Geofences" screen and the picker opened from a geofence trigger's config —
 * so both see the same edits without a reload. See [LocalGeofencePlaces] for
 * how the config form, which is several composables deep and has no ViewModel
 * of its own, gets hold of it.
 */
@Suppress("TooManyFunctions") // One entry point per control on the editor; splitting would only hide them.
class GeofencePlacesViewModel(
    private val repository: GeofencePlaceRepository,
    private val locationLookup: LocationLookup,
    private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GeofencePlacesUiState(places = repository.list()))
    val uiState: StateFlow<GeofencePlacesUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            repository.places.collect { places -> _uiState.update { it.copy(places = places) } }
        }
    }

    fun placeById(id: String): GeofencePlace? = repository.get(id)

    // ---- Editing -----------------------------------------------------------

    /**
     * Opens the editor on a new place, starting at the device's location when
     * it can be had. Until it arrives the map sits at [DEFAULT_CENTER]; the
     * user can pan away in the meantime, so the fix only *recentres* rather
     * than overwriting a centre they may have already chosen.
     */
    fun createPlace() {
        _uiState.update { it.copy(draft = GeofenceDraft(), suggestions = emptyList(), searchQuery = "") }
        useCurrentLocation()
    }

    fun editPlace(id: String) {
        val place = repository.get(id) ?: return
        _uiState.update {
            it.copy(
                draft = GeofenceDraft(
                    id = place.id,
                    name = place.name,
                    center = MapPoint(place.latitude, place.longitude),
                    radiusMeters = place.radiusMeters,
                    address = place.address,
                    recenterTo = MapPoint(place.latitude, place.longitude),
                ),
                suggestions = emptyList(),
                searchQuery = "",
            )
        }
    }

    fun closeEditor() {
        searchJob?.cancel()
        _uiState.update { it.copy(draft = null, suggestions = emptyList(), searchQuery = "", locating = false) }
    }

    fun updateName(name: String) = updateDraft { it.copy(name = name) }

    fun updateRadius(radiusMeters: Float) = updateDraft { it.copy(radiusMeters = radiusMeters) }

    /**
     * The map reporting where it now sits. This clears [GeofenceDraft.address]:
     * the old label describes the old spot, and showing a stale street name
     * under a moved pin is worse than showing none. A fresh one is resolved on
     * save.
     */
    fun updateCenter(center: MapPoint) = updateDraft {
        if (it.center == center) it else it.copy(center = center, address = "", recenterTo = null)
    }

    fun useCurrentLocation() {
        _uiState.update { it.copy(locating = true) }
        viewModelScope.launch {
            val fix = locationLookup.currentLocation()
            _uiState.update { state ->
                val draft = state.draft
                if (fix == null || draft == null) {
                    state.copy(locating = false)
                } else {
                    val point = MapPoint(fix.latitude, fix.longitude)
                    state.copy(
                        locating = false,
                        draft = draft.copy(center = point, recenterTo = point, address = fix.label),
                    )
                }
            }
        }
    }

    // ---- Address search ----------------------------------------------------

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(suggestions = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            // Geocoding is a network round trip; wait for a pause in typing
            // rather than firing one per keystroke.
            delay(SEARCH_DEBOUNCE_MS)
            val results = locationLookup.search(query)
            _uiState.update { it.copy(suggestions = results) }
        }
    }

    fun applySuggestion(suggestion: PlaceSuggestion) {
        val point = MapPoint(suggestion.latitude, suggestion.longitude)
        _uiState.update { state ->
            state.copy(
                draft = state.draft?.copy(
                    center = point,
                    recenterTo = point,
                    address = suggestion.label,
                    // An unnamed new place takes the address as its name; it is
                    // almost always what the user would have typed anyway.
                    name = state.draft.name.ifBlank { suggestion.label.substringBefore(",") },
                ),
                suggestions = emptyList(),
                searchQuery = "",
            )
        }
    }

    // ---- Persistence -------------------------------------------------------

    /**
     * Saves the open draft and re-arms the engine. Returns the id, so the
     * picker can select a place the user has just created.
     */
    fun save(onSaved: (String) -> Unit = {}) {
        val draft = _uiState.value.draft?.takeIf { it.canSave } ?: return
        viewModelScope.launch {
            val address = draft.address.ifBlank {
                locationLookup.describe(draft.center.latitude, draft.center.longitude)
            }
            val saved = if (draft.isNew) {
                repository.create(
                    name = draft.name.trim(),
                    latitude = draft.center.latitude,
                    longitude = draft.center.longitude,
                    radiusMeters = draft.radiusMeters,
                    address = address,
                )
            } else {
                repository.upsert(
                    GeofencePlace(
                        id = requireNotNull(draft.id),
                        name = draft.name.trim(),
                        latitude = draft.center.latitude,
                        longitude = draft.center.longitude,
                        radiusMeters = draft.radiusMeters,
                        address = address,
                    ),
                )
            }
            rearmEngine()
            closeEditor()
            onSaved(saved.id)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repository.delete(id)
            rearmEngine()
            if (_uiState.value.draft?.id == id) closeEditor()
        }
    }

    /**
     * Triggers move fences only when they arm, so an armed macro keeps watching
     * the old spot until the runner restarts. `REARM_ALL` cancels and restarts
     * every enabled workflow, which tears the stale platform geofence down and
     * re-adds it at the new centre.
     */
    private fun rearmEngine() {
        runCatching { MacroEngineService.start(appContext, MacroEngineService.ACTION_REARM_CHANGED) }
    }

    private fun updateDraft(transform: (GeofenceDraft) -> GeofenceDraft) {
        _uiState.update { state -> state.draft?.let { state.copy(draft = transform(it)) } ?: state }
    }

    companion object {
        fun factory(
            repository: GeofencePlaceRepository,
            locationLookup: LocationLookup,
            appContext: Context,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { GeofencePlacesViewModel(repository, locationLookup, appContext) }
        }
    }
}

private const val SEARCH_DEBOUNCE_MS = 350L

/**
 * Where a brand-new place starts before a location fix arrives: (0, 0) would
 * drop the user in the Atlantic with no clue what to do, so this is a
 * recognisable landmark to pan away from.
 */
private val DEFAULT_CENTER = MapPoint(latitude = 48.2082, longitude = 16.3738)
