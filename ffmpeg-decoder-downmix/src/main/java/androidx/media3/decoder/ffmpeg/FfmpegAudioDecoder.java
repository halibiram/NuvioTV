/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This modified decoder module is distributed with NuvioTV under GPL-3.0-only.
 * It is based on AndroidX Media3 decoder_ffmpeg; the original Apache-2.0 notice
 * is preserved below. Additional downmix behavior was adapted from Kodi
 * (GPL-2.0-or-later). See this module's NOTICE.md for provenance details.
 */
/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.decoder.ffmpeg;

import static com.google.common.base.Preconditions.checkNotNull;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.nio.ByteBuffer;
import java.util.List;

/** FFmpeg audio decoder with optional float output for active FFmpeg downmixing. */
/* package */ final class FfmpegAudioDecoder
    extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, FfmpegDecoderException> {

  // Private synthetic input used by EncodedEosDecoder to advance the native
  // drain on the SimpleDecoder worker thread. Never passed to FFmpeg as media.
  static final long ENCODED_DRAIN_INPUT_TIME_US = Long.MAX_VALUE;
  static final long ENCODED_PENDING_INPUT_TIME_US = Long.MAX_VALUE - 1;

  private static final int INITIAL_OUTPUT_BUFFER_SIZE = 65535 * 2;

  private static final int AUDIO_DECODER_ERROR_INVALID_DATA = -1;
  private static final int AUDIO_DECODER_ERROR_OTHER = -2;
  private static final int AUDIO_DECODER_ERROR_EAC3_LAYOUT_MISMATCH = -1000;

  // FLAC parsing constants
  private static final byte[] flacStreamMarker = {'f', 'L', 'a', 'C'};
  private static final int FLAC_METADATA_TYPE_STREAM_INFO = 0;
  private static final int FLAC_METADATA_BLOCK_HEADER_SIZE = 4;
  private static final int FLAC_STREAM_INFO_DATA_SIZE = 34;

  private final String codecName;
  @Nullable private final byte[] extraData;
  private final @C.PcmEncoding int outputEncoding;
  private volatile int userCenterMixLevelDb;
  private volatile boolean downmixNormalizationEnabled;
  private volatile float lfeMixLevel;
  private int outputBufferSize;

  private long nativeContext; // May be reassigned on resetting the codec.
  private boolean hasOutputFormat;
  private volatile int channelCount;
  private volatile int sampleRate;
  // First input timestamp anchors native encoder-relative PTS to media time.
  private long encodedTimeAnchorUs = C.TIME_UNSET;
  // Accessed only by the decoder/output scheduler thread. Reset on seek.
  private volatile boolean encodedEndOfStreamComplete;
  private volatile boolean syntheticDrainStepFinished;
  private volatile int pendingEncodedPacketsSnapshot;

  public FfmpegAudioDecoder(
      Format format,
      int numInputBuffers,
      int numOutputBuffers,
      int initialInputBufferSize,
      int outputChannelCount,
      @Nullable String requestedOutputLayoutName,
      @C.PcmEncoding int outputEncoding)
      throws FfmpegDecoderException {
    super(new DecoderInputBuffer[numInputBuffers], new SimpleDecoderOutputBuffer[numOutputBuffers]);
    if (!FfmpegLibrary.isAvailable()) {
      throw new FfmpegDecoderException("Failed to load decoder native libraries.");
    }
    checkNotNull(format.sampleMimeType);
    codecName = checkNotNull(FfmpegLibrary.getCodecName(format.sampleMimeType));
    extraData = getExtraData(format.sampleMimeType, format.initializationData);
    this.outputEncoding = outputEncoding;
    outputBufferSize = INITIAL_OUTPUT_BUFFER_SIZE;
    nativeContext =
        ffmpegInitialize(
            codecName,
            extraData,
            format.sampleRate,
            format.channelCount,
            outputChannelCount,
            requestedOutputLayoutName,
            outputEncoding == C.ENCODING_PCM_FLOAT,
            outputEncoding == C.ENCODING_E_AC3 ? 2
                : outputEncoding == C.ENCODING_AC3 ? 1 : 0);
    if (nativeContext == 0) {
      throw new FfmpegDecoderException("Initialization failed.");
    }
    setInitialInputBufferSize(initialInputBufferSize);
  }

  @Override
  public String getName() {
    return "ffmpeg" + FfmpegLibrary.getVersion() + "-" + codecName;
  }

  @Override
  protected DecoderInputBuffer createInputBuffer() {
    return new DecoderInputBuffer(
        DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT,
        FfmpegLibrary.getInputBufferPaddingSize());
  }

  @Override
  protected SimpleDecoderOutputBuffer createOutputBuffer() {
    return new SimpleDecoderOutputBuffer(this::releaseOutputBuffer);
  }

  @Override
  protected FfmpegDecoderException createUnexpectedDecodeException(Throwable error) {
    return new FfmpegDecoderException("Unexpected decode error", error);
  }

  @Override
  @Nullable
  protected FfmpegDecoderException decode(
      DecoderInputBuffer inputBuffer, SimpleDecoderOutputBuffer outputBuffer, boolean reset) {
    if (reset) {
      nativeContext = ffmpegReset(nativeContext, extraData);
      if (nativeContext == 0) {
        return new FfmpegDecoderException("Error resetting (see logcat).");
      }
      // The native encoder/resampler may have been recreated after seeking.
      // Query the resulting output format again on the next non-empty buffer.
      hasOutputFormat = false;
      encodedTimeAnchorUs = C.TIME_UNSET;
      encodedEndOfStreamComplete = false;
      syntheticDrainStepFinished = false;
      pendingEncodedPacketsSnapshot = 0;
    }
    if (inputBuffer.timeUs == ENCODED_PENDING_INPUT_TIME_US) {
      try {
        if (!dequeuePendingEncodedPacket(outputBuffer)) {
          outputBuffer.shouldBeSkipped = true;
        }
        return null;
      } catch (FfmpegDecoderException error) {
        return error;
      } finally {
        pendingEncodedPacketsSnapshot = getPendingEncodedPacketCount();
        syntheticDrainStepFinished = true;
        
      }
    }
    if (inputBuffer.timeUs == ENCODED_DRAIN_INPUT_TIME_US) {
      try {
        if (!drainNextEncodedEndOfStreamBuffer(outputBuffer)) {
          outputBuffer.shouldBeSkipped = true;
        }
        return null;
      } catch (FfmpegDecoderException error) {
        return error;
      } finally {
        // Allows the renderer-facing wrapper to schedule the next drain
        // step even when SimpleDecoder discards an empty output buffer.
        pendingEncodedPacketsSnapshot = getPendingEncodedPacketCount();
        syntheticDrainStepFinished = true;
        
      }
    }
    if ((outputEncoding == C.ENCODING_AC3 || outputEncoding == C.ENCODING_E_AC3)
        && encodedTimeAnchorUs == C.TIME_UNSET) {
      encodedTimeAnchorUs = inputBuffer.timeUs;
    }
    ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
    int inputSize = inputData.limit();
    ByteBuffer outputData = outputBuffer.init(inputBuffer.timeUs, outputBufferSize);
    int result =
        ffmpegDecode(
            nativeContext,
            inputData,
            inputSize,
            outputBuffer,
            outputData,
            outputBufferSize,
            userCenterMixLevelDb,
            downmixNormalizationEnabled,
            lfeMixLevel);
    if (isCompressedOutput()) {
      pendingEncodedPacketsSnapshot = getPendingEncodedPacketCount();
    }
    if (result == AUDIO_DECODER_ERROR_EAC3_LAYOUT_MISMATCH) {
      return FfmpegDecoderException.eac3LayoutMismatch();
    } else if (result == AUDIO_DECODER_ERROR_OTHER) {
      return new FfmpegDecoderException("Error decoding (see logcat).");
    } else if (result == AUDIO_DECODER_ERROR_INVALID_DATA) {
      // Treat invalid data errors as non-fatal to match the behavior of MediaCodec. No output will
      // be produced for this buffer, so mark it as skipped to ensure that the audio sink's
      // position is reset when more audio is produced.
      outputBuffer.shouldBeSkipped = true;
      return null;
    } else if (result == 0) {
      // There's no need to output empty buffers.
      outputBuffer.shouldBeSkipped = true;
      return null;
    }
    if (!hasOutputFormat) {
      channelCount = ffmpegGetChannelCount(nativeContext);
      sampleRate = ffmpegGetSampleRate(nativeContext);
      if (sampleRate == 0 && "alac".equals(codecName)) {
        checkNotNull(extraData);
        // ALAC decoder did not set the sample rate in earlier versions of FFmpeg. See
        // https://trac.ffmpeg.org/ticket/6096.
        ParsableByteArray parsableExtraData = new ParsableByteArray(extraData);
        parsableExtraData.setPosition(extraData.length - 4);
        sampleRate = parsableExtraData.readUnsignedIntToInt();
      }
      hasOutputFormat = true;
    }
    // Get a new reference to the output ByteBuffer in case the native decode method reallocated the
    // buffer to grow its size.
    outputData = checkNotNull(outputBuffer.data);
    outputData.position(0);
    outputData.limit(result);
    if (outputEncoding == C.ENCODING_AC3 || outputEncoding == C.ENCODING_E_AC3) {
      int accessUnitCount = ffmpegGetOutputPacketCount(nativeContext);
      if (accessUnitCount != 1) {
        // A SimpleDecoderOutputBuffer has one presentation timestamp. Do not
        // send concatenated compressed access units with an incorrect frame
        // count or timestamp to the Media3 passthrough sink.
        return new FfmpegDecoderException(
            "Expected exactly one encoded audio access unit, got " + accessUnitCount
                + ". Native packet scheduling returned an invalid buffer.");
      }
      long packetTimeUs = ffmpegGetOutputTimeUs(nativeContext);
      if (packetTimeUs != Long.MIN_VALUE && encodedTimeAnchorUs != C.TIME_UNSET) {
        // Guard the media-time conversion against signed overflow. A corrupt
        // native timestamp must not wrap into an unrelated playback position.
        if ((packetTimeUs > 0 && encodedTimeAnchorUs <= Long.MAX_VALUE - packetTimeUs)
            || (packetTimeUs < 0 && encodedTimeAnchorUs >= Long.MIN_VALUE - packetTimeUs)
            || packetTimeUs == 0) {
          outputBuffer.timeUs = encodedTimeAnchorUs + packetTimeUs;
        }
      }
    }
    return null;
  }

  // Called from native code
  @SuppressWarnings("unused")
  private ByteBuffer growOutputBuffer(SimpleDecoderOutputBuffer outputBuffer, int requiredSize) {
    // Use it for new buffer so that hopefully we won't need to reallocate again
    outputBufferSize = requiredSize;
    return outputBuffer.grow(requiredSize);
  }

  @Override
  public void release() {
    super.release();
    ffmpegRelease(nativeContext);
    nativeContext = 0;
  }

  /** Returns the channel count of output audio. */
  public int getChannelCount() {
    return channelCount;
  }

  /** Returns the sample rate of output audio. */
  public int getSampleRate() {
    return sampleRate;
  }

  /** Returns the encoding of output audio. */
  public @C.PcmEncoding int getEncoding() {
    return outputEncoding;
  }

  /** Sets the center-mix offset in dB relative to stream metadata or the default (-3 dB). */
  public void setUserCenterMixLevelDb(int userCenterMixLevelDb) {
    this.userCenterMixLevelDb = userCenterMixLevelDb;
  }

  /** Enables or disables downmix normalization. */
  public void setDownmixNormalizationEnabled(boolean downmixNormalizationEnabled) {
    this.downmixNormalizationEnabled = downmixNormalizationEnabled;
  }

  /**
   * Sets how much of the LFE channel is mixed into the other channels when the downmix layout has
   * no LFE (0 drops it, 1 mixes it at full level, split -3 dB per front channel by swresample).
   */
  public void setLfeMixLevel(float lfeMixLevel) {
    this.lfeMixLevel = lfeMixLevel;
  }

  /**
   * Returns FFmpeg-compatible codec-specific initialization data ("extra data"), or {@code null} if
   * not required.
   */
  @Nullable
  private static byte[] getExtraData(String mimeType, List<byte[]> initializationData) {
    switch (mimeType) {
      case MimeTypes.AUDIO_AAC:
      case MimeTypes.AUDIO_OPUS:
        return initializationData.get(0);
      case MimeTypes.AUDIO_ALAC:
        return getAlacExtraData(initializationData);
      case MimeTypes.AUDIO_VORBIS:
        return getVorbisExtraData(initializationData);
      case MimeTypes.AUDIO_FLAC:
        return getFlacExtraData(initializationData);
      default:
        // Other codecs do not require extra data.
        return null;
    }
  }

  private static byte[] getAlacExtraData(List<byte[]> initializationData) {
    // FFmpeg's ALAC decoder expects an ALAC atom, which contains the ALAC "magic cookie", as extra
    // data. initializationData[0] contains only the magic cookie, and so we need to package it into
    // an ALAC atom. See:
    // https://ffmpeg.org/doxygen/0.6/alac_8c.html
    // https://github.com/macosforge/alac/blob/master/ALACMagicCookieDescription.txt
    byte[] magicCookie = initializationData.get(0);
    int alacAtomLength = 12 + magicCookie.length;
    ByteBuffer alacAtom = ByteBuffer.allocate(alacAtomLength);
    alacAtom.putInt(alacAtomLength);
    alacAtom.putInt(0x616c6163); // type=alac
    alacAtom.putInt(0); // version=0, flags=0
    alacAtom.put(magicCookie, /* offset= */ 0, magicCookie.length);
    return alacAtom.array();
  }

  private static byte[] getVorbisExtraData(List<byte[]> initializationData) {
    byte[] header0 = initializationData.get(0);
    byte[] header1 = initializationData.get(1);
    byte[] extraData = new byte[header0.length + header1.length + 6];
    extraData[0] = (byte) (header0.length >> 8);
    extraData[1] = (byte) (header0.length & 0xFF);
    System.arraycopy(header0, 0, extraData, 2, header0.length);
    extraData[header0.length + 2] = 0;
    extraData[header0.length + 3] = 0;
    extraData[header0.length + 4] = (byte) (header1.length >> 8);
    extraData[header0.length + 5] = (byte) (header1.length & 0xFF);
    System.arraycopy(header1, 0, extraData, header0.length + 6, header1.length);
    return extraData;
  }

  @Nullable
  private static byte[] getFlacExtraData(List<byte[]> initializationData) {
    for (int i = 0; i < initializationData.size(); i++) {
      byte[] out = extractFlacStreamInfo(initializationData.get(i));
      if (out != null) {
        return out;
      }
    }
    return null;
  }

  @Nullable
  private static byte[] extractFlacStreamInfo(byte[] data) {
    int offset = 0;
    if (arrayStartsWith(data, flacStreamMarker)) {
      offset = flacStreamMarker.length;
    }

    if (data.length - offset == FLAC_STREAM_INFO_DATA_SIZE) {
      byte[] streamInfo = new byte[FLAC_STREAM_INFO_DATA_SIZE];
      System.arraycopy(data, offset, streamInfo, 0, FLAC_STREAM_INFO_DATA_SIZE);
      return streamInfo;
    }

    if (data.length >= offset + FLAC_METADATA_BLOCK_HEADER_SIZE) {
      int type = data[offset] & 0x7F;
      int length =
          ((data[offset + 1] & 0xFF) << 16)
              | ((data[offset + 2] & 0xFF) << 8)
              | (data[offset + 3] & 0xFF);

      if (type == FLAC_METADATA_TYPE_STREAM_INFO
          && length == FLAC_STREAM_INFO_DATA_SIZE
          && data.length >= offset + FLAC_METADATA_BLOCK_HEADER_SIZE + FLAC_STREAM_INFO_DATA_SIZE) {
        byte[] streamInfo = new byte[FLAC_STREAM_INFO_DATA_SIZE];
        System.arraycopy(
            data,
            offset + FLAC_METADATA_BLOCK_HEADER_SIZE,
            streamInfo,
            0,
            FLAC_STREAM_INFO_DATA_SIZE);
        return streamInfo;
      }
    }

    return null;
  }

  private static boolean arrayStartsWith(byte[] data, byte[] prefix) {
    if (data.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (data[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  private native long ffmpegInitialize(
      String codecName,
      @Nullable byte[] extraData,
      int rawSampleRate,
      int rawChannelCount,
      int outputChannelCount,
      @Nullable String requestedOutputLayoutName,
      boolean outputFloat,
      int transcodingMode);

  private native int ffmpegDecode(
      long context,
      ByteBuffer inputData,
      int inputSize,
      SimpleDecoderOutputBuffer decoderOutputBuffer,
      ByteBuffer outputData,
      int outputSize,
      int userCenterMixLevelDb,
      boolean downmixNormalizationEnabled,
      float lfeMixLevel);

  /**
   * Advances compressed end-of-stream processing by at most one output access unit.
   *
   * <p>This method must be called by a future drain-aware output scheduler
   * after the last compressed input, on the same thread that owns the native
   * decoder. It must never be called concurrently with decode() or reset.
   * A false result means no output was produced in this step, NOT necessarily
   * that EOS is complete. Query isEncodedEndOfStreamComplete() separately.
   */
  boolean drainNextEncodedEndOfStreamBuffer(SimpleDecoderOutputBuffer outputBuffer)
      throws FfmpegDecoderException {
    if (outputEncoding != C.ENCODING_AC3 && outputEncoding != C.ENCODING_E_AC3) {
      throw new FfmpegDecoderException("Encoded EOS drain requires AC-3 or E-AC-3 output.");
    }
    if (encodedEndOfStreamComplete) {
      return false;
    }
    // Always emit previously encoded access units before producing more.
    // This also keeps the bounded native packet queue from saturating.
    if (dequeuePendingEncodedPacket(outputBuffer)) {
      return true;
    }
    if (!isDecoderDrainComplete()) {
      int size = drainDelayedDecoderFrames(outputBuffer);
      if (size > 0) {
        return true;
      }
      // The decoder can return zero bytes without having reached EOF.
      return false;
    }
    if (!isResamplerDrainComplete()) {
      drainResamplerAfterDecoderEof();
      return dequeuePendingEncodedPacket(outputBuffer);
    }
    // A native encoder flush can return EAGAIN, or pause for queue
    // backpressure, before receiving confirmed encoder EOF. Retry only
    // after delivering queued packets to the renderer.
    if (!isEncoderFlushComplete()) {
      flushEncodedFramesAfterUpstreamDrain();
      return dequeuePendingEncodedPacket(outputBuffer);
    }
    if (dequeuePendingEncodedPacket(outputBuffer)) {
      return true;
    }
    encodedEndOfStreamComplete = true;
    return false;
  }

  int getPendingEncodedPacketsSnapshot() {
    return pendingEncodedPacketsSnapshot;
  }

  // Renderer-thread invalidation after a seek: the old native queue will be
  // destroyed by SimpleDecoder's next reset, so never schedule stale tokens.
  void invalidatePendingEncodedPackets() {
    pendingEncodedPacketsSnapshot = 0;
    syntheticDrainStepFinished = false;
    encodedEndOfStreamComplete = false;
  }

  boolean hasFinishedSyntheticDrainStep() {
    return syntheticDrainStepFinished;
  }

  void prepareSyntheticDrainStep() {
    syntheticDrainStepFinished = false;
  }

  boolean isEncodedEndOfStreamComplete() {
    return encodedEndOfStreamComplete;
  }

  boolean isCompressedOutput() {
    return outputEncoding == C.ENCODING_AC3 || outputEncoding == C.ENCODING_E_AC3;
  }

  // For a future drain-capable output scheduler. SimpleDecoder cannot emit
  // additional output buffers without another input, including at EOS.
  int getPendingEncodedPacketCount() {
    return ffmpegGetPendingEncodedPacketCount(nativeContext);
  }

  // Called only after the output scheduler owns an unused output buffer.
  // Returns true when one compressed access unit was copied into it.
  boolean dequeuePendingEncodedPacket(SimpleDecoderOutputBuffer outputBuffer)
      throws FfmpegDecoderException {
    if (outputEncoding != C.ENCODING_AC3 && outputEncoding != C.ENCODING_E_AC3) {
      return false;
    }
    if (getPendingEncodedPacketCount() == 0) {
      return false;
    }
    ByteBuffer data = outputBuffer.init(
        encodedTimeAnchorUs == C.TIME_UNSET ? 0 : encodedTimeAnchorUs, outputBufferSize);
    int size = ffmpegDequeueEncodedPacket(nativeContext, outputBuffer, data, outputBufferSize);
    if (size < 0) {
      throw new FfmpegDecoderException("Failed to dequeue encoded access unit.");
    }
    if (size == 0) {
      return false;
    }
    data = checkNotNull(outputBuffer.data);
    data.position(0);
    data.limit(size);
    // A short stream can produce its first encoded packet only during
    // EOS draining, bypassing the normal decode() output-format update.
    if (!hasOutputFormat) {
      channelCount = ffmpegGetChannelCount(nativeContext);
      sampleRate = ffmpegGetSampleRate(nativeContext);
      if (channelCount <= 0 || sampleRate <= 0) {
        throw new FfmpegDecoderException("Invalid compressed output format after drain.");
      }
      hasOutputFormat = true;
    }
    long packetTimeUs = ffmpegGetOutputTimeUs(nativeContext);
    if (packetTimeUs != Long.MIN_VALUE && encodedTimeAnchorUs != C.TIME_UNSET
        && ((packetTimeUs > 0 && encodedTimeAnchorUs <= Long.MAX_VALUE - packetTimeUs)
            || (packetTimeUs < 0 && encodedTimeAnchorUs >= Long.MIN_VALUE - packetTimeUs)
            || packetTimeUs == 0)) {
      outputBuffer.timeUs = encodedTimeAnchorUs + packetTimeUs;
    }
    return true;
  }

  // Start native decoder EOF handling. This can return one queued encoded
  // access unit; a drain-capable scheduler must continue until no output
  // remains, then drain SwrContext before finalizing the encoder.
  int drainDelayedDecoderFrames(SimpleDecoderOutputBuffer outputBuffer)
      throws FfmpegDecoderException {
    if (outputEncoding != C.ENCODING_AC3 && outputEncoding != C.ENCODING_E_AC3) {
      return 0;
    }
    ByteBuffer data = outputBuffer.init(
        encodedTimeAnchorUs == C.TIME_UNSET ? 0 : encodedTimeAnchorUs, outputBufferSize);
    int size = ffmpegDrainDecoder(
        nativeContext, outputBuffer, data, outputBufferSize,
        userCenterMixLevelDb, downmixNormalizationEnabled, lfeMixLevel);
    if (size == AUDIO_DECODER_ERROR_EAC3_LAYOUT_MISMATCH) {
      throw FfmpegDecoderException.eac3LayoutMismatch();
    }
    if (size < 0) {
      throw new FfmpegDecoderException("Failed to drain delayed FFmpeg decoder frames.");
    }
    if (size > 0) {
      data = checkNotNull(outputBuffer.data);
      data.position(0);
      data.limit(size);
      long packetTimeUs = ffmpegGetOutputTimeUs(nativeContext);
      if (packetTimeUs != Long.MIN_VALUE && encodedTimeAnchorUs != C.TIME_UNSET
          && ((packetTimeUs > 0 && encodedTimeAnchorUs <= Long.MAX_VALUE - packetTimeUs)
              || (packetTimeUs < 0 && encodedTimeAnchorUs >= Long.MIN_VALUE - packetTimeUs)
              || packetTimeUs == 0)) {
        outputBuffer.timeUs = encodedTimeAnchorUs + packetTimeUs;
      }
    }
    return size;
  }

  private native int ffmpegDrainDecoder(
      long context, SimpleDecoderOutputBuffer decoderOutputBuffer, ByteBuffer outputData,
      int outputSize, int userCenterMixLevelDb, boolean downmixNormalizationEnabled,
      float lfeMixLevel);

  // Zero bytes from drainDelayedDecoderFrames() does not prove EOF.
  boolean isDecoderDrainComplete() {
    return ffmpegIsDecoderDrainComplete(nativeContext);
  }

  private native boolean ffmpegIsDecoderDrainComplete(long context);

  // Drains delayed SwrContext samples after confirmed native decoder EOF.
  // Returns queued compressed access units, not a count of PCM samples.
  int drainResamplerAfterDecoderEof() throws FfmpegDecoderException {
    if (outputEncoding != C.ENCODING_AC3 && outputEncoding != C.ENCODING_E_AC3) {
      return 0;
    }
    int count = ffmpegDrainResampler(nativeContext);
    if (count < 0) {
      throw new FfmpegDecoderException("Failed to drain FFmpeg resampler.");
    }
    return count;
  }

  private native int ffmpegDrainResampler(long context);

  // A zero packet count is not evidence that SwrContext has finished.
  boolean isResamplerDrainComplete() {
    return ffmpegIsResamplerDrainComplete(nativeContext);
  }

  private native boolean ffmpegIsResamplerDrainComplete(long context);

  // Only call after the upstream FFmpeg decoder and resampler are drained.
  // Returns the number of queued encoded packets, or a negative error.
  int flushEncodedFramesAfterUpstreamDrain() throws FfmpegDecoderException {
    if (outputEncoding != C.ENCODING_AC3 && outputEncoding != C.ENCODING_E_AC3) {
      return 0;
    }
    int count = ffmpegFlushEncodedFrames(nativeContext);
    if (count < 0) {
      throw new FfmpegDecoderException("Failed to flush encoded audio frames.");
    }
    return count;
  }

  private native int ffmpegFlushEncodedFrames(long context);

  private native boolean ffmpegIsEncoderFlushComplete(long context);

  boolean isEncoderFlushComplete() {
    return ffmpegIsEncoderFlushComplete(nativeContext);
  }



  private native int ffmpegGetPendingEncodedPacketCount(long context);

  private native int ffmpegDequeueEncodedPacket(
      long context,
      SimpleDecoderOutputBuffer decoderOutputBuffer,
      ByteBuffer outputData,
      int outputSize);

  private native int ffmpegGetOutputPacketCount(long context);

  private native long ffmpegGetOutputTimeUs(long context);

  private native int ffmpegGetChannelCount(long context);

  private native int ffmpegGetSampleRate(long context);

  private native long ffmpegReset(long context, @Nullable byte[] extraData);

  private native void ffmpegRelease(long context);
}
