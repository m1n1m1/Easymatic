package io.github.m1n1m1.easymatic.domain.model

/**
 * The one reading of an NFC tag's hardware id — how it is turned into text, how it
 * is shown to a person, and when it is not worth saving at all.
 *
 * Shared by the trigger, the trampoline activity, the capture overlay and the tag
 * library for the reason [WifiSsid] is shared: four places deciding separately what
 * counts as "the same tag" is four chances for them to disagree, and the symptom
 * would be a macro that fires for a tag it was never pointed at. Pure, so it is
 * tested on the JVM where the platform half needs a phone and a sticker.
 */
object NfcTagId {

    /**
     * The stored form of a tag's id: uppercase hex, no separators.
     *
     * Blank means **this tag cannot be identified** — an empty id, which some tech
     * types genuinely return. That is the same convention [WifiSsid.normalise] uses
     * for a network the platform will not name, and it is what lets every caller
     * treat "unidentifiable" as one case rather than three.
     */
    fun format(bytes: ByteArray?): String =
        bytes?.takeIf { it.isNotEmpty() }
            ?.joinToString("") { "%02X".format(it) }
            .orEmpty()

    /**
     * The id as shown to a person: `04:A2:3F:1B`.
     *
     * Display only. Nothing parses this back, and nothing stores it — the grouping
     * exists so somebody can read an id off the screen and compare it with another,
     * which is the only thing anybody ever does with a UID by eye.
     */
    fun display(uid: String): String =
        uid.chunked(BYTE_HEX_LENGTH).joinToString(":")

    /**
     * Whether [uid] will be a *different* id the next time this tag is tapped, in
     * which case saving it is pointless and matching on it can never work.
     *
     * Three ways that happens, and all three look exactly like an ordinary tag at
     * the moment of capture, which is why this is worth a warning rather than
     * leaving the user to discover it when the macro silently stops firing:
     *
     * - a single-size (4-byte) id beginning `0x08` is a **random id** by
     *   ISO/IEC 14443-3 — bank cards, a phone emulating a card, and DESFire in
     *   random-id mode all announce themselves this way;
     * - an `NfcB` tag regenerates its identifier on every activation, by design;
     * - an empty id is no identifier at all.
     */
    fun isUnstable(uid: String, techs: List<String> = emptyList()): Boolean =
        uid.isBlank() ||
            techs.any { it == TECH_NFC_B } ||
            (uid.length == SINGLE_SIZE_HEX_LENGTH && uid.startsWith(RANDOM_ID_PREFIX))

    /**
     * Whether a tap on the tag named [actual] should run a trigger configured for
     * [configured]. Blank [configured] means any tag, exactly as a blank SSID means
     * any network.
     */
    fun matches(configured: String, actual: String): Boolean =
        configured.isBlank() || configured == actual

    /** `android.nfc.tech.NfcB`, named rather than imported so this stays pure. */
    private const val TECH_NFC_B = "android.nfc.tech.NfcB"

    private const val BYTE_HEX_LENGTH = 2
    private const val SINGLE_SIZE_HEX_LENGTH = 8
    private const val RANDOM_ID_PREFIX = "08"
}
