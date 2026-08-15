package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.service.CalendarInfo
import com.example.ottomatic.domain.model.CalendarRef

/**
 * Which calendars this phone has, as a lookup anything in `domain` may reach.
 *
 * The sibling of [MacroDirectory] and [SmartHomeHubs], published for their reason: the
 * only thing that knows is the calendar provider, reaching it means a `ContentResolver`
 * and a suspension point, and the questions — *does this reference still resolve?* and
 * *what may an AI tool choose here?* — are asked from `GraphValidator` and
 * [PickerOptions], neither of which can suspend or be injected into.
 *
 * **[isHydrated] matters more here than anywhere else it appears.** Before `READ_CALENDAR`
 * is granted this process knows about no calendar at all, and a directory that answered
 * "not found" to everything would report every calendar node on the device as pointing at
 * something deleted — on a fresh install, before the user has done anything wrong. Empty
 * and *unasked* are different states, and only this flag tells them apart.
 *
 * Hydrated from `ServiceLocator` and re-hydrated whenever the app comes back to the
 * foreground, because calendar access is granted *outside* the app — the same reason
 * `GrantedPrerequisites` is re-read in `MainActivity.onResume`.
 */
object CalendarDirectory {

    @Volatile
    private var current: List<CalendarInfo>? = null

    /** Whether anything has published a list yet; see the class KDoc. */
    val isHydrated: Boolean get() = current != null

    /** Every calendar on the phone, in the provider's order (by name). */
    fun all(): List<CalendarInfo> = current.orEmpty()

    /** The ones an appointment can actually be added to. */
    fun writable(): List<CalendarInfo> = all().filter { it.writable }

    /**
     * The calendar [spec] names, or null when it is gone or nothing is hydrated.
     *
     * Matched on the **id and the account together**, which is [CalendarRef]'s rule
     * carried through: matching on the id alone would silently resolve to whichever
     * calendar inherited that row number after an account was removed and re-added.
     */
    fun byRef(spec: String): CalendarInfo? {
        val wanted = CalendarRef.parse(spec) ?: return null
        return current?.firstOrNull { info ->
            CalendarRef.parse(info.ref)?.let { it.calendarId == wanted.calendarId } == true
        } ?: current?.firstOrNull { info ->
            info.accountName == wanted.accountName && info.name == wanted.calendarName
        }
    }

    /** Publishes [calendars] as the current set. */
    fun hydrate(calendars: List<CalendarInfo>) {
        current = calendars
    }

    /** Returns to the unhydrated state. Test seam, mirroring [MacroDirectory.reset]. */
    internal fun reset() {
        current = null
    }
}
