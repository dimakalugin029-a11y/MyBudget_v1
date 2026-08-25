package ru.mybudget.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {
    @Test
    fun encryptDecrypt_roundTrip() {
        val plain = """{"version":8,"budgets":[]}"""
        val encrypted = BackupCrypto.encrypt(plain, "secret-password")
        assertTrue(BackupCrypto.isEncryptedJson(encrypted))
        val decrypted = BackupCrypto.decrypt(encrypted, "secret-password").getOrThrow()
        assertEquals(plain, decrypted)
    }

    @Test
    fun decrypt_wrongPasswordFails() {
        val encrypted = BackupCrypto.encrypt("{}", "correct")
        assertTrue(BackupCrypto.decrypt(encrypted, "wrong").isFailure)
    }

    @Test
    fun isEncryptedJson_plainJsonReturnsFalse() {
        assertFalse(BackupCrypto.isEncryptedJson("""{"version":8}"""))
    }

    @Test
    fun isEncryptedJson_blankReturnsFalse() {
        assertFalse(BackupCrypto.isEncryptedJson(""))
    }
}
