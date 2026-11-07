package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes
import com.nuvio.tv.core.player.AudioPassthroughPolicy.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeniedTranscodePlannerTest {

    @Test
    fun eac3UsesDeniedEligibleFormatsAndKeepsAllowedFormatsInPassthrough() {
        val policy = AudioPassthroughPolicy(
            allowAc3 = false, allowTrueHd = false, allowDtsHd = false
        )
        assertEquals(
            setOf(MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_DTS_HD),
            DeniedTranscodePlanner.effectiveTranscodeMimes(policy, true, MimeTypes.AUDIO_E_AC3)
        )
        assertTrue(DeniedTranscodePlanner.effectiveTranscodeMimes(
            policy.copy(learnedDeniedGroups = setOf(Group.EAC3)), true, MimeTypes.AUDIO_E_AC3
        ).isEmpty())
    }

    private fun plan(
        policy: AudioPassthroughPolicy,
        transcodeEnabled: Boolean = true
    ): Set<String> = DeniedTranscodePlanner.effectiveTranscodeMimes(policy, transcodeEnabled)

    @Test
    fun optOut_yieldsEmptySet_evenWithDeniedFormats() {
        val policy = AudioPassthroughPolicy(allowDts = false, allowDtsHd = false)
        assertTrue(plan(policy, transcodeEnabled = false).isEmpty())
    }

    @Test
    fun allowAllPolicy_yieldsEmptySet_evenWhenOptedIn() {
        assertTrue(plan(AudioPassthroughPolicy.ALLOW_ALL).isEmpty())
    }

    @Test
    fun deniedDts_yieldsExactlyDtsMime() {
        val policy = AudioPassthroughPolicy(allowDts = false)
        assertEquals(setOf(MimeTypes.AUDIO_DTS), plan(policy))
    }

    @Test
    fun deniedEac3_yieldsBothEac3Mimes() {
        val policy = AudioPassthroughPolicy(allowEac3 = false)
        assertEquals(setOf(MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC), plan(policy))
    }

    @Test
    fun multipleDeniedGroups_accumulate() {
        val policy = AudioPassthroughPolicy(allowTrueHd = false, allowDtsHd = false)
        assertEquals(setOf(MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_DTS_HD), plan(policy))
    }

    @Test
    fun learnedRejection_countsAsDenied() {
        val policy = AudioPassthroughPolicy(learnedDeniedGroups = setOf(Group.DTS_HD))
        assertEquals(setOf(MimeTypes.AUDIO_DTS_HD), plan(policy))
    }

    @Test
    fun ac3SwitchedOff_yieldsEmptySet() {
        val policy = AudioPassthroughPolicy(allowAc3 = false, allowDts = false)
        assertTrue(plan(policy).isEmpty())
    }

    @Test
    fun learnedAc3Rejection_yieldsEmptySet() {
        val policy = AudioPassthroughPolicy(
            allowDts = false,
            learnedDeniedGroups = setOf(Group.AC3)
        )
        assertTrue(plan(policy).isEmpty())
    }

    @Test
    fun noSoftwareDecoders_yieldsEmptySet() {
        val policy = AudioPassthroughPolicy(allowDts = false, softwareDecodersAvailable = false)
        assertTrue(plan(policy).isEmpty())
    }

    @Test
    fun ac3_neverAppearsInTheSet_evenWithEverythingDenied() {
        val policy = AudioPassthroughPolicy(
            allowEac3 = false,
            allowTrueHd = false,
            allowDts = false,
            allowDtsHd = false
        )
        val result = plan(policy)
        assertTrue(MimeTypes.AUDIO_AC3 !in result)
        assertEquals(
            setOf(
                MimeTypes.AUDIO_E_AC3,
                MimeTypes.AUDIO_E_AC3_JOC,
                MimeTypes.AUDIO_TRUEHD,
                MimeTypes.AUDIO_DTS,
                MimeTypes.AUDIO_DTS_HD
            ),
            result
        )
    }
}
