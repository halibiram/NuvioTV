package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

internal class NightModeAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var compressionEnabled: Boolean = false

    @Volatile
    private var compressionThresholdFraction: Float = 1f

    @Volatile
    private var compressionMaxGain: Float = 1f

    @Volatile
    private var peakLimitFraction: Float = 1f

    @Volatile
    private var peakMinGain: Float = 1f

    @Volatile
    private var midStaticGain: Float = MID_GAIN

    @Volatile
    private var currentSideGain: Float = SIDE_GAIN_MIN

    @Volatile
    private var currentNonCenterGain: Float = NON_CENTER_GAIN_MIN

    @Volatile
    private var currentLfeGain: Float = LFE_GAIN_MIN

    @Volatile
    private var currentHpfCutoffHz: Float = HPF_CUTOFF_MAX_HZ

    private var attackCoeff: Float = 0f
    private var releaseCoeff: Float = 0f
    private var gainSmoothCoeff: Float = 0f

    private var stereoMidEnvelope: Float = 0f
    private var stereoMidGain: Float = 1f
    private var multichannelFcEnvelope: Float = 0f
    private var multichannelFcGain: Float = 1f

    private var hpfStage1: Array<BiquadState> = emptyArray()
    private var hpfStage2: Array<BiquadState> = emptyArray()
    private var hpfB0: Float = 0f
    private var hpfB1: Float = 0f
    private var hpfB2: Float = 0f
    private var hpfA1: Float = 0f
    private var hpfA2: Float = 0f

    private var mudStates: Array<BiquadState> = emptyArray()

    @Volatile
    private var mudB0: Float = 1f

    @Volatile
    private var mudB1: Float = 0f

    @Volatile
    private var mudB2: Float = 0f

    @Volatile
    private var mudA1: Float = 0f

    @Volatile
    private var mudA2: Float = 0f

    private var cachedSampleRate: Float = 0f

    fun setEnabled(on: Boolean) {
        enabled = on
    }

    fun setDialogIsolationDb(extraDb: Float) {
        val amp = (extraDb * 0.5f).coerceIn(0f, AMP_RANGE_DB)
        val ampNorm = amp / AMP_RANGE_DB
        val sideDb =
            SIDE_ATTENUATION_MIN_DB + ampNorm * (SIDE_ATTENUATION_MAX_DB - SIDE_ATTENUATION_MIN_DB)
        val nonCenterDb = NON_CENTER_ATTENUATION_MIN_DB +
            ampNorm * (NON_CENTER_ATTENUATION_MAX_DB - NON_CENTER_ATTENUATION_MIN_DB)
        val mudCutDb = MUD_CUT_MIN_DB + ampNorm * (MUD_CUT_MAX_DB - MUD_CUT_MIN_DB)
        val lfeDb = LFE_ATTENUATION_MIN_DB + ampNorm * (LFE_ATTENUATION_MAX_DB - LFE_ATTENUATION_MIN_DB)
        val hpfHz = HPF_CUTOFF_MAX_HZ + ampNorm * (HPF_CUTOFF_MIN_HZ - HPF_CUTOFF_MAX_HZ)

        currentSideGain = 10f.pow(sideDb / 20f)
        currentNonCenterGain = 10f.pow(nonCenterDb / 20f)
        currentLfeGain = 10f.pow(lfeDb / 20f)
        currentHpfCutoffHz = hpfHz

        updateMudCoefficients(mudCutDb)
        updateHpfCoefficients(hpfHz)
        if (amp <= 0f) {
            compressionEnabled = false
            compressionThresholdFraction = 1f
            compressionMaxGain = 1f
            peakLimitFraction = 1f
            peakMinGain = 1f
            midStaticGain = MID_GAIN
            return
        }
        val thresholdDb =
            COMP_THRESHOLD_HIGH_DB + ampNorm * (COMP_THRESHOLD_LOW_DB - COMP_THRESHOLD_HIGH_DB)
        val maxBoostDb = ampNorm * COMP_MAX_BOOST_DB
        val staticBoostDb = MID_STATIC_BOOST_MIN_DB +
            ampNorm * (MID_STATIC_BOOST_MAX_DB - MID_STATIC_BOOST_MIN_DB)
        val peakThresholdDb =
            PEAK_THRESHOLD_HIGH_DB + ampNorm * (PEAK_THRESHOLD_LOW_DB - PEAK_THRESHOLD_HIGH_DB)
        val peakMaxAttenDb = ampNorm * PEAK_MAX_ATTEN_DB
        compressionThresholdFraction = 10f.pow(thresholdDb / 20f)
        compressionMaxGain = 10f.pow(maxBoostDb / 20f)
        peakLimitFraction = 10f.pow(peakThresholdDb / 20f)
        peakMinGain = 10f.pow(-peakMaxAttenDb / 20f)
        midStaticGain = 10f.pow((MID_BOOST_DB + staticBoostDb) / 20f)
        compressionEnabled = true
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT -> {
                val sampleRate = inputAudioFormat.sampleRate.toFloat()
                cachedSampleRate = sampleRate
                configureHpf(sampleRate, inputAudioFormat.channelCount)
                configureCompressor(sampleRate)
                configureMud(inputAudioFormat.channelCount)
                inputAudioFormat
            }
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    private fun configureMud(channelCount: Int) {
        mudStates = Array(channelCount) { BiquadState() }
        // Coefficients will be updated on next setDialogIsolationDb call; default unity passthrough.
        mudB0 = 1f
        mudB1 = 0f
        mudB2 = 0f
        mudA1 = 0f
        mudA2 = 0f
    }

    private fun updateMudCoefficients(gainDb: Float) {
        val sampleRate = cachedSampleRate
        if (sampleRate <= 0f) return
        if (gainDb >= 0f) {
            mudB0 = 1f
            mudB1 = 0f
            mudB2 = 0f
            mudA1 = 0f
            mudA2 = 0f
            return
        }
        val a = 10f.pow(gainDb / 40f)
        val w0 = 2.0 * PI * MUD_FREQ_HZ / sampleRate
        val cosW0 = cos(w0).toFloat()
        val sinW0 = sin(w0).toFloat()
        val alpha = sinW0 / (2f * MUD_Q)
        val a0 = 1f + alpha / a
        mudB0 = (1f + alpha * a) / a0
        mudB1 = (-2f * cosW0) / a0
        mudB2 = (1f - alpha * a) / a0
        mudA1 = (-2f * cosW0) / a0
        mudA2 = (1f - alpha / a) / a0
    }

    private fun applyMud(channel: Int, input: Float): Float {
        if (channel >= mudStates.size) return input
        val state = mudStates[channel]
        val y = mudB0 * input + mudB1 * state.x1 + mudB2 * state.x2 -
            mudA1 * state.y1 - mudA2 * state.y2
        state.x2 = state.x1
        state.x1 = input
        state.y2 = state.y1
        state.y1 = y
        return y
    }

    private fun configureCompressor(sampleRate: Float) {
        attackCoeff = exp(-1f / (sampleRate * COMP_ATTACK_SECONDS))
        releaseCoeff = exp(-1f / (sampleRate * COMP_RELEASE_SECONDS))
        gainSmoothCoeff = exp(-1f / (sampleRate * COMP_GAIN_SMOOTH_SECONDS))
        stereoMidEnvelope = 0f
        stereoMidGain = 1f
        multichannelFcEnvelope = 0f
        multichannelFcGain = 1f
    }

    private fun updateStereoMidGain(mid: Float, fullScale: Float): Float {
        if (!compressionEnabled) return MID_GAIN
        val absMid = if (mid < 0f) -mid else mid
        val coeff = if (absMid > stereoMidEnvelope) attackCoeff else releaseCoeff
        stereoMidEnvelope = absMid + coeff * (stereoMidEnvelope - absMid)
        val thresholdSamples = compressionThresholdFraction * fullScale
        val peakSamples = peakLimitFraction * fullScale
        val noiseFloor = NOISE_FLOOR_FRACTION * fullScale
        val rawGain = when {
            stereoMidEnvelope < noiseFloor -> 1f
            stereoMidEnvelope < thresholdSamples ->
                (thresholdSamples / stereoMidEnvelope).coerceAtMost(compressionMaxGain)
            stereoMidEnvelope > peakSamples ->
                (peakSamples / stereoMidEnvelope).coerceAtLeast(peakMinGain)
            else -> 1f
        }
        stereoMidGain = rawGain + gainSmoothCoeff * (stereoMidGain - rawGain)
        return stereoMidGain * midStaticGain
    }

    private fun updateMultichannelFcGain(fc: Float, fullScale: Float): Float {
        if (!compressionEnabled) return MID_GAIN
        val absFc = if (fc < 0f) -fc else fc
        val coeff = if (absFc > multichannelFcEnvelope) attackCoeff else releaseCoeff
        multichannelFcEnvelope = absFc + coeff * (multichannelFcEnvelope - absFc)
        val thresholdSamples = compressionThresholdFraction * fullScale
        val peakSamples = peakLimitFraction * fullScale
        val noiseFloor = NOISE_FLOOR_FRACTION * fullScale
        val rawGain = when {
            multichannelFcEnvelope < noiseFloor -> 1f
            multichannelFcEnvelope < thresholdSamples ->
                (thresholdSamples / multichannelFcEnvelope).coerceAtMost(compressionMaxGain)
            multichannelFcEnvelope > peakSamples ->
                (peakSamples / multichannelFcEnvelope).coerceAtLeast(peakMinGain)
            else -> 1f
        }
        multichannelFcGain = rawGain + gainSmoothCoeff * (multichannelFcGain - rawGain)
        return multichannelFcGain * midStaticGain
    }

    private fun updateHpfCoefficients(cutoffHz: Float) {
        val sampleRate = cachedSampleRate
        if (sampleRate <= 0f) return
        val w0 = 2.0 * PI * cutoffHz / sampleRate
        val cosW0 = cos(w0).toFloat()
        val sinW0 = sin(w0).toFloat()
        val alpha = sinW0 / (2f * HPF_Q)
        val a0 = 1f + alpha
        hpfB0 = ((1f + cosW0) / 2f) / a0
        hpfB1 = -(1f + cosW0) / a0
        hpfB2 = ((1f + cosW0) / 2f) / a0
        hpfA1 = (-2f * cosW0) / a0
        hpfA2 = (1f - alpha) / a0
    }

    private fun configureHpf(sampleRate: Float, channelCount: Int) {
        updateHpfCoefficients(currentHpfCutoffHz)
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
        val fullScale = SHORT_FULL_SCALE
        val sideGain = currentSideGain
        while (inputBuffer.remaining() >= 4) {
            val l = inputBuffer.short.toFloat()
            val r = inputBuffer.short.toFloat()
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f * sideGain
            val midBoost = mid * updateStereoMidGain(mid, fullScale)
            val preL = midBoost + side
            val preR = midBoost - side
            val hpL = applyShelf(0, preL)
            val hpR = applyShelf(1, preR)
            val filtL = applyMud(0, hpL)
            val filtR = applyMud(1, hpR)
            val newL = filtL.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
            val newR = filtR.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
            outputBuffer.putShort(newL.roundToInt().toShort())
            outputBuffer.putShort(newR.roundToInt().toShort())
        }
        if (inputBuffer.hasRemaining()) outputBuffer.put(inputBuffer)
    }

    private fun processStereoPcmFloat(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer) {
        val fullScale = FLOAT_FULL_SCALE
        val sideGain = currentSideGain
        while (inputBuffer.remaining() >= 8) {
            val l = inputBuffer.float
            val r = inputBuffer.float
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f * sideGain
            val midBoost = mid * updateStereoMidGain(mid, fullScale)
            val preL = midBoost + side
            val preR = midBoost - side
            val hpL = applyShelf(0, preL)
            val hpR = applyShelf(1, preR)
            val filtL = applyMud(0, hpL)
            val filtR = applyMud(1, hpR)
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
        val fullScale = SHORT_FULL_SCALE
        val nonCenterGain = currentNonCenterGain
        while (inputBuffer.remaining() >= frameBytes) {
            for (channel in 0 until channelCount) {
                val sample = inputBuffer.short.toFloat()
                val scale = when (channel) {
                    centerIndex -> updateMultichannelFcGain(sample, fullScale)
                    lfeIndex -> currentLfeGain
                    else -> nonCenterGain
                }
                val scaled = sample * scale
                val hpFiltered = applyShelf(channel, scaled)
                val filtered = if (channel == centerIndex) {
                    applyMud(channel, hpFiltered)
                } else {
                    hpFiltered
                }
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
        val fullScale = FLOAT_FULL_SCALE
        val nonCenterGain = currentNonCenterGain
        while (inputBuffer.remaining() >= frameBytes) {
            for (channel in 0 until channelCount) {
                val sample = inputBuffer.float
                val scale = when (channel) {
                    centerIndex -> updateMultichannelFcGain(sample, fullScale)
                    lfeIndex -> currentLfeGain
                    else -> nonCenterGain
                }
                val hpFiltered = applyShelf(channel, sample * scale)
                val filtered = if (channel == centerIndex) {
                    applyMud(channel, hpFiltered)
                } else {
                    hpFiltered
                }
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
        private const val MID_BOOST_DB = 0f
        private const val SIDE_ATTENUATION_MIN_DB = -12f
        private const val SIDE_ATTENUATION_MAX_DB = 0f
        private const val NON_CENTER_ATTENUATION_MIN_DB = -12f
        private const val NON_CENTER_ATTENUATION_MAX_DB = 0f
        private const val LFE_ATTENUATION_MIN_DB = -80f
        private const val LFE_ATTENUATION_MAX_DB = 0f
        private const val HPF_CUTOFF_MAX_HZ = 60f
        private const val HPF_CUTOFF_MIN_HZ = 20f
        private const val HPF_Q = 0.7071f
        private val MID_GAIN = 10f.pow(MID_BOOST_DB / 20f)
        private val SIDE_GAIN_MIN = 10f.pow(SIDE_ATTENUATION_MIN_DB / 20f)
        private val NON_CENTER_GAIN_MIN = 10f.pow(NON_CENTER_ATTENUATION_MIN_DB / 20f)
        private val LFE_GAIN_MIN = 10f.pow(LFE_ATTENUATION_MIN_DB / 20f)

        private const val AMP_RANGE_DB = 10f
        private const val COMP_THRESHOLD_HIGH_DB = -12f
        private const val COMP_THRESHOLD_LOW_DB = -30f
        private const val COMP_MAX_BOOST_DB = 24f
        private const val MID_STATIC_BOOST_MIN_DB = 0f
        private const val MID_STATIC_BOOST_MAX_DB = 0f
        private const val PEAK_THRESHOLD_HIGH_DB = -3f
        private const val PEAK_THRESHOLD_LOW_DB = -12f
        private const val PEAK_MAX_ATTEN_DB = 18f
        private const val MUD_FREQ_HZ = 200f
        private const val MUD_Q = 1.0f
        private const val MUD_CUT_MIN_DB = -5f
        private const val MUD_CUT_MAX_DB = 0f
        private const val COMP_ATTACK_SECONDS = 0.010f
        private const val COMP_RELEASE_SECONDS = 0.200f
        private const val COMP_GAIN_SMOOTH_SECONDS = 0.020f
        private const val NOISE_FLOOR_FRACTION = 0.0005f
        private const val SHORT_FULL_SCALE = 32768f
        private const val FLOAT_FULL_SCALE = 1f
    }
}
