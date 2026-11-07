package com.nuvio.tv.ui.screens.player

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import com.nuvio.tv.core.player.dvmkv.TrueHdAtmos
import java.util.Locale

/** AudioTrack is the app-to-Android boundary; firmware can still convert audio after it. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object DebugAudioFormat {
    fun describeInput(format: Format?): String? {
        if (format == null) return null
        val mime = format.sampleMimeType ?: return format.channelCount.takeIf { it > 0 }?.let(::channelLayout)
        val name = if (mime == MimeTypes.AUDIO_TRUEHD && TrueHdAtmos.isPresent(format)) {
            "Dolby Atmos TrueHD"
        } else {
            friendlyAudioName(mime)
        }
        return "$name (${mime.substringAfter('/')}) ${channelLayout(format.channelCount)}"
    }

    fun describeOutput(config: AudioSink.AudioTrackConfig, source: Format? = null): String {
        val isJoc = config.encoding == C.ENCODING_E_AC3_JOC
        val isTrueHdAtmos = config.encoding == C.ENCODING_DOLBY_TRUEHD && TrueHdAtmos.isPresent(source)
        // Media3 can configure JOC with the route's maximum channel count. The
        // AudioTrack mask in this case does not describe the compressed bed.
        val channels = if (isJoc) {
            source?.takeIf { it.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC }?.channelCount ?: Format.NO_VALUE
        } else {
            Integer.bitCount(config.channelConfig)
        }
        val hasLfe = if (isJoc) null else config.channelConfig and AudioFormat.CHANNEL_OUT_LOW_FREQUENCY != 0
        return describe(encodingName(config.encoding), channels, config.sampleRate, hasLfe) +
            if (isJoc || isTrueHdAtmos) " · Atmos" else ""
    }

    internal fun describe(codec: String, channels: Int, sampleRate: Int, hasLfe: Boolean? = null): String {
        val channelLabel = when {
            channels <= 0 -> "? ch"
            channels == 1 -> "1.0 (1 channel)"
            channels == 2 -> "2.0 (2 channels)"
            channels == 6 && hasLfe != false -> "5.1 (6 channels)"
            channels == 8 && hasLfe != false -> "7.1 (8 channels)"
            else -> "$channels channels"
        }
        val rate = if (sampleRate > 0) {
            String.format(Locale.US, "%.3f", sampleRate / 1000.0).trimEnd('0').trimEnd('.') + " kHz"
        } else "? kHz"
        return "$codec · $channelLabel · $rate"
    }

    private fun friendlyAudioName(mime: String): String = when (mime) {
        MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos DD+"
        MimeTypes.AUDIO_E_AC3 -> "Dolby Digital+"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
        MimeTypes.AUDIO_DTS_X -> "DTS:X"
        MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_ALAC -> "ALAC"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_MPEG -> "MP3"
        else -> mime.substringAfter('/')
    }

    private fun channelLayout(channelCount: Int): String = when {
        channelCount <= 0 -> "? ch"
        channelCount == 1 -> "mono"
        channelCount == 2 -> "2.0"
        channelCount == 6 -> "5.1"
        channelCount == 8 -> "7.1"
        else -> "$channelCount ch"
    }

    private fun encodingName(encoding: Int): String = when (encoding) {
        C.ENCODING_AC3 -> "DD (AC-3)"
        C.ENCODING_E_AC3 -> "DDP (E-AC-3)"
        C.ENCODING_E_AC3_JOC -> "DDP (E-AC-3)"
        C.ENCODING_DOLBY_TRUEHD -> "TrueHD"
        C.ENCODING_DTS -> "DTS"
        C.ENCODING_DTS_HD -> "DTS-HD"
        C.ENCODING_AC4 -> "AC-4"
        C.ENCODING_PCM_16BIT -> "PCM 16-bit"
        C.ENCODING_PCM_FLOAT -> "PCM float"
        C.ENCODING_PCM_24BIT -> "PCM 24-bit"
        C.ENCODING_PCM_32BIT -> "PCM 32-bit"
        else -> "encoding $encoding"
    }
}

internal data class DebugAudioOutput(val format: String, val route: String?)

/** Retains the exact track created by the normal provider; no config-release identity assumptions. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class DebugAudioTrackBinding(
    private val track: android.media.AudioTrack,
    private val config: AudioSink.AudioTrackConfig
) {
    fun snapshot(source: Format?): DebugAudioOutput? = runCatching {
        if (track.state != android.media.AudioTrack.STATE_INITIALIZED) return@runCatching null
        val device = track.routedDevice
        val route = device?.let {
            val type = when (it.type) {
                android.media.AudioDeviceInfo.TYPE_HDMI -> "HDMI"
                android.media.AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
                android.media.AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI eARC"
                android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "TV speaker"
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
                android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
                else -> "device ${it.type}"
            }
            val name = it.productName?.toString()?.trim().orEmpty()
            if (name.isEmpty() || name.equals(type, ignoreCase = true)) type else "$type / $name"
        }
        DebugAudioOutput(DebugAudioFormat.describeOutput(config, source), route)
    }.getOrNull()
}
