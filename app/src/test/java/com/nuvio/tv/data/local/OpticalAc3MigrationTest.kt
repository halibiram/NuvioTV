package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.media3.common.MimeTypes
import com.nuvio.tv.core.player.AudioPassthroughPolicy
import com.nuvio.tv.core.player.DeniedTranscodePlanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpticalAc3MigrationTest {
    private val oldForce = booleanPreferencesKey("force_optical_passthrough")

    @Test
    fun enabledLegacySettingBecomesManualDolbyDigital() = runBlocking {
        val input = preferencesOf(oldForce to true)
        assertTrue(OpticalAc3Migration.shouldMigrate(input))
        val migrated = OpticalAc3Migration.migrate(input)

        assertNull(migrated[oldForce])
        assertEquals(SurroundFormatMode.MANUAL.name, migrated[surroundFormatModeKey])
        assertEquals(DeniedCodecHandling.TRANSCODE_AC3.name, migrated[deniedCodecHandlingKey])
        assertEquals(true, migrated[legacyOpticalAc3CompatibilityKey])
        val policy = AudioPassthroughPolicy(
            allowAc3 = migrated[allowAc3PassthroughKey]!!,
            allowEac3 = migrated[allowEac3PassthroughKey]!!,
            allowTrueHd = migrated[allowTruehdPassthroughKey]!!,
            allowDts = migrated[allowDtsPassthroughKey]!!,
            allowDtsHd = migrated[allowDtshdPassthroughKey]!!
        )
        assertEquals(
            setOf(
                MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_TRUEHD,
                MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD
            ),
            DeniedTranscodePlanner.effectiveTranscodeMimes(policy, transcodeEnabled = true)
        )
        assertFalse(OpticalAc3Migration.shouldMigrate(migrated))
        assertEquals(migrated, OpticalAc3Migration.migrate(migrated))
    }

    @Test
    fun falseOrMissingLegacySettingDoesNotEnableTheOverride() = runBlocking {
        val disabled = preferencesOf(oldForce to false)
        assertTrue(OpticalAc3Migration.shouldMigrate(disabled))
        assertEquals(emptyPreferences(), OpticalAc3Migration.migrate(disabled))
        assertFalse(OpticalAc3Migration.shouldMigrate(emptyPreferences()))
    }

    @Test
    fun explicitOutputAndManualChoicesWinOverTheLegacyFlag() = runBlocking {
        val explicit = preferencesOf(
            surroundFormatModeKey to SurroundFormatMode.MANUAL.name,
            deniedCodecHandlingKey to DeniedCodecHandling.TRANSCODE_EAC3.name,
            allowDtsPassthroughKey to false
        )
        val withLegacy = explicit.toMutablePreferences().apply { this[oldForce] = true }
        assertEquals(explicit, OpticalAc3Migration.migrate(withLegacy))
    }

    @Test
    fun anExistingChannelTargetIsPreserved() = runBlocking {
        val existing = preferencesOf(surroundChannelTargetKey to SurroundChannelTarget.CH_2_0.name)
        val withLegacy = existing.toMutablePreferences().apply { this[oldForce] = true }
        assertEquals(existing, OpticalAc3Migration.migrate(withLegacy))
    }
}
