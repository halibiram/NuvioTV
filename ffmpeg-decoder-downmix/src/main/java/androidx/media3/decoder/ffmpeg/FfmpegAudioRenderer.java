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

import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DecoderAudioRenderer;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** Decodes and renders audio using FFmpeg. */
@UnstableApi
public final class FfmpegAudioRenderer extends DecoderAudioRenderer<Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, FfmpegDecoderException>> {

  private static final String TAG = "FfmpegAudioRenderer";

  /** The number of input and output buffers. */
  private static final int NUM_BUFFERS = 16;

  /** The default input buffer size. */
  private static final int DEFAULT_INPUT_BUFFER_SIZE = 960 * 6;

  private volatile int userCenterMixLevelDb;
  @Nullable private volatile String requestedOutputLayoutName;
  private volatile int requestedOutputChannelCount;
  private volatile boolean downmixNormalizationEnabled;
  @Nullable private volatile FfmpegAudioDecoder activeDecoder;
  private volatile boolean rendererEnabled;
  private volatile boolean downmixActive;
  private volatile boolean forceOpticalPassthrough;
  private volatile boolean preferEac3Transcoding;
  private volatile boolean forceEac3FiveOne;
  private volatile Set<String> deniedTranscodeMimes = Collections.emptySet();

  public FfmpegAudioRenderer() {
    this(/* eventHandler= */ null, /* eventListener= */ null);
  }

  /**
   * Creates a new instance.
   *
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param audioProcessors Optional {@link AudioProcessor}s that will process audio before output.
   */
  public FfmpegAudioRenderer(
      @Nullable Handler eventHandler,
      @Nullable AudioRendererEventListener eventListener,
      AudioProcessor... audioProcessors) {
    this(
        eventHandler,
        eventListener,
        new DefaultAudioSink.Builder().setAudioProcessors(audioProcessors).build());
  }

  /**
   * Creates a new instance.
   *
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param audioSink The sink to which audio will be output.
   */
  public FfmpegAudioRenderer(
      @Nullable Handler eventHandler,
      @Nullable AudioRendererEventListener eventListener,
      AudioSink audioSink) {
    super(eventHandler, eventListener, audioSink);
  }

  @Override
  public String getName() {
    return TAG;
  }

  @Override
  protected void onEnabled(boolean joining, boolean mayRenderStartOfStream)
      throws ExoPlaybackException {
    super.onEnabled(joining, mayRenderStartOfStream);
    rendererEnabled = true;
  }

  @Override
  protected void onDisabled() {
    try {
      super.onDisabled();
    } finally {
      rendererEnabled = false;
      downmixActive = false;
      activeDecoder = null;
    }
  }

  @Override
  protected @C.FormatSupport int supportsFormatInternal(Format format) {
    String mimeType = checkNotNull(format.sampleMimeType);
    if (!FfmpegLibrary.isAvailable() || !MimeTypes.isAudio(mimeType)) {
      return C.FORMAT_UNSUPPORTED_TYPE;
    }
    if (!FfmpegLibrary.supportsFormat(mimeType)) {
      return C.FORMAT_UNSUPPORTED_SUBTYPE;
    }
    if (forceOpticalPassthrough && MimeTypes.AUDIO_AC3.equals(mimeType)) {
      return C.FORMAT_UNSUPPORTED_SUBTYPE;
    }
    Format compressedOutput = selectCompressedOutput(format, mimeType);

    if (compressedOutput == null && (format.channelCount <= 0 || format.sampleRate <= 0)) {
      return format.cryptoType == C.CRYPTO_TYPE_NONE
          ? C.FORMAT_HANDLED
          : C.FORMAT_UNSUPPORTED_DRM;
    }
    if (compressedOutput == null) {
      int outputChannelCount = resolveOutputChannelCount(format.channelCount);
      boolean shouldRequestDownmix = shouldRequestDownmix(format.channelCount, outputChannelCount);
      @C.PcmEncoding int outputEncoding =
          shouldRequestDownmix ? C.ENCODING_PCM_FLOAT : C.ENCODING_PCM_16BIT;
      if (!sinkSupportsFormat(format, outputEncoding, outputChannelCount)) {
        return C.FORMAT_UNSUPPORTED_SUBTYPE;
      }
    } else if (forceOpticalPassthrough && !sinkSupportsFormat(compressedOutput)) {
      return C.FORMAT_UNSUPPORTED_SUBTYPE;
    }
    if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
      return C.FORMAT_UNSUPPORTED_DRM;
    }
    return C.FORMAT_HANDLED;
  }

  @Override
  public @AdaptiveSupport int supportsMixedMimeTypeAdaptation() {
    return ADAPTIVE_NOT_SEAMLESS;
  }

  @Override
  protected Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, FfmpegDecoderException> createDecoder(Format format, @Nullable CryptoConfig cryptoConfig)
      throws FfmpegDecoderException {
    TraceUtil.beginSection("createFfmpegAudioDecoder");
    String mimeType = checkNotNull(format.sampleMimeType);
    Format compressedOutput = selectCompressedOutput(format, mimeType);
    int initialInputBufferSize =
        format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;
    @C.PcmEncoding int outputEncoding;
    int nativeOutputChannelCount;
    @Nullable String outputLayoutName;
    if (compressedOutput != null) {
      boolean eac3 = MimeTypes.AUDIO_E_AC3.equals(compressedOutput.sampleMimeType);
      outputEncoding = eac3 ? C.ENCODING_E_AC3 : C.ENCODING_AC3;
      nativeOutputChannelCount = compressedOutput.channelCount;
      if (eac3) {
        boolean useFiveOneLayout = nativeOutputChannelCount == 6
            && (format.channelCount == 8 || forceEac3FiveOne);
        outputLayoutName = useFiveOneLayout ? "5.1(side)" : null;
      } else {
        outputLayoutName = "5.1";
      }
      downmixActive = false;
    } else {
      int outputChannelCount = resolveOutputChannelCount(format.channelCount);
      boolean shouldRequestDownmix = shouldRequestDownmix(format.channelCount, outputChannelCount);
      outputEncoding = shouldRequestDownmix ? C.ENCODING_PCM_FLOAT : C.ENCODING_PCM_16BIT;
      outputLayoutName = shouldRequestDownmix ? requestedOutputLayoutName : null;
      nativeOutputChannelCount = shouldRequestDownmix ? outputChannelCount : 0;
      downmixActive = shouldRequestDownmix;
    }
    FfmpegAudioDecoder decoder =
        new FfmpegAudioDecoder(
            format,
            NUM_BUFFERS,
            NUM_BUFFERS,
            initialInputBufferSize,
            nativeOutputChannelCount,
            outputLayoutName,
            outputEncoding);
    decoder.setUserCenterMixLevelDb(userCenterMixLevelDb);
    decoder.setDownmixNormalizationEnabled(downmixNormalizationEnabled);
    activeDecoder = decoder;
    TraceUtil.endSection();
    return decoder.isCompressedOutput() ? new EncodedEosDecoder(decoder) : decoder;
  }

  @Override
  protected Format getOutputFormat(Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, FfmpegDecoderException> decoder) {
    checkNotNull(decoder);
    FfmpegAudioDecoder underlying = decoder instanceof EncodedEosDecoder
        ? ((EncodedEosDecoder) decoder).getUnderlyingDecoder() : (FfmpegAudioDecoder) decoder;
    int encoding = underlying.getEncoding();
    if (encoding == C.ENCODING_AC3 || encoding == C.ENCODING_E_AC3) {
      return new Format.Builder()
          .setSampleMimeType(encoding == C.ENCODING_E_AC3 ? MimeTypes.AUDIO_E_AC3 : MimeTypes.AUDIO_AC3)
          .setChannelCount(underlying.getChannelCount())
          .setSampleRate(underlying.getSampleRate())
          .build();
    }
    return new Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setChannelCount(underlying.getChannelCount())
        .setSampleRate(underlying.getSampleRate())
        .setPcmEncoding(encoding)
        .build();
  }

  /** Sets the center-mix offset in dB relative to FFmpeg metadata or the default (-3 dB). */
  public void setCenterMixLevelDb(int centerMixLevelDb) {
    userCenterMixLevelDb = centerMixLevelDb;
    @Nullable FfmpegAudioDecoder decoder = activeDecoder;
    if (decoder != null) {
      decoder.setUserCenterMixLevelDb(centerMixLevelDb);
    }
  }

  /** Sets the desired FFmpeg output layout used for explicit downmix decisions. */
  public void setAudioOutputChannels(@Nullable String outputLayoutName, int outputChannelCount) {
    requestedOutputLayoutName = outputLayoutName;
    requestedOutputChannelCount = outputChannelCount;
  }

  /** Sets whether downmix normalization should be enabled. */
  public void setDownmixNormalizationEnabled(boolean downmixNormalizationEnabled) {
    this.downmixNormalizationEnabled = downmixNormalizationEnabled;
    @Nullable FfmpegAudioDecoder decoder = activeDecoder;
    if (decoder != null) {
      decoder.setDownmixNormalizationEnabled(downmixNormalizationEnabled);
    }
  }

  public void setForceOpticalPassthrough(boolean enabled) {
    this.forceOpticalPassthrough = enabled;
  }

  public void setDeniedTranscodeMimes(@Nullable Set<String> mimeTypes) {
    deniedTranscodeMimes =
        mimeTypes == null || mimeTypes.isEmpty()
            ? Collections.<String>emptySet()
            : Collections.unmodifiableSet(new HashSet<>(mimeTypes));
  }

  /** Returns whether this renderer is the active playback path for FFmpeg downmix + center mix. */
  public boolean isCenterMixActive() {
    return rendererEnabled && activeDecoder != null && downmixActive;
  }

  /** Returns whether this renderer is the active playback path for FFmpeg audio decoding. */
  public boolean isAudioPathActive() {
    return rendererEnabled && activeDecoder != null;
  }

  public boolean isTranscodingToAc3() {
    return rendererEnabled && activeDecoder != null && activeDecoder.getEncoding() == C.ENCODING_AC3;
  }

  /**
   * Returns whether the renderer's {@link AudioSink} supports the PCM format that will be output
   * from the decoder for the given input format and requested output encoding.
   */
  private boolean sinkSupportsFormat(
      Format inputFormat, @C.PcmEncoding int pcmEncoding, int channelCount) {
    if (channelCount <= 0 || inputFormat.sampleRate <= 0) {
      return false;
    }
    return sinkSupportsFormat(Util.getPcmFormat(pcmEncoding, channelCount, inputFormat.sampleRate));
  }

  @Nullable
  private Format selectCompressedOutput(Format format, String mimeType) {
    if (MimeTypes.AUDIO_AC3.equals(mimeType)) {
      return null;
    }
    boolean isDtsOrTrueHd = MimeTypes.AUDIO_DTS.equals(mimeType)
        || MimeTypes.AUDIO_DTS_HD.equals(mimeType)
        || MimeTypes.AUDIO_TRUEHD.equals(mimeType);
    boolean eligible = format.channelCount > 2 || format.channelCount <= 0 || isDtsOrTrueHd;
    if (!eligible) {
      return null;
    }
    boolean forceOptical = forceOpticalPassthrough;
    if (!forceOptical && !deniedTranscodeMimes.contains(mimeType)) {
      return null;
    }
    if (preferEac3Transcoding && !forceOptical) {
      if (MimeTypes.AUDIO_E_AC3.equals(mimeType) || MimeTypes.AUDIO_E_AC3_JOC.equals(mimeType)) {
        return null;
      }
      return selectEac3Output(format.channelCount, forceEac3FiveOne, this::sinkSupportsFormat);
    }
    Format ac3 = new Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_AC3)
        .setChannelCount(6)
        .setSampleRate(ac3SampleRate(format.sampleRate))
        .build();
    return forceOptical || sinkSupportsFormat(ac3) ? ac3 : null;
  }

  public static int ac3SampleRate(int inputSampleRate) {
    if (inputSampleRate == 32000 || inputSampleRate == 44100 || inputSampleRate == 48000) {
      return inputSampleRate;
    }
    return 48000;
  }

  public static int ac3TrailingSilenceSamples(int queuedSamples, int frameSize) {
    if (frameSize <= 0 || queuedSamples <= 0) {
      return 0;
    }
    int remainder = queuedSamples % frameSize;
    if (remainder == 0) {
      return 0;
    }
    return frameSize - remainder;
  }

  public static long ac3FrameDurationUs(int frameSamples, int sampleRate) {
    if (frameSamples <= 0 || sampleRate <= 0) {
      return 0L;
    }
    return frameSamples * 1_000_000L / sampleRate;
  }

  public void setPreferEac3Transcoding(boolean enabled) { preferEac3Transcoding = enabled; }
  public void setForceEac3FiveOne(boolean enabled) { forceEac3FiveOne = enabled; }

  public static boolean canEncodeEac3(int channels) { return channels == 6 || channels == 8; }

  public static int eac3OutputChannels(int inputChannels, boolean forceFiveOne) {
    return forceFiveOne && inputChannels == 8 ? 6 : inputChannels;
  }

  public boolean isTranscodingToEac3() {
    return rendererEnabled && activeDecoder != null && activeDecoder.getEncoding() == C.ENCODING_E_AC3;
  }

  @Nullable
  static Format selectEac3Output(
      int inputChannels, boolean forceFiveOne, Predicate<Format> supportsOutput) {
    if (!canEncodeEac3(inputChannels)) {
      return null;
    }
    Format preferred = new Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_E_AC3)
        .setChannelCount(eac3OutputChannels(inputChannels, forceFiveOne))
        .setSampleRate(48000)
        .build();
    if (supportsOutput.test(preferred)) {
      return preferred;
    }
    if (preferred.channelCount == 8) {
      Format fallback = preferred.buildUpon().setChannelCount(6).build();
      if (supportsOutput.test(fallback)) {
        return fallback;
      }
    }
    return null;
  }

  private int resolveOutputChannelCount(int inputChannelCount) {
    if (inputChannelCount <= 0) {
      return inputChannelCount;
    }
    int configuredChannelCount = requestedOutputChannelCount;
    if (configuredChannelCount <= 0 || configuredChannelCount >= inputChannelCount) {
      return inputChannelCount;
    }
    return configuredChannelCount;
  }

  private boolean shouldRequestDownmix(int inputChannelCount, int outputChannelCount) {
    return requestedOutputChannelCount > 0 && outputChannelCount < inputChannelCount;
  }
}
