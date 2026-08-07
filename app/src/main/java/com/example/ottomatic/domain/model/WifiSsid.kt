package com.example.ottomatic.domain.model

/**
 * The one reading of "what network is this, and is it the one the node asked for?".
 *
 * A Wi-Fi network's name reaches the app in three different disguises depending on
 * which API answered and what has been granted, and only one of them is the name:
 * `WifiInfo.getSSID()` returns it **wrapped in double quotes**, returns the literal
 * `<unknown ssid>` when the caller may not know it, and older platforms hand back
 * the MAC placeholder `02:00:00:00:00:00` instead. Reading that in each of the four
 * places that need it — the trigger, the value, the bridge and the chooser — is four
 * readings that can drift, so it is one function here, for the reason [WebUrl] and
 * [TimeOfDay] are.
 *
 * It lives in `domain` rather than behind a platform facade because that keeps it a
 * pure function with real JVM tests, where every producer of these strings needs a
 * device.
 */
object WifiSsid {

    /**
     * [raw] as the network's name, or **blank when Android would not name it**.
     *
     * Blank is not an error and not an absent value: it is what a missing
     * `ACCESS_FINE_LOCATION` grant looks like from here, and the reason the callers
     * can treat "unnameable" as one case rather than three. A network genuinely
     * broadcasting an empty SSID (a hidden one) is indistinguishable from it, which
     * is correct — neither can be matched by name.
     */
    fun normalise(raw: String?): String {
        val text = raw?.trim().orEmpty().removeSurrounding("\"")
        return if (text.equals(UNKNOWN, ignoreCase = true) || text == MAC_PLACEHOLDER) "" else text
    }

    /**
     * Whether a node configured for [configured] should act on network [actual].
     *
     * Blank [configured] means **any network**, which is how an unconfigured field
     * says "I do not care which" — the same answer the "Any network" row in the
     * chooser stores.
     *
     * The comparison is **case-sensitive**, which is not fussiness: an SSID is a
     * sequence of bytes rather than text, so `MyWifi` and `mywifi` are two different
     * networks that can legally be in range at once. Matching them loosely would
     * silently act on the wrong one.
     */
    fun matches(configured: String, actual: String): Boolean =
        configured.isBlank() || configured == actual

    /** What `WifiInfo.getSSID()` returns instead of a name it may not disclose. */
    private const val UNKNOWN = "<unknown ssid>"

    /**
     * The address older platforms substitute for a withheld identity. It shows up
     * where a *name* is expected, so it is a non-answer rather than a network.
     */
    private const val MAC_PLACEHOLDER = "02:00:00:00:00:00"
}
