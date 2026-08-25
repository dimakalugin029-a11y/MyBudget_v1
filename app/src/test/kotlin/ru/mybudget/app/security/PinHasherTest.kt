package ru.mybudget.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {
    @Test
    fun hashAndVerify_samePinMatches() {
        val salt = PinHasher.generateSalt()
        val hash = PinHasher.hashPin("1234", salt)
        assertTrue(PinHasher.verifyPin("1234", salt, hash))
    }

    @Test
    fun verifyPin_wrongPinFails() {
        val salt = PinHasher.generateSalt()
        val hash = PinHasher.hashPin("1234", salt)
        assertFalse(PinHasher.verifyPin("4321", salt, hash))
    }

    @Test
    fun hashPin_differentSaltsProduceDifferentHashes() {
        val salt1 = PinHasher.generateSalt()
        val salt2 = PinHasher.generateSalt()
        assertNotEquals(
            PinHasher.hashPin("1234", salt1).contentToString(),
            PinHasher.hashPin("1234", salt2).contentToString(),
        )
    }

    @Test
    fun verifyLegacyPin_usesHashCode() {
        assertTrue(PinHasher.verifyLegacyPin("1234", "1234".hashCode()))
        assertFalse(PinHasher.verifyLegacyPin("1234", "4321".hashCode()))
    }
}
