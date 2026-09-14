package io.github.m1n1m1.easymatic.data.security

import io.github.m1n1m1.easymatic.data.ReloadableLibrary
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Whether the escrow holds anything, and whether this phone can read it. */
sealed interface EscrowState {

    /** No backup password has been set; a backup's credentials open on this phone only. */
    data object Unset : EscrowState

    /** A password is set and this phone holds its key: every credential is kept up to date under it. */
    data class Unlocked(val entries: Int) : EscrowState

    /** The escrow came from a backup and this phone cannot read it until the password is typed. */
    data class Locked(val entries: Int) : EscrowState
}

/**
 * The password check a backup file carries in its plaintext header, read before
 * anything is restored: the salt and the verifier, and nothing that could open a byte
 * of it.
 *
 * [open] derives the key exactly as [SecretEscrow.unlock] would, so a password that
 * passes here is one that decrypts the file and puts every credential back afterwards —
 * which is what lets the restore refuse a wrong password with the phone untouched,
 * rather than discovering it after the libraries have been replaced.
 */
class EscrowChallenge(
    private val salt: String,
    private val iterations: Int,
    private val verifier: String,
) {
    /** The cipher [password] derives to when it is the right one, or null. */
    suspend fun open(password: String): PasswordCipher? = withContext(Dispatchers.Default) {
        runCatching { PasswordCipher.derive(password, Base64.getDecoder().decode(salt), iterations) }
            .getOrNull()
            ?.takeIf { it.verifies(verifier) }
    }

    suspend fun accepts(password: String): Boolean = open(password) != null
}

/** What is on disk. `encodeDefaults` so the version is always stated. */
@Serializable
private data class EscrowFile(
    val version: Int = 1,
    val salt: String = "",
    val iterations: Int = 0,
    val verifier: String = "",
    /** The derived key, sealed under this phone's Keystore — useless anywhere else, which is the point. */
    val sealedKey: String = "",
    val entries: Map<String, String> = emptyMap(),
) {
    val isSet: Boolean get() = salt.isNotBlank() && verifier.isNotBlank()

    /**
     * Entries or a key without the verifier that checks a password is a file somebody
     * edited: it is read as no escrow at all, so nothing can be applied without the
     * password being asked for.
     */
    val isConsistent: Boolean get() = isSet || (entries.isEmpty() && sealedKey.isBlank())
}

/**
 * A second copy of every credential, sealed under a password the user knows, kept in
 * `{filesDir}/secrets/escrow.json` — which is in the backup set.
 *
 * **Why it exists.** Every credential library seals under an AndroidKeyStore key that
 * never leaves the phone, so a backup carries blobs nothing else can open, and a
 * restore after a reinstall or onto another phone ends with every account asking for
 * its secret again. This file is the same secrets under a key that *does* travel — one
 * derived from a password — so that one password typed once after a restore puts every
 * credential back. Without the password the file says nothing.
 *
 * **The password is never stored.** What is stored is the derived key, sealed under the
 * Keystore like any other credential. On the phone that set the password that opens at
 * start-up and the escrow is [EscrowState.Unlocked]: every change to a credential is
 * re-escrowed without anybody being asked. On any other phone it does not open and the
 * escrow is [EscrowState.Locked] until [unlock] derives the key again from the typed
 * password — and then re-seals it under *this* phone's Keystore, so the question is
 * asked once.
 *
 * **Unlocking never overwrites a credential that already opens.** Somebody who typed a
 * password by hand before finding this screen keeps what they typed; the escrow fills
 * in only what is still unreadable.
 *
 * One `Mutex` over everything, because [refresh] arrives from three collected flows and
 * [unlock] writes into the libraries those flows are collected from.
 */
@Suppress("TooManyFunctions") // Four verbs and the file I/O under them; a split would hide the lock discipline.
class SecretEscrow(
    directory: File,
    private val keystore: Secrets,
    private val libraries: List<EscrowedLibrary>,
) : ReloadableLibrary {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val file = File(directory, DIR_NAME).apply { mkdirs() }.let { File(it, FILE_NAME) }

    private val mutex = Mutex()

    private var stored: EscrowFile = read()

    private var cipher: PasswordCipher? = unseal(stored)

    private val _state = MutableStateFlow(stateOf())

    /** For the Backup screen's card and the prompt after an Android restore. */
    val state: StateFlow<EscrowState> = _state.asStateFlow()

    /**
     * Sets or changes the backup password: a fresh salt and key, every credential this
     * phone can open sealed under it, and the key sealed under the Keystore for next time.
     * Answers the derived cipher — the one a backup file written next is encrypted with —
     * or null for a password under the floor or a cipher that would not seal.
     */
    suspend fun setPassword(password: String): PasswordCipher? = mutex.withLock {
        if (password.length < PasswordCipher.MIN_PASSWORD_LENGTH) return null
        val salt = PasswordCipher.newSalt()
        val fresh = withContext(Dispatchers.Default) { PasswordCipher.derive(password, salt) }
        val verifier = fresh.verifier() ?: return null
        cipher = fresh
        stored = EscrowFile(
            salt = encode(salt),
            iterations = fresh.iterations,
            verifier = verifier,
            sealedKey = keystore.seal(encode(fresh.keyBytes)).orEmpty(),
            entries = encrypt(fresh, collect()),
        )
        write()
        publish()
        fresh
    }

    /** Forgets the password and every escrowed copy; the libraries themselves are untouched. */
    suspend fun clear() = mutex.withLock {
        cipher = null
        stored = EscrowFile()
        write()
        publish()
    }

    /**
     * Derives the key from [password], checks it against the verifier, and when it is the
     * right one puts back every credential this phone cannot currently read.
     */
    suspend fun unlock(password: String): Boolean {
        val current = stored
        val candidate = if (current.isSet) {
            withContext(Dispatchers.Default) {
                PasswordCipher.derive(password, decode(current.salt), current.iterations)
            }
        } else {
            null
        }
        return candidate != null && unlockWith(candidate)
    }

    /**
     * [unlock] with a cipher already derived — the one that just decrypted a backup
     * file, so the password is not derived a second time for the escrow inside it. It
     * still has to pass the stored verifier: a file whose escrow was made under another
     * password stays locked, and the prompt asks for that one.
     */
    suspend fun unlockWith(candidate: PasswordCipher): Boolean = mutex.withLock {
        val current = stored
        val accepted = current.isSet && candidate.verifies(current.verifier)
        if (accepted) {
            cipher = candidate
            stored = current.copy(sealedKey = keystore.seal(encode(candidate.keyBytes)).orEmpty())
            applyLocked(candidate)
            stored = stored.copy(entries = encrypt(candidate, collect()))
            write()
            publish()
        }
        accepted
    }

    /**
     * Brings the escrow up to date with what the libraries hold now. A no-op while locked
     * (nothing could be written that the password would still open) and when nothing
     * changed, so the collectors that call this on every emission cost no writes at rest.
     */
    suspend fun refresh() = mutex.withLock {
        val active = cipher ?: return
        val fresh = collect()
        val current = stored.entries.mapNotNull { (key, sealed) -> active.open(sealed)?.let { key to it } }.toMap()
        if (fresh == current) return
        stored = stored.copy(entries = encrypt(active, fresh))
        write()
        publish()
    }

    /**
     * After a restore replaced the file: re-read it, see whether this phone holds its key,
     * and if so put back whatever the restored libraries cannot read — which on the phone
     * that made the backup is nothing, and after a change of password is everything.
     */
    override suspend fun reload() = mutex.withLock {
        stored = read()
        cipher = unseal(stored)
        cipher?.let { applyLocked(it) }
        publish()
    }

    /** Callers hold [mutex]. Seals into each library only the entries it cannot open itself. */
    private suspend fun applyLocked(active: PasswordCipher) {
        for (library in libraries) {
            val prefix = library.escrowName + SEPARATOR
            val openable = library.openSecrets()
            val missing = stored.entries
                .filterKeys { it.startsWith(prefix) }
                .mapNotNull { (key, sealed) ->
                    val own = key.removePrefix(prefix)
                    if (own in openable) null else active.open(sealed)?.let { own to it }
                }
                .toMap()
            if (missing.isNotEmpty()) library.sealSecrets(missing)
        }
    }

    private fun collect(): Map<String, String> = libraries
        .flatMap { library ->
            library.openSecrets().map { (key, value) -> library.escrowName + SEPARATOR + key to value }
        }
        .toMap()

    private fun encrypt(active: PasswordCipher, plain: Map<String, String>): Map<String, String> =
        plain.mapNotNull { (key, value) -> active.seal(value)?.let { key to it } }.toMap()

    private fun unseal(escrow: EscrowFile): PasswordCipher? = escrow.sealedKey
        .takeIf { it.isNotBlank() }
        ?.let(keystore::open)
        ?.let { runCatching { PasswordCipher.fromKey(decode(it)) }.getOrNull() }

    private fun stateOf(): EscrowState = when {
        !stored.isSet -> EscrowState.Unset
        cipher != null -> EscrowState.Unlocked(stored.entries.size)
        else -> EscrowState.Locked(stored.entries.size)
    }

    private fun publish() {
        _state.value = stateOf()
    }

    private fun read(): EscrowFile = runCatching {
        if (!file.exists()) EscrowFile() else json.decodeFromString(EscrowFile.serializer(), file.readText())
    }.getOrDefault(EscrowFile()).takeIf { it.isConsistent } ?: EscrowFile()

    private fun write() {
        runCatching { file.writeText(json.encodeToString(EscrowFile.serializer(), stored)) }
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    companion object {
        private const val DIR_NAME = "secrets"
        private const val FILE_NAME = "escrow.json"
        private const val SEPARATOR = "/"

        /** Where the escrow sits in a backup archive. */
        const val ENTRY_PATH = "$DIR_NAME/$FILE_NAME"

        private val CHALLENGE_JSON = Json { ignoreUnknownKeys = true }

        /** The password check in an escrow file's [text], or null when it holds no password. */
        fun challengeOf(text: String): EscrowChallenge? =
            runCatching { CHALLENGE_JSON.decodeFromString(EscrowFile.serializer(), text) }
                .getOrNull()
                ?.takeIf { it.isSet && it.isConsistent }
                ?.let { EscrowChallenge(it.salt, it.iterations, it.verifier) }
    }
}
