package com.nuvio.tv.ui.screens.player

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

@UnstableApi
internal class ToggleableChannelMixingAudioProcessor(
    private val enabledProvider: () -> Boolean
) : AudioProcessor {

    private val delegate = ChannelMixingAudioProcessor()

    fun putChannelMixingMatrix(matrix: ChannelMixingMatrix) {
        delegate.putChannelMixingMatrix(matrix)
    }

    override fun configure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (!enabledProvider()) return AudioProcessor.AudioFormat.NOT_SET
        return delegate.configure(inputAudioFormat)
    }

    override fun isActive(): Boolean = enabledProvider() && delegate.isActive

    override fun queueInput(inputBuffer: ByteBuffer) {
        delegate.queueInput(inputBuffer)
    }

    override fun queueEndOfStream() {
        delegate.queueEndOfStream()
    }

    override fun getOutput(): ByteBuffer = delegate.output

    override fun isEnded(): Boolean = delegate.isEnded

    override fun flush() {
        delegate.flush()
    }

    override fun reset() {
        delegate.reset()
    }
}
