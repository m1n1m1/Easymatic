package com.example.ottomatic.domain.model

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The key a `trigger.api` node carries, and the only credential the Intent front
 * door has.
 *
 * The two inbound channels are authorised differently because they can be: a
 * [com.example.ottomatic.domain.model.ApiContract.METHOD_RUN] call through the
 * content provider arrives with a caller package the system vouches for, so it can
 * be checked against a list the user approved by name. A broadcast arrives with
 * **nothing** — `sendBroadcast` carries no sender identity at all — so the only
 * thing that can distinguish the user's own script from any other app on the phone
 * is a secret they were given and it was not. Hence a bearer token, and hence it is
 * required on that path and merely sufficient on the other.
 *
 * A token is generated, never typed. That is `PickerKind`'s argument arriving at a
 * value that does not exist yet rather than one that lives in a library: a
 * hand-written key would be short, guessable and mistyped, and a mistyped one fails
 * exactly like a stolen one, which is the worst pair of failures to confuse.
 */
object ApiTokens {

    /**
     * 192 bits, which is far past what a local IPC secret needs and costs nothing:
     * the token is copied by machine, not read aloud. Base64url-encodes to 32
     * characters with no padding, so it survives a shell command line, a URL and an
     * `--es` extra without quoting.
     */
    const val BYTES = 24

    private val random = SecureRandom()

    /** A fresh token. Never blank, so a generated one is always callable. */
    fun generate(): String {
        val bytes = ByteArray(BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Whether [presented] is the token [expected], compared in constant time.
     *
     * **A blank [expected] never matches**, including against a blank or absent
     * [presented], and that is the single most important line in this file. A
     * trigger with no key is not "callable with no key" — it is not callable by key
     * *at all*, only by an app the user approved by name, which is the stricter
     * posture. Read the other way round, an unset field would authorise every caller
     * that thought to omit the extra.
     *
     * Both sides are hashed before comparison rather than compared directly, so the
     * *length* of the expected token does not leak either — `MessageDigest.isEqual`
     * is constant-time across equal-length inputs and returns early on a length
     * mismatch, and two digests are always the same length.
     */
    fun matches(expected: String, presented: String?): Boolean {
        if (expected.isBlank() || presented.isNullOrEmpty()) return false
        return MessageDigest.isEqual(digest(expected), digest(presented))
    }

    private fun digest(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}
