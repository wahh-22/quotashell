package dev.wahh.quotashell

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.wahh.quotashell.core.CredentialCipher
import dev.wahh.quotashell.core.ManualCredential
import dev.wahh.quotashell.core.ProviderId
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Separate encrypted preferences. AES key never leaves Android Keystore; backup is disabled. */
class SecureCredentialStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("encrypted_credentials_v1", Context.MODE_PRIVATE)
    private val alias = "quotashell.credentials.v1"

    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }.generateKey()
    }

    @Synchronized fun read(provider: ProviderId): ManualCredential? {
        val encoded = prefs.getString(provider.name, null) ?: return null
        return CredentialCipher.decrypt(key(), provider, Base64.decode(encoded, Base64.NO_WRAP))
    }

    /**
     * Call through CredentialMutations: commit() may change memory even when it returns false.
     * Failure does not establish either rollback or the credential that will survive restart.
     */
    @Synchronized fun replace(
        provider: ProviderId,
        credential: ManualCredential?,
    ) {
        if (credential == null) {
            check(prefs.edit().remove(provider.name).commit()) { "Credential persistence unconfirmed" }
            return
        }
        val record = CredentialCipher.encrypt(key(), provider, credential)
        check(prefs.edit().putString(provider.name, Base64.encodeToString(record, Base64.NO_WRAP)).commit()) {
            "Credential persistence unconfirmed"
        }
    }
}
