package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

internal class NightModeAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var enabled: Boolean = false

    private var hpfStage1: Array<BiquadState> = emptyArray()
    private var hpfStage2: Array<BiquadState> = emptyArray()
    private var hpfB0: Float = 0f
    private var hpfB1: Float = 0f
    private var hpfB2: Float = 0f
    private var hpfA1: Float = 0f
    private var hpfA2: Float = 0f

    fun setEnabled(on: Boolean) {
        enabled = on
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT -> {
                configureHpf(inputAudioFormat.sampleRate.toFloat(), inputAudioFormat.channelCount)
                inputAudioFormat
            }
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    private fun configureHpf(sampleRate: Float, channelCount: Int) {
        val w0 = 2.0 * PI * HPF_CUTOFF_HZ / sampleRate
        val cosW0 = cos(w0).toFloat()
        val sinW0 = sin(w0).toFloat()
        val alpha = sinW0 / (2f * HPF_Q)
        val a0 = 1f + alpha
        hpfB0 = ((1f + cosW0) / 2f) / a0
        hpfB1 = -(1f + cosW0) / a0
        hpfB2 = ((1f + cosW0) / 2f) / a0
        hpfA1 = (-2f * cosW0) / a0
        hpfA2 = (1f - alpha) / a0
        hpfStage1 = Array(channelCount) { BiquadState() }
        hpfStage2 = Array(channelCount) { BiquadState() }
    }

    private fun applyShelf(channel: Int, input: Float): Float {
        val stage1 = hpfStage1[channel]
        val y1 = hpfB0 * input + hpfB1 * stage1.x1 + hpfB2 * stage1.x2 -
            hpfA1 * stage1.y1 - hpfA2 * stage1.y2
        stage1.x2 = stage1.x1
        stage1.x1 = input
        stage1.y2 = stage1.y1
        stage1.y1 = y1

        val stage2 = hpfStage2[channel]
        val y2 = hpfB0 * y1 + hpfB1 * stage2.x1 + hpfB2 * stage2.x2 -
            hpfA1 * stage2.y1 - hpfA2 * stage2.y2
        stage2.x2 = stage2.x1
        stage2.x1 = y1
        stage2.y2 = stage2.y1
        stage2.y1 = y2
        return y2
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val inputSize = inputBuffer.remaining()
        val outputBuffer = replaceOutputBuffer(inputSize)

        if (!enabled) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val channelCount = inputAudioFormat.channelCount
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> processPcm16(inputBuffer, outputBuffer, channelCount)
            C.ENCODING_PCM_FLOAT -> processPcmFloat(inputBuffer, outputBuffer, channelCount)
            else -> outputBuffer.put(inputBuffer)
        }

        outputBuffer.flip()
    }

    private fun processPcm16(
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        channelCount: Int
    ) {
        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())
        when (channelCount) {
            2 -> processStereoPcm16(inputBuffer, outputBuffer)
            else -> processMultichannelPcm16(inputBuffer, outputBuffer, channelCount)
        }
    }

    private fun processPcmFloat(
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        channelCount: Int
    ) {
        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())
        when (channelCount) {
            2 -> processStereoPcmFloat(inputBuffer, outputBuffer)
            else -> processMultichannelPcmFloat(inputBuffer, outputBuffer, channelCount)
        }
    }

    private fun processStereoPcm16(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer) {
        while (inputBuffer.remaining() >= 4) {
            val l = inputBuffer.short.toFloat()
            val r = inputBuffer.short.toFloat()
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f * SIDE_GAIN
            val midBoost = mid * MID_GAIN
            val preL = midBoost + side
            val preR = midBoost - side
            val filtL = applyShelf(0, preL)
            val filtR = applyShelf(1, preR)
            val newL = filtL.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
            val newR = filtR.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
            outputBuffer.putShort(newL.roundToInt().toShort())
            outputBuffer.putShort(newR.roundToInt().toShort())
        }
        if (inputBuffer.hasRemaining()) outputBuffer.put(inputBuffer)
    }

    private fun processStereoPcmFloat(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer) {
        while (inputBuffer.remaining() >= 8) {
            val l = inputBuffer.float
            val r = inputBuffer.float
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f * SIDE_GAIN
            val midBoost = mid * MID_GAIN
            val preL = midBoost + side
            val preR = midBoost - side
            val filtL = applyShelf(0, preL)
            val filtR = applyShelf(1, preR)
            outputBuffer.putFloat(filtL.coerceIn(-1f, 1f))
            outputBuffer.putFloat(filtR.coerceIn(-1f, 1f))
        }
        if (inputBuffer.hasRemaining()) outputBuffer.put(inputBuffer)
    }

    private fun processMultichannelPcm16(
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        channelCount: Int
    ) {
        val frameBytes = channelCount * 2
        val centerIndex = if (channelCount >= 3) 2 else -1
        val lfeIndex = if (channelCount >= 4) 3 else -1
        while (inputBuffer.remaining() >= frameBytes) {
            for (channel in 0 until channelCount) {
                val sample = inputBuffer.short.toFloat()
                val scale = when (channel) {
                    centerIndex -> MID_GAIN
                    lfeIndex -> LFE_GAIN
                    else -> NON_CENTER_GAIN
                }
                val scaled = sample * scale
                val filtered = applyShelf(channel, scaled)
                val amplified = filtered
                    .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                outputBuffer.putShort(amplified.roundToInt().toShort())
            }
        }
        if (inputBuffer.hasRemaining()) outputBuffer.put(inputBuffer)
    }

    private fun processMultichannelPcmFloat(
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        channelCount: Int
    ) {
        val frameBytes = channelCount * 4
        val centerIndex = if (channelCount >= 3) 2 else -1
        val lfeIndex = if (channelCount >= 4) 3 else -1
        while (inputBuffer.remaining() >= frameBytes) {
            for (channel in 0 until channelCount) {
                val sample = inputBuffer.float
                val scale = when (channel) {
                    centerIndex -> MID_GAIN
                    lfeIndex -> LFE_GAIN
                    else -> NON_CENTER_GAIN
                }
                val filtered = applyShelf(channel, sample * scale)
                outputBuffer.putFloat(filtered.coerceIn(-1f, 1f))
            }
        }
        if (inputBuffer.hasRemaining()) outputBuffer.put(inputBuffer)
    }

    private class BiquadState {
        var x1: Float = 0f
        var x2: Float = 0f
        var y1: Float = 0f
        var y2: Float = 0f
    }

    companion object {
        private const val MID_BOOST_DB = 9f
        private const val SIDE_ATTENUATION_DB = -60f
        private const val NON_CENTER_ATTENUATION_DB = -48f
        private const val LFE_ATTENUATION_DB = -80f
        private const val HPF_CUTOFF_HZ = 130f
        private const val HPF_Q = 0.7071f
        private val MID_GAIN = 10f.pow(MID_BOOST_DB / 20f)
        private val SIDE_GAIN = 10f.pow(SIDE_ATTENUATION_DB / 20f)
        private val NON_CENTER_GAIN = 10f.pow(NON_CENTER_ATTENUATION_DB / 20f)
        private val LFE_GAIN = 10f.pow(LFE_ATTENUATION_DB / 20f)
    }
}
