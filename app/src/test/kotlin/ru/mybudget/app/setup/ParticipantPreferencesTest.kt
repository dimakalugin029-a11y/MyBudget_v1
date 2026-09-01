package ru.mybudget.app.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ParticipantPreferencesTest {
    @Test
    fun setNames_rejectsMoreThanMax() {
        val names = (1..ParticipantPreferences.MAX_PARTICIPANTS + 1).map { "User $it" }
        assertFalse(setNamesWithoutContext(names))
    }

    @Test
    fun mergeNames_respectsMax() {
        val merged = mergeNamesLogic(
            existing = listOf("A", "B"),
            imported = listOf("C", "D", "E", "F", "G", "H", "I"),
        )
        assertEquals(ParticipantPreferences.MAX_PARTICIPANTS, merged.size)
    }

    private fun setNamesWithoutContext(names: List<String>): Boolean {
        val cleaned = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return cleaned.size <= ParticipantPreferences.MAX_PARTICIPANTS
    }

    private fun mergeNamesLogic(existing: List<String>, imported: List<String>): List<String> {
        return (existing + imported.map { it.trim() }.filter { it.isNotEmpty() })
            .distinct()
            .take(ParticipantPreferences.MAX_PARTICIPANTS)
    }
}
