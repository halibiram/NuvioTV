package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

internal class CenterChannelGainAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var gainDb: Int = 0

    @Volatile
    private var gainScale: Float = 1f

    fun setGainDb(db: Int) {
        val clampedDb = db.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
        gainDb = clampedDb
        gainScale = if (clampedDb == 0) 1f else 10.0.pow(clampedDb / 20.0).toFloat()
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.channelCount < 3) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val inputSize = inputBuffer.remaining()
        val outputBuffer = replaceOutputBuffer(inputSize)
        val scale = gainScale

        if (scale == 1f) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val channelCount = inputAudioFormat.channelCount
        val centerIndex = CENTER_CHANNEL_INDEX

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> processPcm16(inputBuffer, outputBuffer, scale, channelCount, centerIndex)
            C.ENCODING_PCM_FLOAT -> processPcmFloat(inputBuffer, outputBuffer, scale, channelCount, centerIndex)
            else -> outputBuffer.put(inputBuffer)
        }

        outputBuffer.flip()
    }

    private fun processPcm16(
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        scale: Float,
        channelCount: Int,
        centerIndex: Int
    ) {
        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        val frameBytes = channelCount * 2
        while (inputBuffer.remaining() >= frameBytes) {
            for (channel in 0 until channelCount) {
                val sample = inputBuffer.short.toInt()
                val outSample = if (channel == centerIndex) {
                    (sample * scale)
                        .roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                } else {
                    sample
                }
                outputBuffer.putShort(outSample.toShort())
            }
        }

        if (inputBuffer.hasRemaining()) {
            outputBuffer.put(inputBuffer)
        }
    }

    private fun processPcmFloat(
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        scale: Float,
        channelCount: Int,
        centerIndex: Int
    ) {
        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        val frameBytes = channelCount * 4
        while (inputBuffer.remaining() >= frameBytes) {
            for (channel in 0 until channelCount) {
                val sample = inputBuffer.float
                val outSample = if (channel == centerIndex) {
                    (sample * scale).coerceIn(-1f, 1f)
                } else {
                    sample
                }
                outputBuffer.putFloat(outSample)
            }
        }

        if (inputBuffer.hasRemaining()) {
            outputBuffer.put(inputBuffer)
        }
    }

    companion object {
        private const val CENTER_CHANNEL_INDEX = 2
    }
}
