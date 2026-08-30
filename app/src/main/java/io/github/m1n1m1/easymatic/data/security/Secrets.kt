package io.github.m1n1m1.easymatic.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals a credential, so the JSON on disk holds ciphertext and nothing else.
 *
 * **What this defends against**, which is not nothing: the app's own files are
 * backed up. `android:allowBackup` is on and the backup rules are empty, so
 * everything under `filesDir` goes to cloud backup as it stands — a plaintext
 * password file would be uploaded to Google Drive. Sealing makes that copy, and an
 * `adb backup`, and a device-to-device transfer, and a forensic image of the data
 * partition, all worthless: the key is generated inside the AndroidKeyStore, is
 * non-exportable, and on every device this app targets lives in a TEE the kernel
 * cannot read out.
 *
 * **What it does not defend against**, and must never be described as if it did:
 * code already running as this app's uid on a rooted or compromised device, which
 * can simply ask for the same decryption this class performs. Nor the credential in
 * flight, which is TLS's job and not this one's.
 *
 * **An interface, unlike almost everything in `data/`.** That is bought by the test
 * plan rather than by taste: the repository tests run on the JVM against a
 * `TemporaryFolder`, and there is no AndroidKeyStore there at all.
 *
 * **Nothing here throws.** Both members answer null on every failure, for the reason
 * [io.github.m1n1m1.easymatic.core.service.Contacts.phoneNumber] does: no key, a key the
 * platform has forgotten, a ciphertext sealed under a *different* key, and a
 * keystore that misbehaved all mean one thing to the caller — this account has to be
 * set up again — and a caller that had to tell them apart could do nothing different
 * about any of them.
 */
interface Secrets {

    /** Seals [plaintext] for storage, or null when this device would not. */
    fun seal(plaintext: String): String?

    /** Opens what [seal] produced, or null when it cannot be read back. */
    fun open(sealed: String): String?
}

/**
 * [Secrets] over AES-GCM with a key held in the AndroidKeyStore under [alias].
 *
 * The key is created on first use and reused thereafter. The alias carries its
 * scheme version, and so does the stored ciphertext's prefix, so a future rotation
 * is a parse branch rather than a schema bump — the same versioned-spec-stored-as-
 * text idiom [io.github.m1n1m1.easymatic.domain.model.VariableRef] and
 * [io.github.m1n1m1.easymatic.domain.model.PhoneRef] use.
 *
 * The alias is a **constructor parameter** rather than a constant so that two
 * features do not share one key, and — more to the point — so that they do not
 * share two copies of this code. A second hand-rolled AES-GCM is how one of them
 * ends up with a subtly different constant, and a GCM mistake is silent: reusing a
 * nonce under one key is the single way to break this cipher, and nothing about the
 * output looks wrong when it happens.
 *
 * `setUserAuthenticationRequired` is deliberately **not** set, and it looks enough
 * like an oversight to be worth saying so: a macro sends mail, or turns the hall
 * light on, from a background service with the screen off and the phone in a pocket.
 * Requiring authentication would make these features work only while their user was
 * looking at them, which is the opposite of what an automation app is for.
 */
class KeystoreSecrets(private val alias: String) : Secrets {

    override fun seal(plaintext: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // No IV supplied: the keystore mints a fresh random one per encryption,
        // which is what `setRandomizedEncryptionRequired` (on by default) enforces.
        // Reusing a GCM nonce under one key is the single way to break this cipher.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }.getOrNull()

    override fun open(sealed: String): String? {
        if (!sealed.startsWith(PREFIX)) return null
        return runCatching {
            val bytes = Base64.decode(sealed.removePrefix(PREFIX), Base64.NO_WRAP)
            // Truncated to nothing but a nonce: `require` rather than an early
            // return so it lands in the same catch as every other malformed input.
            require(bytes.size > IV_BYTES) { "sealed value carries no ciphertext" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES),
            )
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "v1:"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}
