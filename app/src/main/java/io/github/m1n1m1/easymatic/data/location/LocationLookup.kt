package io.github.m1n1m1.easymatic.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** A place found by [LocationLookup.search], ready to drop onto the map. */
data class PlaceSuggestion(
    val label: String,
    val latitude: Double,
    val longitude: Double,
)

/**
 * The two location lookups the geofence editor needs — "where am I" and "where
 * is this address" — behind one interface so `feature/` never touches Play
 * Services or [Geocoder] directly.
 *
 * Every method fails soft (null / empty list). A geocoder can be absent on the
 * device, rate-limited, or simply offline; none of that should stop the user
 * placing a fence by dragging the map, which always works.
 */
interface LocationLookup {

    /** The device's current position, or null when unavailable or not permitted. */
    suspend fun currentLocation(): PlaceSuggestion?

    /** Places matching the free-text [query], best match first. Empty on failure. */
    suspend fun search(query: String): List<PlaceSuggestion>

    /** A human-readable label for a coordinate, or "" when none can be resolved. */
    suspend fun describe(latitude: Double, longitude: Double): String
}

/** Play Services + platform [Geocoder] implementation of [LocationLookup]. */
class AndroidLocationLookup(context: Context) : LocationLookup {

    private val appContext = context.applicationContext
    private val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)

    override suspend fun currentLocation(): PlaceSuggestion? {
        val location = requestFix() ?: return null
        return PlaceSuggestion(
            label = describe(location.latitude, location.longitude),
            latitude = location.latitude,
            longitude = location.longitude,
        )
    }

    /**
     * A fresh position fix, or null when location is unavailable or not
     * permitted.
     *
     * `getCurrentLocation` rather than `getLastLocation`: the last fix can be
     * hours old and kilometres away, which would silently drop the user's fence
     * somewhere they have not been since this morning. BALANCED accuracy is
     * plenty to centre a map on and costs far less battery than a GPS lock.
     */
    @SuppressLint("MissingPermission") // Guarded by hasLocationPermission.
    private suspend fun requestFix(): Location? {
        if (!hasLocationPermission()) return null
        return runCatching {
            suspendCancellableCoroutine { continuation ->
                fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resume(null) }
            }
        }.getOrNull()
    }

    override suspend fun search(query: String): List<PlaceSuggestion> {
        if (query.isBlank() || !Geocoder.isPresent()) return emptyList()
        val geocoder = Geocoder(appContext)
        val addresses = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocationName(query, MAX_RESULTS) { continuation.resume(it) }
                }
            } else {
                // Deprecated on 33+ but the only option below it, and it blocks
                // on network I/O — hence the IO dispatcher.
                @Suppress("DEPRECATION")
                withContext(Dispatchers.IO) { geocoder.getFromLocationName(query, MAX_RESULTS) }
            }
        }.getOrNull().orEmpty()
        return addresses.map {
            PlaceSuggestion(label = it.readableLabel(), latitude = it.latitude, longitude = it.longitude)
        }
    }

    override suspend fun describe(latitude: Double, longitude: Double): String {
        if (!Geocoder.isPresent()) return ""
        val geocoder = Geocoder(appContext)
        val addresses = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocation(latitude, longitude, 1) { continuation.resume(it) }
                }
            } else {
                @Suppress("DEPRECATION")
                withContext(Dispatchers.IO) { geocoder.getFromLocation(latitude, longitude, 1) }
            }
        }.getOrNull().orEmpty()
        return addresses.firstOrNull()?.readableLabel().orEmpty()
    }

    private fun hasLocationPermission(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED }

    /**
     * Joins the address lines the geocoder filled in. Falls back to the
     * locality or country when there are none, so a coarse match still shows
     * something rather than an empty row the user cannot tell apart.
     */
    private fun Address.readableLabel(): String {
        val lines = (0..maxAddressLineIndex).mapNotNull { getAddressLine(it) }
        return lines.joinToString(", ").ifBlank { listOfNotNull(locality, countryName).joinToString(", ") }
    }

    private companion object {
        const val MAX_RESULTS = 5
    }
}
