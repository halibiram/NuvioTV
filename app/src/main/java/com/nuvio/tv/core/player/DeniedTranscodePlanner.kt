package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes

object DeniedTranscodePlanner {

    private val candidateMimeTypes = listOf(
        MimeTypes.AUDIO_E_AC3,
        MimeTypes.AUDIO_E_AC3_JOC,
        MimeTypes.AUDIO_TRUEHD,
        MimeTypes.AUDIO_DTS,
        MimeTypes.AUDIO_DTS_HD
    )

    fun effectiveTranscodeMimes(
        policy: AudioPassthroughPolicy,
        transcodeEnabled: Boolean,
        outputMimeType: String = MimeTypes.AUDIO_AC3
    ): Set<String> {
        if (!transcodeEnabled) return emptySet()
        if (!policy.softwareDecodersAvailable) return emptySet()
        if (outputMimeType != MimeTypes.AUDIO_AC3 && outputMimeType != MimeTypes.AUDIO_E_AC3) return emptySet()
        if (policy.deniesPassthrough(outputMimeType)) return emptySet()
        return candidateMimeTypes.filter { mime ->
            mime != outputMimeType &&
                (outputMimeType == MimeTypes.AUDIO_AC3 || mime != MimeTypes.AUDIO_E_AC3_JOC) &&
                policy.deniesPassthrough(mime)
        }.toSet()
    }
}
