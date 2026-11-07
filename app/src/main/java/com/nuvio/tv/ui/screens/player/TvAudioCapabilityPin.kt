package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioCapabilities

internal object TvAudioCapabilityPin {

    private val bitstreamEncodings = intArrayOf(
        C.ENCODING_AC3,
        C.ENCODING_E_AC3,
        C.ENCODING_E_AC3_JOC,
        C.ENCODING_AC4,
        C.ENCODING_DOLBY_TRUEHD,
        C.ENCODING_DTS,
        C.ENCODING_DTS_HD,
        C.ENCODING_DTS_UHD_P2
    )

    fun shouldPin(isTelevision: Boolean, supportsEncoding: (Int) -> Boolean): Boolean {
        return isTelevision && bitstreamEncodings.any(supportsEncoding)
    }

    fun pinnedCapabilities(context: Context): AudioCapabilities? {
        val isTelevision = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        if (!isTelevision) return null
        val snapshot = runCatching {
            AudioCapabilities.getCapabilities(
                context,
                AudioOutputRouteDetector.mediaMovieAudioAttributes(),
                null
            )
        }.getOrNull() ?: return null
        return snapshot.takeIf { shouldPin(isTelevision, it::supportsEncoding) }
    }

    fun legacyOpticalAc3Capabilities(context: Context): AudioCapabilities {
        val attributes = AudioOutputRouteDetector.mediaMovieAudioAttributes()
        val ac3 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3)
            .setChannelCount(6).setSampleRate(48_000).build()
        val detected = runCatching {
            AudioCapabilities.getCapabilities(context, attributes, null)
                .takeIf { it.isPassthroughPlaybackSupported(ac3, attributes) }
        }.getOrNull()
        // The old optical setting was an explicit override for devices that underreport AC-3.
        return detected ?: AudioCapabilities(intArrayOf(C.ENCODING_PCM_16BIT, C.ENCODING_AC3), 6)
    }

    fun touchesOutputChain(routeKeyChanged: Boolean, sinkDeviceTypes: Collection<Int>): Boolean {
        return routeKeyChanged ||
            sinkDeviceTypes.any { !AudioOutputRouteDetector.isBluetoothType(it) }
    }

    fun sinkTypesOf(devices: Array<out AudioDeviceInfo>): List<Int> {
        return devices.filter { it.isSink }.map { it.type }
    }
}
