package dev.wahh.quotashell.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Version belongs to the store/Keystore alias. Each record binds its provider via GCM AAD. */
object CredentialCipher {
    fun encrypt(
        key: SecretKey,
        provider: ProviderId,
        credential: ManualCredential,
    ): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use {
            it.writeUTF(credential.token)
            it.writeUTF(credential.accountId ?: "")
        }
        val plain = buffer.toByteArray()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(provider.name.toByteArray(Charsets.UTF_8))
            check(cipher.iv.size == 12)
            return cipher.iv + cipher.doFinal(plain)
        } finally {
            plain.fill(0)
        }
    }

    fun decrypt(
        key: SecretKey,
        provider: ProviderId,
        record: ByteArray,
    ): ManualCredential {
        require(record.size in 29..40000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, record.copyOfRange(0, 12)))
        cipher.updateAAD(provider.name.toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(record, 12, record.size - 12)
        try {
            return DataInputStream(ByteArrayInputStream(plain)).use {
                val token = it.readUTF()
                val account = it.readUTF().ifEmpty { null }
                require(it.available() == 0)
                ManualCredential(token, account)
            }
        } finally {
            plain.fill(0)
        }
    }
}
