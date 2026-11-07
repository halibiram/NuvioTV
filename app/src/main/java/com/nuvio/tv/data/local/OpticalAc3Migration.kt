package com.nuvio.tv.data.local

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

internal const val LEGACY_OPTICAL_AC3_COMPATIBILITY_NAME = "legacy_optical_ac3_compatibility"
internal val legacyOpticalAc3CompatibilityKey = booleanPreferencesKey(LEGACY_OPTICAL_AC3_COMPATIBILITY_NAME)
internal val surroundFormatModeKey = stringPreferencesKey("surround_format_mode")
internal val surroundChannelTargetKey = stringPreferencesKey("surround_channel_target")
internal val allowAc3PassthroughKey = booleanPreferencesKey("allow_ac3_passthrough")
internal val allowEac3PassthroughKey = booleanPreferencesKey("allow_eac3_passthrough")
internal val allowTruehdPassthroughKey = booleanPreferencesKey("allow_truehd_passthrough")
internal val allowDtsPassthroughKey = booleanPreferencesKey("allow_dts_passthrough")
internal val allowDtshdPassthroughKey = booleanPreferencesKey("allow_dtshd_passthrough")
internal val deniedCodecHandlingKey = stringPreferencesKey("denied_codec_handling")

internal object OpticalAc3Migration : DataMigration<Preferences> {
    private val legacyKey = booleanPreferencesKey("force_optical_passthrough")
    private val surroundKeys = listOf(
        surroundFormatModeKey,
        surroundChannelTargetKey,
        allowAc3PassthroughKey,
        allowEac3PassthroughKey,
        allowTruehdPassthroughKey,
        allowDtsPassthroughKey,
        allowDtshdPassthroughKey,
        deniedCodecHandlingKey
    )

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = legacyKey in currentData

    override suspend fun migrate(currentData: Preferences): Preferences {
        val updated = currentData.toMutablePreferences()
        if (currentData[legacyKey] == true && surroundKeys.none { it in currentData }) {
            updated[surroundFormatModeKey] = SurroundFormatMode.MANUAL.name
            updated[deniedCodecHandlingKey] = DeniedCodecHandling.TRANSCODE_AC3.name
            updated[allowAc3PassthroughKey] = true
            updated[allowEac3PassthroughKey] = false
            updated[allowTruehdPassthroughKey] = false
            updated[allowDtsPassthroughKey] = false
            updated[allowDtshdPassthroughKey] = false
            updated[legacyOpticalAc3CompatibilityKey] = true
        }
        updated.remove(legacyKey)
        return updated.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}
