package dev.wahh.quotashell.core

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class CredentialCipherTest {
    private val key = SecretKeySpec(ByteArray(32) { 7 }, "AES") // synthetic test key, never production

    @Test fun ciphertextRoundTripsWithoutPlaintextAndUsesFreshIv() {
        val credential = ManualCredential("synthetic-fixture-token", "synthetic-account")
        val first = CredentialCipher.encrypt(key, ProviderId.CODEX, credential)
        val second = CredentialCipher.encrypt(key, ProviderId.CODEX, credential)
        assertFalse(first.contentEquals(second))
        assertFalse(String(first, Charsets.ISO_8859_1).contains(credential.token))
        val restored = CredentialCipher.decrypt(key, ProviderId.CODEX, first)
        assertEquals(credential.token, restored.token)
        assertEquals(credential.accountId, restored.accountId)
    }

    @Test fun legacyTwoFieldCredentialsRemainReadableForEveryProvider() {
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use {
            it.writeUTF("synthetic-legacy-token")
            it.writeUTF("synthetic-legacy-account")
        }
        for (provider in ProviderId.entries) {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(provider.name.toByteArray(Charsets.UTF_8))
            val record = cipher.iv + cipher.doFinal(bytes.toByteArray())
            val restored = CredentialCipher.decrypt(key, provider, record)
            assertEquals("synthetic-legacy-token", restored.token)
            assertEquals("synthetic-legacy-account", restored.accountId)
            assertEquals(ClaudeCredentialKind.OAUTH_TOKEN, restored.claudeKind)
            assertNull(restored.organizationId)
        }
    }

    @Test fun rejectsWrongKeyProviderTamperAndInvalidRecords() {
        val record = CredentialCipher.encrypt(key, ProviderId.CODEX, ManualCredential("fixture"))
        assertThrows(Exception::class.java) { CredentialCipher.decrypt(key, ProviderId.CLAUDE, record) }
        assertThrows(Exception::class.java) { CredentialCipher.decrypt(SecretKeySpec(ByteArray(32), "AES"), ProviderId.CODEX, record) }
        record[record.lastIndex] = (record.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { CredentialCipher.decrypt(key, ProviderId.CODEX, record) }
        for (bad in listOf(ByteArray(0), ByteArray(28), ByteArray(40001))) {
            assertThrows(Exception::class.java) { CredentialCipher.decrypt(key, ProviderId.CODEX, bad) }
        }
    }
}
