package com.example.ottomatic.data

import com.example.ottomatic.data.mail.MailSecrets
import com.example.ottomatic.domain.model.MailAccount
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Persists the mail account library as a single JSON file at
 * `{filesDir}/mail/accounts.json`.
 *
 * Shaped after [NfcTagRepository] and [GeofencePlaceRepository], for their reasons:
 * the list is small, always read whole and always rendered whole, and it has to be
 * readable **synchronously**, because `TriggerHost.mailAccount` resolves an id
 * while arming and has no suspending context to read a file in.
 *
 * What is new here is the password, and the rule about it is one sentence: **this
 * repository never hands a plaintext password to `feature/`**. [password] exists
 * for the transport and for the editor's connection test; the editor writes a
 * password with [setPassword] and never reads one back, so a blank field on an
 * existing account means "leave the stored secret alone". Nobody needs to see a
 * password they already typed, and not offering it closes the last easy way to
 * read one off an unlocked phone.
 *
 * A missing or corrupt file decodes to an empty library rather than throwing, for
 * [GeofencePlaceRepository]'s reason: losing the accounts is bad, crashing the app
 * on startup is worse. The same stance is what keeps [MailSecrets] out of the
 * constructor's way — it answers null rather than throwing, so a keystore this
 * device has forgotten degrades to "type your password again" instead of taking
 * `Application.onCreate` down with it.
 */
class MailAccountRepository(
    directory: File,
    private val secrets: MailSecrets,
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val serializer = ListSerializer(MailAccount.serializer())

    private val file = File(directory, DIR_NAME).apply { mkdirs() }.let { File(it, FILE_NAME) }

    // Read synchronously at construction, as the other two libraries are: the
    // trigger host may resolve an account before any coroutine has run.
    private val cache = MutableStateFlow(readFile())

    /** The library, sorted by name, re-emitted on every mutation. */
    val accounts: StateFlow<List<MailAccount>> = cache.asStateFlow()

    /** Snapshot of [accounts]. */
    fun list(): List<MailAccount> = cache.value

    /** The account with [id], or null when it was never created or has been deleted. */
    fun get(id: String): MailAccount? = cache.value.firstOrNull { it.id == id }

    /**
     * The password for [id] right now, or null — no such account, no stored
     * secret, or a key this device has lost.
     *
     * Three failures, one answer, because nothing a caller could do differs
     * between them: every one of them means "this account cannot sign in until
     * somebody types its password again".
     */
    fun password(id: String): String? =
        get(id)?.secret?.takeIf { it.isNotBlank() }?.let(secrets::open)

    /**
     * Whether [id] would fail to authenticate for want of a readable password.
     *
     * **Derived on every call, never stored.** A persisted flag goes stale the
     * moment a password is re-entered, which is the same reason
     * [com.example.ottomatic.domain.registry.GrantedPrerequisites] re-hydrates on
     * every return to the foreground rather than caching an answer.
     *
     * The case this exists for is a **restore**: an AndroidKeyStore key is never
     * backed up, so a cloud restore or a device transfer brings the account list
     * across and leaves the key behind. Everything tedious survives — name,
     * address, host, port, username — and one field has to be typed again. That is
     * why the file is deliberately *not* excluded from backup: excluding it would
     * lose the whole account instead of one field.
     */
    fun needsPassword(id: String): Boolean =
        get(id)?.let { it.secret.isBlank() || secrets.open(it.secret) == null } ?: false

    /** Inserts [account] or replaces the entry with the same id, then persists. */
    suspend fun upsert(account: MailAccount): MailAccount {
        mutate { current ->
            val index = current.indexOfFirst { it.id == account.id }
            if (index >= 0) current.toMutableList().apply { this[index] = account } else current + account
        }
        return account
    }

    /** Removes the account with [id]; a no-op when it does not exist. */
    suspend fun delete(id: String) {
        mutate { current -> current.filterNot { it.id == id } }
    }

    /** Creates an account with a fresh id, persists it, and returns it. */
    suspend fun create(account: MailAccount): MailAccount =
        upsert(account.copy(id = UUID.randomUUID().toString()))

    /**
     * Seals [plaintext] onto [id]. Returns false when there is no such account or
     * this device would not seal it — in which case nothing is written, so a
     * keystore failure leaves the previous password in place rather than
     * replacing it with something unreadable.
     */
    suspend fun setPassword(id: String, plaintext: String): Boolean {
        val updated = get(id)?.let { account ->
            secrets.seal(plaintext)?.let { sealed -> account.copy(secret = sealed) }
        } ?: return false
        upsert(updated)
        return true
    }

    private suspend fun mutate(transform: (List<MailAccount>) -> List<MailAccount>) {
        mutex.withLock {
            val updated = transform(cache.value).sortedBy { it.name.lowercase() }
            cache.value = updated
            withContext(Dispatchers.IO) {
                runCatching { file.writeText(json.encodeToString(serializer, updated)) }
            }
        }
    }

    private fun readFile(): List<MailAccount> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        json.decodeFromString(serializer, file.readText()).sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())

    private val mutex = Mutex()

    private companion object {
        const val DIR_NAME = "mail"
        const val FILE_NAME = "accounts.json"
    }
}
