package to.eyed.inferno.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Preferences <-> SettingsState conversions on a plain JVM (datastore-preferences-core needs no Context). */
class ExperienceLevelMigrationTest {
    private val developerMode = booleanPreferencesKey("developerMode")
    private val experienceLevel = stringPreferencesKey("experienceLevel")

    @Test fun freshInstallIsNormal() {
        val s = preferencesOf().toState()
        assertEquals(ExperienceLevel.NORMAL, s.experienceLevel)
        assertFalse(s.developerMode)
    }

    @Test fun storedDeveloperModeBecomesTheDeveloperTier() {
        assertEquals(ExperienceLevel.DEVELOPER, preferencesOf(developerMode to true).toState().experienceLevel)
        assertEquals(ExperienceLevel.NORMAL, preferencesOf(developerMode to false).toState().experienceLevel)
    }

    @Test fun explicitLevelWinsOverTheOldBoolean() {
        val s = preferencesOf(experienceLevel to "POWER", developerMode to true).toState()
        assertEquals(ExperienceLevel.POWER, s.experienceLevel)
        assertFalse(s.developerMode)
    }

    @Test fun unknownLevelFallsBackToTheBooleanThenTheDefault() {
        assertEquals(ExperienceLevel.DEVELOPER, preferencesOf(experienceLevel to "WIZARD", developerMode to true).toState().experienceLevel)
        assertEquals(ExperienceLevel.NORMAL, preferencesOf(experienceLevel to "WIZARD").toState().experienceLevel)
    }

    @Test fun writeKeepsTheOldBooleanInSyncForADowngrade() {
        val prefs = mutablePreferencesOf()
        prefs.write(SettingsState(experienceLevel = ExperienceLevel.DEVELOPER))
        assertEquals("DEVELOPER", prefs[experienceLevel])
        assertEquals(true, prefs[developerMode])
        prefs.write(SettingsState(experienceLevel = ExperienceLevel.POWER))
        assertEquals("POWER", prefs[experienceLevel])
        assertEquals(false, prefs[developerMode])
        assertTrue(prefs.toState().powerUser)
    }

    @Test fun everyLevelRoundTrips() {
        for (level in ExperienceLevel.entries) {
            val prefs = mutablePreferencesOf()
            prefs.write(SettingsState(experienceLevel = level))
            assertEquals(level, prefs.toState().experienceLevel)
        }
    }
}
