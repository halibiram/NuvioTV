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
#include <android/log.h>
#include <jni.h>
#include <limits.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>

extern "C" {
#ifdef __cplusplus
#define __STDC_CONSTANT_MACROS
#ifdef _STDINT_H
#undef _STDINT_H
#endif
#include <stdint.h>
#endif
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/downmix_info.h>
#include <libavutil/error.h>
#include <libavutil/mem.h>
#include <libavutil/samplefmt.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
#include <libavutil/audio_fifo.h>
}

#define LOG_TAG "ffmpeg_jni"
#define LOGE(...) \
  ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
#define LOGD(...) \
  ((void)__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))

#define LIBRARY_FUNC(RETURN_TYPE, NAME, ...)                               \
  extern "C" {                                                             \
  JNIEXPORT RETURN_TYPE                                                    \
  Java_androidx_media3_decoder_ffmpeg_FfmpegLibrary_##NAME(JNIEnv* env,    \
                                                           jobject thiz,   \
                                                           ##__VA_ARGS__); \
  }                                                                        \
  JNIEXPORT RETURN_TYPE                                                    \
  Java_androidx_media3_decoder_ffmpeg_FfmpegLibrary_##NAME(                \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__)

#define AUDIO_DECODER_FUNC(RETURN_TYPE, NAME, ...)               \
  extern "C" {                                                   \
  JNIEXPORT RETURN_TYPE                                          \
  Java_androidx_media3_decoder_ffmpeg_FfmpegAudioDecoder_##NAME( \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__);                 \
  }                                                              \
  JNIEXPORT RETURN_TYPE                                          \
  Java_androidx_media3_decoder_ffmpeg_FfmpegAudioDecoder_##NAME( \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__)

#define ERROR_STRING_BUFFER_LENGTH 256

// Output formats corresponding to Android PCM encodings. Downmix-off uses
// 16-bit PCM to keep behavior aligned with the standard Media3 FFmpeg decoder.
static const AVSampleFormat OUTPUT_FORMAT_PCM_16BIT = AV_SAMPLE_FMT_S16;
static const AVSampleFormat OUTPUT_FORMAT_PCM_FLOAT = AV_SAMPLE_FMT_FLT;
// Default center level when no downmix metadata is present (-3 dB).
static const double DEFAULT_CENTER_MIX_LEVEL = M_SQRT1_2;
// Kodi AudioEngine limiter defaults. The limiter sits after rematrixing and
// catches decoded/remixed float peaks without permanently lowering the mix.
static const float LIMITER_DEFAULT_SAMPLE_RATE = 48000.0f;
static const float LIMITER_HOLD_SECONDS = 0.025f;
static const float LIMITER_RELEASE_SECONDS = 0.1f;

// LINT.IfChange
static const int AUDIO_DECODER_ERROR_INVALID_DATA = -1;
static const int AUDIO_DECODER_ERROR_OTHER = -2;
static const int AUDIO_DECODER_ERROR_EAC3_LAYOUT_MISMATCH = -1000;
// LINT.ThenChange(../java/androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder.java)

struct AudioLimiter {
  float attenuation;
  float sample_rate;
  int hold_counter;
  float increase;
};

// Each compressed access unit must retain its own packet and timestamp.
struct PendingEncodedPacket {
  AVPacket* packet;
  PendingEncodedPacket* next;
};

struct DecoderContext {
  AVCodecContext* codec_context;
  SwrContext* resample_context;
  AVSampleFormat output_sample_format;
  AVChannelLayout input_layout;
  AVChannelLayout output_layout;
  jint requested_output_channel_count;
  char* requested_output_layout_name;
  double center_mix_level;
  // LFE share mixed into the other channels when the downmix layout has no LFE.
  double lfe_mix_level;
  bool downmix_normalization_enabled;
  bool has_input_layout;
  bool has_output_layout;
  bool has_center_mix_level;
  bool downmix_active;
  AudioLimiter limiter;

  // AC3 Encoder fields
  // 0 = PCM, 1 = AC-3, 2 = E-AC-3.
  int transcoding_mode;
  AVCodecContext* encoder_context;
  AVAudioFifo* fifo;
  AVFrame* encoder_frame;
  AVPacket* encoder_packet;
  bool encoder_initialized;
  // Terminal encoder drain must be requested at most once per context.
  bool encoder_flush_started;
  bool encoder_flush_completed;
  // Final partial FIFO frame may have been accepted before EOF submission.
  bool encoder_final_frame_submitted;
  bool decoder_drain_started;
  bool decoder_drain_completed;
  bool resampler_drain_completed;
  // Presentation timestamp of the next encoded frame, in output samples.
  int64_t next_encoder_pts;
  // First packet PTS emitted by the most recent decodePacket call.
  int64_t last_output_pts;
  // Number of complete compressed access units emitted by the last decode.
  int last_output_packet_count;
  PendingEncodedPacket* pending_packet_head;
  PendingEncodedPacket* pending_packet_tail;
  int pending_packet_count;
};

static jmethodID growOutputBufferMethod;

/**
 * Returns the AVCodec with the specified name, or NULL if it is not available.
 */
const AVCodec* getCodecByName(JNIEnv* env, jstring codecName);

/**
 * Allocates and opens a new decoder context for the specified codec.
 */
DecoderContext* createContext(JNIEnv* env, const AVCodec* codec,
                              jbyteArray extraData,
                              jint rawSampleRate, jint rawChannelCount,
                              jint outputChannelCount,
                              jstring requestedOutputLayoutName,
                              jboolean outputFloat,
                              jint transcodingMode);

struct GrowOutputBufferCallback {
  uint8_t* operator()(int requiredSize) const;

  JNIEnv* env;
  jobject thiz;
  jobject decoderOutputBuffer;
};

// Pop exactly one compressed access unit. A failed allocation leaves the
// packet in the queue so it can be retried or freed during decoder reset.
int dequeueEncodedPacket(DecoderContext* context, uint8_t* outputBuffer,
                         int outputSize, GrowOutputBufferCallback growBuffer) {
  PendingEncodedPacket* node = context->pending_packet_head;
  if (!node) {
    context->last_output_pts = AV_NOPTS_VALUE;
    context->last_output_packet_count = 0;
    return 0;
  }
  const int packetSize = node->packet->size;
  if (packetSize <= 0) {
    LOGE("Invalid queued compressed packet size.");
    return AUDIO_DECODER_ERROR_OTHER;
  }
  if (packetSize > outputSize) {
    outputBuffer = growBuffer(packetSize);
    if (!outputBuffer) {
      LOGE("Failed to grow encoded access-unit output buffer.");
      return AUDIO_DECODER_ERROR_OTHER;
    }
  }
  memcpy(outputBuffer, node->packet->data, packetSize);
  context->last_output_pts = node->packet->pts != AV_NOPTS_VALUE
      ? node->packet->pts : node->packet->dts;
  context->last_output_packet_count = 1;
  context->pending_packet_head = node->next;
  if (!context->pending_packet_head) {
    context->pending_packet_tail = nullptr;
  }
  context->pending_packet_count--;
  av_packet_free(&node->packet);
  free(node);
  return packetSize;
}

/**
 * Decodes an input packet and emits at most one compressed access unit.
 * Returns bytes written or a negative AUDIO_DECODER_ERROR constant.
 */
int decodePacket(DecoderContext* decoderContext, AVPacket* packet,
                 uint8_t* outputBuffer, int outputSize,
                 jint userCenterMixLevelDb,
                 jboolean downmixNormalizationEnabled,
                 jfloat lfeMixLevel,
                 GrowOutputBufferCallback growBuffer);

/**
 * Configures or recreates the resampler for the current frame.
 */
int configureResampler(DecoderContext* decoderContext, AVFrame* frame,
                       jint userCenterMixLevelDb,
                       jboolean downmixNormalizationEnabled,
                       jfloat lfeMixLevel);

/**
 * Transforms ffmpeg AVERROR into a negative AUDIO_DECODER_ERROR constant value.
 */
int transformError(int errorNumber);

/**
 * Outputs a log message describing the avcodec error number.
 */
void logError(const char* functionName, int errorNumber);

/**
 * Releases the specified decoder context.
 */
void releaseContext(DecoderContext* decoderContext);

void clearResampler(DecoderContext* decoderContext);

void resetLimiter(AudioLimiter* limiter);

float getLimiterGain(AudioLimiter* limiter, float highestSample);

void limitInterleavedFloat(AudioLimiter* limiter, float* samples,
                           int frameCount, int channelCount,
                           int sampleRate);

void limitPlanarFloat(AudioLimiter* limiter, uint8_t** samples,
                      int frameCount, int channelCount, int sampleRate);

bool copyChannelLayout(const AVChannelLayout* source, AVChannelLayout* destination);

bool channelLayoutsEqual(const AVChannelLayout* left, const AVChannelLayout* right);

bool getInputChannelLayout(AVCodecContext* codecContext, AVFrame* frame,
                           AVChannelLayout* inputLayout);

bool getOutputChannelLayout(DecoderContext* decoderContext,
                            const AVChannelLayout* inputLayout,
                            AVChannelLayout* outputLayout);

bool applyRequestedOutputLayout(DecoderContext* decoderContext,
                                AVChannelLayout* outputLayout);

double getAdjustedCenterMixLevel(AVFrame* frame, jint userCenterMixLevelDb,
                                 bool isDownmixActive);

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  JNIEnv* env;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    LOGE("JNI_OnLoad: GetEnv failed");
    return -1;
  }
  jclass clazz =
      env->FindClass("androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder");
  if (!clazz) {
    LOGE("JNI_OnLoad: FindClass failed");
    return -1;
  }
  growOutputBufferMethod =
      env->GetMethodID(clazz, "growOutputBuffer",
                       "(Landroidx/media3/decoder/"
                       "SimpleDecoderOutputBuffer;I)Ljava/nio/ByteBuffer;");
  if (!growOutputBufferMethod) {
    LOGE("JNI_OnLoad: GetMethodID failed");
    return -1;
  }
  return JNI_VERSION_1_6;
}

LIBRARY_FUNC(jstring, ffmpegGetVersion) {
  return env->NewStringUTF(LIBAVCODEC_IDENT);
}

LIBRARY_FUNC(jint, ffmpegGetInputBufferPaddingSize) {
  return (jint)AV_INPUT_BUFFER_PADDING_SIZE;
}

LIBRARY_FUNC(jboolean, ffmpegHasDecoder, jstring codecName) {
  return getCodecByName(env, codecName) != NULL;
}

AUDIO_DECODER_FUNC(jlong, ffmpegInitialize, jstring codecName,
                   jbyteArray extraData,
                   jint rawSampleRate, jint rawChannelCount,
                   jint outputChannelCount,
                   jstring requestedOutputLayoutName,
                   jboolean outputFloat,
                   jint transcodingMode) {
  const AVCodec* codec = getCodecByName(env, codecName);
  if (!codec) {
    LOGE("Codec not found.");
    return 0L;
  }
  return (jlong)createContext(env, codec, extraData, rawSampleRate,
                              rawChannelCount, outputChannelCount,
                              requestedOutputLayoutName, outputFloat,
                              transcodingMode);
}

AUDIO_DECODER_FUNC(jint, ffmpegDecode, jlong context, jobject inputData,
                   jint inputSize, jobject decoderOutputBuffer,
                   jobject outputData, jint outputSize,
                   jint userCenterMixLevelDb,
                   jboolean downmixNormalizationEnabled,
                   jfloat lfeMixLevel) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  if (!inputData || !decoderOutputBuffer || !outputData) {
    LOGE("Input and output buffers must be non-NULL.");
    return -1;
  }
  if (inputSize < 0) {
    LOGE("Invalid input buffer size: %d.", inputSize);
    return -1;
  }
  if (outputSize < 0) {
    LOGE("Invalid output buffer length: %d", outputSize);
    return -1;
  }
  uint8_t* inputBuffer = (uint8_t*)env->GetDirectBufferAddress(inputData);
  uint8_t* outputBuffer = (uint8_t*)env->GetDirectBufferAddress(outputData);
  if (!inputBuffer || !outputBuffer) {
    LOGE("Audio decoder requires valid direct input and output ByteBuffers.");
    return AUDIO_DECODER_ERROR_OTHER;
  }
  if (inputSize > env->GetDirectBufferCapacity(inputData) ||
      outputSize > env->GetDirectBufferCapacity(outputData)) {
    LOGE("Audio buffer size exceeds direct ByteBuffer capacity.");
    return AUDIO_DECODER_ERROR_OTHER;
  }
  DecoderContext* decoderContext = (DecoderContext*)context;
  if (decoderContext->decoder_drain_started ||
      decoderContext->encoder_flush_started) {
    LOGE("Cannot decode new input after decoder/encoder EOS without reset.");
    return AUDIO_DECODER_ERROR_OTHER;
  }
  decoderContext->last_output_pts = AV_NOPTS_VALUE;
  decoderContext->last_output_packet_count = 0;
  AVPacket* packet = av_packet_alloc();
  if (!packet) {
    LOGE("Failed to allocate packet.");
    return -1;
  }
  packet->data = inputBuffer;
  packet->size = inputSize;
  const int ret =
      decodePacket((DecoderContext*)context, packet, outputBuffer, outputSize,
                   userCenterMixLevelDb,
                   downmixNormalizationEnabled,
                   lfeMixLevel,
                   GrowOutputBufferCallback{env, thiz, decoderOutputBuffer});
  av_packet_free(&packet);
  return ret;
}

uint8_t* GrowOutputBufferCallback::operator()(int requiredSize) const {
  jobject newOutputData = env->CallObjectMethod(
      thiz, growOutputBufferMethod, decoderOutputBuffer, requiredSize);
  if (env->ExceptionCheck()) {
    LOGE("growOutputBuffer() failed");
    env->ExceptionDescribe();
    return nullptr;
  }
  if (!newOutputData) {
    LOGE("growOutputBuffer() returned a null buffer");
    return nullptr;
  }
  uint8_t* address = static_cast<uint8_t*>(env->GetDirectBufferAddress(newOutputData));
  if (!address || env->GetDirectBufferCapacity(newOutputData) < requiredSize) {
    LOGE("growOutputBuffer() did not provide a sufficiently large direct buffer");
    address = nullptr;
  }
  // CallObjectMethod creates a local reference on every growth. Release it
  // before the decoder continues processing more input packets.
  env->DeleteLocalRef(newOutputData);
  return address;
}

// Returns the first compressed packet timestamp relative to this encoder
// session, in microseconds. Java adds the media timeline anchor.
// Dequeue an already-encoded access unit without consuming another input
// packet. The Java output scheduler must call this before signaling EOS.
AUDIO_DECODER_FUNC(jint, ffmpegDequeueEncodedPacket, jlong context,
                   jobject decoderOutputBuffer, jobject outputData,
                   jint outputSize) {
  if (!context || !decoderOutputBuffer || !outputData || outputSize < 0) {
    return AUDIO_DECODER_ERROR_OTHER;
  }
  DecoderContext* decoderContext = (DecoderContext*)context;
  if (decoderContext->transcoding_mode == 0) {
    return AUDIO_DECODER_ERROR_OTHER;
  }
  uint8_t* outputBuffer = (uint8_t*)env->GetDirectBufferAddress(outputData);
  if (!outputBuffer ||
      outputSize > env->GetDirectBufferCapacity(outputData)) {
    return AUDIO_DECODER_ERROR_OTHER;
  }
  decoderContext->last_output_pts = AV_NOPTS_VALUE;
  decoderContext->last_output_packet_count = 0;
  return dequeueEncodedPacket(
      decoderContext, outputBuffer, outputSize,
      GrowOutputBufferCallback{env, thiz, decoderOutputBuffer});
}

// Decode delayed frames after compressed input EOS. The existing decodePacket
// pipeline resamples and encodes frames returned by avcodec_receive_frame.
// It does not flush delayed SwrContext samples; that is a separate stage.
AUDIO_DECODER_FUNC(jint, ffmpegDrainDecoder, jlong context,
                   jobject decoderOutputBuffer, jobject outputData,
                   jint outputSize, jint userCenterMixLevelDb,
                   jboolean downmixNormalizationEnabled, jfloat lfeMixLevel) {
  if (!context || !decoderOutputBuffer || !outputData || outputSize < 0) {
    return AUDIO_DECODER_ERROR_OTHER;
  }
  DecoderContext* decoderContext = (DecoderContext*)context;
  if (decoderContext->transcoding_mode == 0 ||
      decoderContext->encoder_flush_started) {
    return AUDIO_DECODER_ERROR_OTHER;
  }
  uint8_t* outputBuffer = (uint8_t*)env->GetDirectBufferAddress(outputData);
  if (!outputBuffer || outputSize > env->GetDirectBufferCapacity(outputData)) {
    return AUDIO_DECODER_ERROR_OTHER;
  }
  decoderContext->last_output_pts = AV_NOPTS_VALUE;
  decoderContext->last_output_packet_count = 0;
  // Null input signals EOS to libavcodec; repeated calls are safe after EOF.
  decoderContext->decoder_drain_started = true;
  return decodePacket(decoderContext, nullptr, outputBuffer, outputSize,
                      userCenterMixLevelDb, downmixNormalizationEnabled,
                      lfeMixLevel,
                      GrowOutputBufferCallback{env, thiz, decoderOutputBuffer});
}

// A scheduler must distinguish an empty output from confirmed decoder EOF.
AUDIO_DECODER_FUNC(jboolean, ffmpegIsDecoderDrainComplete, jlong context) {
  if (!context) {
    return JNI_FALSE;
  }
  return ((DecoderContext*)context)->decoder_drain_completed
      ? JNI_TRUE : JNI_FALSE;
}

// Append delayed samples before discarding the resampler or flushing the encoder.
static int appendResamplerTailToFifo(DecoderContext* dc) {
  const int channels = dc->encoder_context->ch_layout.nb_channels;
  int capacity = swr_get_out_samples(dc->resample_context, 0);
  if (capacity < 0 || channels <= 0) {
    LOGE("Invalid delayed resampler dimensions: samples=%d channels=%d", capacity, channels);
    return AUDIO_DECODER_ERROR_OTHER;
  }
  // Bound each allocation and let the caller repeat until the resampler is empty.
  if (capacity == 0) {
    capacity = 1;
  }
  const int kMaxDrainSamplesPerCall = 8192;
  if (capacity > kMaxDrainSamplesPerCall) {
    capacity = kMaxDrainSamplesPerCall;
  }
  if (channels > INT_MAX / (capacity * static_cast<int>(sizeof(float)))) {
    LOGE("Resampler drain buffer dimensions overflow.");
    return AUDIO_DECODER_ERROR_OTHER;
  }
  uint8_t** samples = nullptr;
  int linesize = 0;
  int ret = av_samples_alloc_array_and_samples(
      &samples, &linesize, channels, capacity, AV_SAMPLE_FMT_FLTP, 0);
  if (ret < 0) {
    logError("av_samples_alloc_array_and_samples (drain)", ret);
    return AUDIO_DECODER_ERROR_OTHER;
  }
  int produced = swr_convert(dc->resample_context, samples, capacity, nullptr, 0);
  if (produced > 0 && dc->downmix_active) {
    limitPlanarFloat(&dc->limiter, samples, produced, channels,
                     dc->encoder_context->sample_rate);
  }
  int written = produced > 0
      ? av_audio_fifo_write(dc->fifo, (void**)samples, produced) : produced;
  av_freep(&samples[0]);
  av_freep(&samples);
  if (produced < 0 || written != produced) {
    LOGE("Failed to drain resampler samples into encoder FIFO.");
    return AUDIO_DECODER_ERROR_OTHER;
  }
  return produced;
}

// Consume SwrContext delayed samples into the encoder FIFO. A final encoder
// flush may run only after this stage has completed. Full frames are encoded
// through the same frame/packet queue contract as ordinary decoding.
AUDIO_DECODER_FUNC(jint, ffmpegDrainResampler, jlong context) {
  if (!context) {
    return AUDIO_DECODER_ERROR_OTHER;
  }
  DecoderContext* dc = (DecoderContext*)context;
  if (dc->transcoding_mode == 0 || !dc->decoder_drain_completed ||
      dc->encoder_flush_started || !dc->resample_context ||
      !dc->encoder_context || !dc->fifo || !dc->encoder_frame ||
      !dc->encoder_packet) {
    return AUDIO_DECODER_ERROR_OTHER;
  }
  if (dc->resampler_drain_completed) {
    return dc->pending_packet_count;
  }
  for (;;) {
    // One 8,192-sample batch plus a partial FIFO frame can produce up to
    // seven 1,536-sample AC-3/E-AC-3 access units. Leave room in the bounded
    // queue so the output scheduler can dequeue packets and resume draining
    // rather than losing a packet to the hard 256-packet limit.
    if (dc->pending_packet_count >= 248) {
      return dc->pending_packet_count;
    }
    const int produced = appendResamplerTailToFifo(dc);
    if (produced < 0) {
      return produced;
    }
    // A zero-sample resampler return does not imply the encoder FIFO is
    // empty. Full frames retained after encoder EAGAIN must be retried.
    const int frameSize = dc->encoder_context->frame_size;
    while (av_audio_fifo_size(dc->fifo) >= frameSize) {
      int ret = av_frame_make_writable(dc->encoder_frame);
      if (ret < 0 ||
          av_audio_fifo_peek(dc->fifo, (void**)dc->encoder_frame->data,
                             frameSize) != frameSize) {
        return AUDIO_DECODER_ERROR_OTHER;
      }
      dc->encoder_frame->pts = dc->next_encoder_pts;
      ret = avcodec_send_frame(dc->encoder_context, dc->encoder_frame);
      const bool encoderNeedsDrain = ret == AVERROR(EAGAIN);
      if (ret < 0 && !encoderNeedsDrain) {
        logError("avcodec_send_frame (resampler drain)", ret);
        return AUDIO_DECODER_ERROR_OTHER;
      }
      if (!encoderNeedsDrain) {
        av_audio_fifo_drain(dc->fifo, frameSize);
        dc->next_encoder_pts += frameSize;
      }
      // EAGAIN means the encoder has output waiting. Collect it before
      // returning, but keep the unaccepted FIFO frame for the next call.
      for (;;) {
        ret = avcodec_receive_packet(dc->encoder_context, dc->encoder_packet);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
          break;
        }
        if (ret < 0) {
          logError("avcodec_receive_packet (resampler drain)", ret);
          return AUDIO_DECODER_ERROR_OTHER;
        }
        if (dc->encoder_packet->size <= 0 || dc->pending_packet_count >= 256) {
          av_packet_unref(dc->encoder_packet);
          return AUDIO_DECODER_ERROR_OTHER;
        }
        PendingEncodedPacket* node =
            static_cast<PendingEncodedPacket*>(calloc(1, sizeof(PendingEncodedPacket)));
        if (!node) {
          av_packet_unref(dc->encoder_packet);
          return AUDIO_DECODER_ERROR_OTHER;
        }
        node->packet = av_packet_clone(dc->encoder_packet);
        av_packet_unref(dc->encoder_packet);
        if (!node->packet) {
          free(node);
          return AUDIO_DECODER_ERROR_OTHER;
        }
        if (dc->pending_packet_tail) {
          dc->pending_packet_tail->next = node;
        } else {
          dc->pending_packet_head = node;
        }
        dc->pending_packet_tail = node;
        dc->pending_packet_count++;
      }
      if (encoderNeedsDrain) {
        return dc->pending_packet_count;
      }
    }
    if (produced == 0) {
      dc->resampler_drain_completed = true;
      return dc->pending_packet_count;
    }
  }
}

// Do not infer resampler completion from the number of queued packets:
// resampling may finish without producing a complete compressed frame.
AUDIO_DECODER_FUNC(jboolean, ffmpegIsResamplerDrainComplete, jlong context) {
  if (!context) {
    return JNI_FALSE;
  }
  return ((DecoderContext*)context)->resampler_drain_completed
      ? JNI_TRUE : JNI_FALSE;
}

// Flush the encoder after upstream decoder and resampler have been drained.
// This is deliberately separate from Media3 EOS handling: calling it before
// upstream drain would truncate buffered decoded/resampled audio.
AUDIO_DECODER_FUNC(jint, ffmpegFlushEncodedFrames, jlong context) {
  if (!context) {
    return AUDIO_DECODER_ERROR_OTHER;
  }
  DecoderContext* decoderContext = (DecoderContext*)context;
  if (decoderContext->transcoding_mode == 0 ||
      !decoderContext->encoder_context || !decoderContext->fifo ||
      !decoderContext->encoder_frame || !decoderContext->encoder_packet) {
    return AUDIO_DECODER_ERROR_OTHER;
  }
  if (!decoderContext->decoder_drain_completed) {
    LOGE("Cannot flush encoder before decoder EOF has been confirmed.");
    return AUDIO_DECODER_ERROR_OTHER;
  }
  if (!decoderContext->resampler_drain_completed) {
    LOGE("Cannot flush encoder before resampler drain completes.");
    return AUDIO_DECODER_ERROR_OTHER;
  }
  // Only a full upstream drain may call this method. It is intentionally not
  // wired to the current SimpleDecoder EOS path.
  if (decoderContext->encoder_flush_completed) {
    return decoderContext->pending_packet_count;
  }
  // Leave space for packets produced while sending the terminal frame.
  if (decoderContext->pending_packet_count >= 248) {
    return decoderContext->pending_packet_count;
  }
  const int frameSize = decoderContext->encoder_context->frame_size;
  const int remaining = av_audio_fifo_size(decoderContext->fifo);
  if (frameSize <= 0 || remaining < 0 || remaining >= frameSize) {
    LOGE("Invalid encoder FIFO state during flush.");
    return AUDIO_DECODER_ERROR_OTHER;
  }
  if (remaining > 0 && !decoderContext->encoder_final_frame_submitted) {
    int ret = av_frame_make_writable(decoderContext->encoder_frame);
    if (ret < 0) {
      logError("av_frame_make_writable (flush)", ret);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    // Fixed-frame-size AC-3 encoders require a complete final frame.
    // Padding is silence, and must not expose stale samples from prior frames.
    int silenceResult = av_samples_set_silence(
        decoderContext->encoder_frame->data, 0, frameSize,
        decoderContext->encoder_context->ch_layout.nb_channels,
        decoderContext->encoder_context->sample_fmt);
    if (silenceResult < 0) {
      logError("av_samples_set_silence (flush)", silenceResult);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    // Peek first: avcodec_send_frame can return EAGAIN. Do not consume
    // the final samples until the encoder has accepted the frame.
    int peeked = av_audio_fifo_peek(
        decoderContext->fifo, (void**)decoderContext->encoder_frame->data, remaining);
    if (peeked != remaining) {
      LOGE("Unable to peek remaining encoder FIFO samples.");
      return AUDIO_DECODER_ERROR_OTHER;
    }
    decoderContext->encoder_frame->pts = decoderContext->next_encoder_pts;
    ret = avcodec_send_frame(decoderContext->encoder_context, decoderContext->encoder_frame);
    if (ret == AVERROR(EAGAIN)) {
      // Leave the FIFO intact; drain pending encoder packets below and
      // retry the same final frame on the next call.
      decoderContext->encoder_final_frame_submitted = false;
    } else if (ret < 0) {
      logError("avcodec_send_frame (flush)", ret);
      return AUDIO_DECODER_ERROR_OTHER;
    } else {
      av_audio_fifo_drain(decoderContext->fifo, remaining);
      decoderContext->next_encoder_pts += frameSize;
      decoderContext->encoder_final_frame_submitted = true;
    }
  }
  int ret = 0;
  if (!decoderContext->encoder_flush_started &&
      (remaining == 0 || decoderContext->encoder_final_frame_submitted)) {
    ret = avcodec_send_frame(decoderContext->encoder_context, nullptr);
    if (ret >= 0 || ret == AVERROR_EOF) {
      decoderContext->encoder_flush_started = true;
    } else if (ret != AVERROR(EAGAIN)) {
      logError("avcodec_send_frame (EOF)", ret);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    // EAGAIN requires receiving packets before retrying the terminal frame.
  }
  while (true) {
    // Leave encoder packets inside libavcodec until the Media3 scheduler
    // consumes enough queued access units. Never receive a packet that
    // cannot be enqueued without loss.
    if (decoderContext->pending_packet_count >= 248) {
      return decoderContext->pending_packet_count;
    }
    ret = avcodec_receive_packet(decoderContext->encoder_context,
                                 decoderContext->encoder_packet);
    if (ret == AVERROR_EOF) {
      decoderContext->encoder_flush_completed = true;
      break;
    }
    if (ret == AVERROR(EAGAIN)) {
      break;
    }
    if (ret < 0) {
      logError("avcodec_receive_packet (flush)", ret);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    if (decoderContext->encoder_packet->size <= 0 ||
        decoderContext->pending_packet_count >= 256) {
      av_packet_unref(decoderContext->encoder_packet);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    PendingEncodedPacket* node =
        static_cast<PendingEncodedPacket*>(calloc(1, sizeof(PendingEncodedPacket)));
    if (!node) {
      av_packet_unref(decoderContext->encoder_packet);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    node->packet = av_packet_clone(decoderContext->encoder_packet);
    av_packet_unref(decoderContext->encoder_packet);
    if (!node->packet) {
      free(node);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    if (decoderContext->pending_packet_tail) {
      decoderContext->pending_packet_tail->next = node;
    } else {
      decoderContext->pending_packet_head = node;
    }
    decoderContext->pending_packet_tail = node;
    decoderContext->pending_packet_count++;
  }
  return decoderContext->pending_packet_count;
}

// Encoder EOF is confirmed only after avcodec_receive_packet returns EOF.
// A successful send_frame(nullptr) alone is not sufficient.
AUDIO_DECODER_FUNC(jboolean, ffmpegIsEncoderFlushComplete, jlong context) {
  if (!context) {
    return JNI_FALSE;
  }
  return ((DecoderContext*)context)->encoder_flush_completed
      ? JNI_TRUE : JNI_FALSE;
}

AUDIO_DECODER_FUNC(jint, ffmpegGetPendingEncodedPacketCount, jlong context) {
  if (!context) {
    return 0;
  }
  return ((DecoderContext*)context)->pending_packet_count;
}

AUDIO_DECODER_FUNC(jint, ffmpegGetOutputPacketCount, jlong context) {
  if (!context) {
    return 0;
  }
  return ((DecoderContext*)context)->last_output_packet_count;
}

AUDIO_DECODER_FUNC(jlong, ffmpegGetOutputTimeUs, jlong context) {
  if (!context) {
    return (jlong)AV_NOPTS_VALUE;
  }
  DecoderContext* decoderContext = (DecoderContext*)context;
  if (!decoderContext->encoder_context ||
      decoderContext->last_output_pts == AV_NOPTS_VALUE) {
    return (jlong)AV_NOPTS_VALUE;
  }
  return (jlong)av_rescale_q(decoderContext->last_output_pts,
                            decoderContext->encoder_context->time_base,
                            AVRational{1, 1000000});
}

AUDIO_DECODER_FUNC(jint, ffmpegGetChannelCount, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  DecoderContext* decoderContext = (DecoderContext*)context;
  if (decoderContext->has_output_layout) {
    return decoderContext->output_layout.nb_channels;
  }
  if (decoderContext->requested_output_channel_count > 0) {
    return decoderContext->requested_output_channel_count;
  }
  return decoderContext->codec_context->ch_layout.nb_channels;
}

AUDIO_DECODER_FUNC(jint, ffmpegGetSampleRate, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  DecoderContext* decoderContext = (DecoderContext*)context;
  if (decoderContext->transcoding_mode != 0 && decoderContext->encoder_context) {
    return decoderContext->encoder_context->sample_rate;
  }
  return decoderContext->codec_context->sample_rate;
}

AUDIO_DECODER_FUNC(jlong, ffmpegReset, jlong jContext, jbyteArray extraData) {
  DecoderContext* decoderContext = (DecoderContext*)jContext;
  if (!decoderContext || !decoderContext->codec_context) {
    LOGE("Tried to reset without a context.");
    return 0L;
  }

  AVCodecContext* codecContext = decoderContext->codec_context;
  AVCodecID codecId = codecContext->codec_id;
  if (codecId == AV_CODEC_ID_TRUEHD) {
    jint outputChannelCount = decoderContext->requested_output_channel_count;
    jboolean outputFloat =
        decoderContext->output_sample_format == OUTPUT_FORMAT_PCM_FLOAT;
    jint transcodingMode = decoderContext->transcoding_mode;
    jstring requestedOutputLayoutName = NULL;
    if (decoderContext->requested_output_layout_name) {
      requestedOutputLayoutName =
          env->NewStringUTF(decoderContext->requested_output_layout_name);
    }
    releaseContext(decoderContext);
    const AVCodec* codec = avcodec_find_decoder(codecId);
    if (!codec) {
      LOGE("Unexpected error finding codec %d.", codecId);
      return 0L;
    }
    jlong context = (jlong)createContext(env, codec, extraData,
                                         /* rawSampleRate= */ -1,
                                         /* rawChannelCount= */ -1,
                                         outputChannelCount,
                                         requestedOutputLayoutName,
                                         outputFloat,
                                         transcodingMode);
    if (requestedOutputLayoutName) {
      env->DeleteLocalRef(requestedOutputLayoutName);
    }
    return context;
  }

  avcodec_flush_buffers(codecContext);
  clearResampler(decoderContext);
  resetLimiter(&decoderContext->limiter);
  return (jlong)decoderContext;
}

AUDIO_DECODER_FUNC(void, ffmpegRelease, jlong context) {
  if (context) {
    releaseContext((DecoderContext*)context);
  }
}

const AVCodec* getCodecByName(JNIEnv* env, jstring codecName) {
  if (!codecName) {
    return NULL;
  }
  const char* codecNameChars = env->GetStringUTFChars(codecName, NULL);
  if (!codecNameChars) {
    LOGE("Failed to access codec name from Java.");
    return NULL;
  }
  const AVCodec* codec = avcodec_find_decoder_by_name(codecNameChars);
  env->ReleaseStringUTFChars(codecName, codecNameChars);
  return codec;
}

DecoderContext* createContext(JNIEnv* env, const AVCodec* codec,
                              jbyteArray extraData,
                              jint rawSampleRate, jint rawChannelCount,
                              jint outputChannelCount,
                              jstring requestedOutputLayoutName,
                              jboolean outputFloat,
                              jint transcodingMode) {
  DecoderContext* decoderContext =
      static_cast<DecoderContext*>(calloc(1, sizeof(DecoderContext)));
  if (!decoderContext) {
    LOGE("Failed to allocate decoder context.");
    return NULL;
  }
  if (transcodingMode < 0 || transcodingMode > 2) {
    LOGE("Unsupported audio transcoding mode: %d", transcodingMode);
    free(decoderContext);
    return NULL;
  }
  decoderContext->transcoding_mode = transcodingMode;
  resetLimiter(&decoderContext->limiter);

  AVCodecContext* codecContext = avcodec_alloc_context3(codec);
  if (!codecContext) {
    LOGE("Failed to allocate codec context.");
    free(decoderContext);
    return NULL;
  }

  decoderContext->codec_context = codecContext;
  decoderContext->output_sample_format =
      outputFloat ? OUTPUT_FORMAT_PCM_FLOAT : OUTPUT_FORMAT_PCM_16BIT;
  decoderContext->requested_output_channel_count = outputChannelCount;
  if (requestedOutputLayoutName) {
    const char* outputLayoutNameChars =
        env->GetStringUTFChars(requestedOutputLayoutName, NULL);
    if (outputLayoutNameChars) {
      decoderContext->requested_output_layout_name = strdup(outputLayoutNameChars);
      env->ReleaseStringUTFChars(requestedOutputLayoutName, outputLayoutNameChars);
    }
  }
  if (extraData) {
    jsize size = env->GetArrayLength(extraData);
    codecContext->extradata_size = size;
    codecContext->extradata =
        (uint8_t*)av_malloc(size + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!codecContext->extradata) {
      LOGE("Failed to allocate extradata.");
      releaseContext(decoderContext);
      return NULL;
    }
    env->GetByteArrayRegion(extraData, 0, size, (jbyte*)codecContext->extradata);
    if (env->ExceptionCheck()) {
      LOGE("Failed to read codec initialization data from Java.");
      releaseContext(decoderContext);
      return NULL;
    }
    // FFmpeg bitstream readers may access the required padding after extradata.
    // The padding must be zero-filled, not left with uninitialized heap bytes.
    memset(codecContext->extradata + size, 0, AV_INPUT_BUFFER_PADDING_SIZE);
  }
  if (codecContext->codec_id == AV_CODEC_ID_PCM_MULAW ||
      codecContext->codec_id == AV_CODEC_ID_PCM_ALAW) {
    codecContext->sample_rate = rawSampleRate;
    av_channel_layout_default(&codecContext->ch_layout, rawChannelCount);
  }
  codecContext->err_recognition = AV_EF_IGNORE_ERR;
  int result = avcodec_open2(codecContext, codec, NULL);
  if (result < 0) {
    logError("avcodec_open2", result);
    releaseContext(decoderContext);
    return NULL;
  }
  return decoderContext;
}

int decodePacket(DecoderContext* decoderContext, AVPacket* packet,
                 uint8_t* outputBuffer, int outputSize,
                 jint userCenterMixLevelDb,
                 jboolean downmixNormalizationEnabled,
                 jfloat lfeMixLevel,
                 GrowOutputBufferCallback growBuffer) {
  AVCodecContext* codecContext = decoderContext->codec_context;
  int result = avcodec_send_packet(codecContext, packet);
  if (packet == nullptr && result == AVERROR(EAGAIN)) {
    // FFmpeg requires the caller to receive delayed frames before retrying
    // the null packet. Continue draining without treating backpressure as
    // a fatal decoder error; the next drain call will retry EOS.
    result = 0;
  } else if (packet == nullptr && result == AVERROR_EOF) {
    // EOS was already accepted on an earlier call.
    result = 0;
  }
  if (result) {
    logError("avcodec_send_packet", result);
    return transformError(result);
  }

  int outSize = 0;
  while (true) {
    AVFrame* frame = av_frame_alloc();
    if (!frame) {
      LOGE("Failed to allocate output frame.");
      return AUDIO_DECODER_ERROR_INVALID_DATA;
    }
    result = avcodec_receive_frame(codecContext, frame);
    if (result) {
      av_frame_free(&frame);
      if (result == AVERROR_EOF && packet == nullptr) {
        decoderContext->decoder_drain_completed = true;
        break;
      }
      if (result == AVERROR(EAGAIN)) {
        break;
      }
      logError("avcodec_receive_frame", result);
      return transformError(result);
    }

    result =
        configureResampler(decoderContext, frame, userCenterMixLevelDb,
                           downmixNormalizationEnabled, lfeMixLevel);
    if (result < 0) {
      av_frame_free(&frame);
      return result == AUDIO_DECODER_ERROR_EAC3_LAYOUT_MISMATCH
          ? result : transformError(result);
    }

    int sampleRate =
        frame->sample_rate > 0 ? frame->sample_rate : codecContext->sample_rate;
    if (decoderContext->transcoding_mode != 0) {
      int outSamples = swr_get_out_samples(decoderContext->resample_context, frame->nb_samples);
      int nb_channels = decoderContext->output_layout.nb_channels;
      if (outSamples < 0 || nb_channels <= 0) {
        LOGE("Invalid AC3/EAC3 resampler dimensions: samples=%d channels=%d",
             outSamples, nb_channels);
        av_frame_free(&frame);
        return AUDIO_DECODER_ERROR_OTHER;
      }
      // Even when no output is predicted, pass the input to swr_convert:
      // dropping this frame would silently discard decoded audio.
      // Reserve at least one output sample to allow the resampler to advance.
      if (outSamples == 0) {
        outSamples = 1;
      }
      if (outSamples > INT_MAX / static_cast<int>(sizeof(float))) {
        LOGE("AC3/EAC3 conversion buffer size overflow.");
        av_frame_free(&frame);
        return AUDIO_DECODER_ERROR_OTHER;
      }
      uint8_t** converted_data = (uint8_t**)calloc(nb_channels, sizeof(uint8_t*));
      if (!converted_data) {
        av_frame_free(&frame);
        return AUDIO_DECODER_ERROR_OTHER;
      }
      for (int i = 0; i < nb_channels; i++) {
        converted_data[i] = (uint8_t*)malloc((size_t)outSamples * sizeof(float));
        if (!converted_data[i]) {
          for (int j = 0; j < i; j++) {
            free(converted_data[j]);
          }
          free(converted_data);
          av_frame_free(&frame);
          LOGE("Failed to allocate AC3 conversion buffer");
          return AUDIO_DECODER_ERROR_OTHER;
        }
      }

      int convertedSamples =
          swr_convert(decoderContext->resample_context, converted_data, outSamples,
                      (const uint8_t**)frame->data, frame->nb_samples);
      av_frame_free(&frame);

      if (convertedSamples < 0) {
        logError("swr_convert", convertedSamples);
        for (int i = 0; i < nb_channels; i++) {
          free(converted_data[i]);
        }
        free(converted_data);
        return AUDIO_DECODER_ERROR_INVALID_DATA;
      }

      // swr_convert may consume input while buffering it internally, yet
      // return no samples for this call. Do not write an empty FIFO block.
      if (convertedSamples == 0) {
        for (int i = 0; i < nb_channels; i++) {
          free(converted_data[i]);
        }
        free(converted_data);
        continue;
      }

      if (decoderContext->downmix_active) {
        limitPlanarFloat(&decoderContext->limiter, converted_data,
                         convertedSamples, nb_channels,
                         decoderContext->encoder_context->sample_rate);
      }

      int writtenSamples =
          av_audio_fifo_write(decoderContext->fifo, (void**)converted_data, convertedSamples);

      for (int i = 0; i < nb_channels; i++) {
        free(converted_data[i]);
      }
      free(converted_data);

      if (writtenSamples != convertedSamples) {
        LOGE("AC3 FIFO write failed: expected %d samples, wrote %d",
             convertedSamples, writtenSamples);
        return AUDIO_DECODER_ERROR_OTHER;
      }

      while (av_audio_fifo_size(decoderContext->fifo) >= decoderContext->encoder_context->frame_size) {
        // The encoder may retain a reference to the previous frame.
        // Obtain writable storage before overwriting it with FIFO samples.
        int writableResult = av_frame_make_writable(decoderContext->encoder_frame);
        if (writableResult < 0) {
          logError("av_frame_make_writable (encoder)", writableResult);
          return AUDIO_DECODER_ERROR_OTHER;
        }
        int requestedSamples = decoderContext->encoder_context->frame_size;
        int peekedSamples = av_audio_fifo_peek(
            decoderContext->fifo, (void**)decoderContext->encoder_frame->data,
            requestedSamples);
        if (peekedSamples != requestedSamples) {
          LOGE("AC3 FIFO peek failed: expected %d samples, got %d",
               requestedSamples, peekedSamples);
          return AUDIO_DECODER_ERROR_OTHER;
        }

        // Encoder PTS uses the configured sample-based time base. Advance
        // only after the encoder accepts the frame.
        decoderContext->encoder_frame->pts = decoderContext->next_encoder_pts;
        int ret = avcodec_send_frame(decoderContext->encoder_context, decoderContext->encoder_frame);
        if (ret < 0) {
          // In particular, EAGAIN must not consume an unaccepted frame.
          // Report the backpressure rather than silently truncating audio.
          logError("avcodec_send_frame", ret);
          return AUDIO_DECODER_ERROR_OTHER;
        }
        av_audio_fifo_drain(decoderContext->fifo, requestedSamples);
        decoderContext->next_encoder_pts += requestedSamples;

        while (true) {
          ret = avcodec_receive_packet(decoderContext->encoder_context, decoderContext->encoder_packet);
          if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
            break;
          } else if (ret < 0) {
            logError("avcodec_receive_packet", ret);
            return AUDIO_DECODER_ERROR_OTHER;
          }

          // Keep access-unit boundaries intact across decoder calls. The
          // SimpleDecoder API permits only one output buffer per input.
          int packetSize = decoderContext->encoder_packet->size;
          if (packetSize <= 0 || decoderContext->pending_packet_count >= 256) {
            LOGE("Invalid encoded packet or pending queue limit reached.");
            av_packet_unref(decoderContext->encoder_packet);
            return AUDIO_DECODER_ERROR_OTHER;
          }
          PendingEncodedPacket* node =
              static_cast<PendingEncodedPacket*>(calloc(1, sizeof(PendingEncodedPacket)));
          if (!node) {
            av_packet_unref(decoderContext->encoder_packet);
            return AUDIO_DECODER_ERROR_OTHER;
          }
          node->packet = av_packet_clone(decoderContext->encoder_packet);
          av_packet_unref(decoderContext->encoder_packet);
          if (!node->packet) {
            free(node);
            return AUDIO_DECODER_ERROR_OTHER;
          }
          if (decoderContext->pending_packet_tail) {
            decoderContext->pending_packet_tail->next = node;
          } else {
            decoderContext->pending_packet_head = node;
          }
          decoderContext->pending_packet_tail = node;
          decoderContext->pending_packet_count++;
        }
      }
      // Keep the decoder input rate unchanged. The encoded output rate is
      // reported by ffmpegGetSampleRate() from the encoder context.
    } else {
      int outputChannelCount = decoderContext->output_layout.nb_channels;
      int outSampleSize =
          av_get_bytes_per_sample(decoderContext->output_sample_format);
      int outSamples =
          swr_get_out_samples(decoderContext->resample_context, frame->nb_samples);
      if (outSampleSize <= 0 || outputChannelCount <= 0 || outSamples < 0 ||
          outSamples > INT_MAX / outSampleSize / outputChannelCount ||
          outSampleSize * outputChannelCount * outSamples > INT_MAX - outSize) {
        LOGE("Invalid or overflowing PCM output dimensions.");
        av_frame_free(&frame);
        return AUDIO_DECODER_ERROR_OTHER;
      }
      int bufferOutSize = outSampleSize * outputChannelCount * outSamples;
      if (outSize + bufferOutSize > outputSize) {
        LOGD(
            "Output buffer size (%d) too small for output data (%d), "
            "reallocating buffer.",
            outputSize, outSize + bufferOutSize);
        outputSize = outSize + bufferOutSize;
        uint8_t* newBase = growBuffer(outputSize);
        if (!newBase) {
          LOGE("Failed to reallocate output buffer.");
          av_frame_free(&frame);
          return AUDIO_DECODER_ERROR_OTHER;
        }
        // growBuffer() returns the base address of the replacement buffer.
        // Preserve any frames already written during this decode call and
        // append the current frame after them.
        outputBuffer = newBase + outSize;
      }

      int convertedSamples =
          swr_convert(decoderContext->resample_context, &outputBuffer, outSamples,
                      (const uint8_t**)frame->data, frame->nb_samples);
      av_frame_free(&frame);
      if (convertedSamples < 0) {
        logError("swr_convert", convertedSamples);
        return AUDIO_DECODER_ERROR_INVALID_DATA;
      }
      if (decoderContext->downmix_active &&
          decoderContext->output_sample_format == AV_SAMPLE_FMT_FLT) {
        limitInterleavedFloat(
            &decoderContext->limiter,
            reinterpret_cast<float*>(outputBuffer), convertedSamples,
            outputChannelCount, sampleRate);
      }
      int writtenSize = outSampleSize * outputChannelCount * convertedSamples;
      outputBuffer += writtenSize;
      outSize += writtenSize;
      codecContext->sample_rate = sampleRate;
    }
  }
  if (decoderContext->transcoding_mode != 0) {
    return dequeueEncodedPacket(decoderContext, outputBuffer, outputSize, growBuffer);
  }
  return outSize;
}

int configureResampler(DecoderContext* decoderContext, AVFrame* frame,
                       jint userCenterMixLevelDb,
                       jboolean downmixNormalizationEnabled,
                       jfloat lfeMixLevel) {
  AVCodecContext* codecContext = decoderContext->codec_context;
  AVChannelLayout inputLayout = {};
  if (!getInputChannelLayout(codecContext, frame, &inputLayout)) {
    LOGE("Unable to resolve input channel layout.");
    return AUDIO_DECODER_ERROR_OTHER;
  }

  AVChannelLayout outputLayout = {};
  if (decoderContext->transcoding_mode != 0) {
    if (decoderContext->transcoding_mode == 2) {
      // An explicit output layout allows downmixing 7.1. Otherwise, preserve the
      // decoded layout and reject a mismatch with the renderer's negotiated count.
      const int requestedChannels = decoderContext->requested_output_channel_count;
      const bool downmixToFiveOne = inputLayout.nb_channels == 8 &&
          requestedChannels == 6 && decoderContext->requested_output_layout_name;
      const bool supportedInput = inputLayout.nb_channels == 6 || inputLayout.nb_channels == 8;
      if (!supportedInput || (!downmixToFiveOne && inputLayout.nb_channels != requestedChannels)) {
        LOGE("EAC3 channel preservation failed: input=%d requested=%d",
             inputLayout.nb_channels, decoderContext->requested_output_channel_count);
        av_channel_layout_uninit(&inputLayout);
        return supportedInput ? AUDIO_DECODER_ERROR_EAC3_LAYOUT_MISMATCH : AUDIO_DECODER_ERROR_OTHER;
      }
      // The FIFO and encoder retain the layout chosen for their first frame.
      const bool haveOutputLayout = decoderContext->encoder_initialized
          ? copyChannelLayout(&decoderContext->encoder_context->ch_layout, &outputLayout)
          : getOutputChannelLayout(decoderContext, &inputLayout, &outputLayout);
      if (!haveOutputLayout) {
        av_channel_layout_uninit(&inputLayout);
        return AUDIO_DECODER_ERROR_OTHER;
      }
    } else {
      av_channel_layout_default(&outputLayout, 6);
    }
    decoderContext->output_sample_format = AV_SAMPLE_FMT_FLTP;
  } else {
    if (!getOutputChannelLayout(decoderContext, &inputLayout, &outputLayout)) {
      av_channel_layout_uninit(&inputLayout);
      LOGE("Unable to resolve output channel layout.");
      return AUDIO_DECODER_ERROR_OTHER;
    }
  }

  int inputSampleRate =
      frame->sample_rate > 0 ? frame->sample_rate : codecContext->sample_rate;
  if (inputSampleRate <= 0) {
    LOGE("Invalid input sample rate: %d", inputSampleRate);
    av_channel_layout_uninit(&inputLayout);
    av_channel_layout_uninit(&outputLayout);
    return AUDIO_DECODER_ERROR_OTHER;
  }
  // Retain PR3107's supported AC-3 input rates; E-AC-3 uses 48 kHz.
  const int outputSampleRate = decoderContext->transcoding_mode == 1
      ? ((inputSampleRate == 32000 || inputSampleRate == 44100 || inputSampleRate == 48000)
          ? inputSampleRate : 48000)
      : decoderContext->transcoding_mode == 2 ? 48000 : inputSampleRate;
  AVSampleFormat inputSampleFormat = (AVSampleFormat)frame->format;
  bool isDownmixActive = outputLayout.nb_channels < inputLayout.nb_channels;
  bool applyNormalization = isDownmixActive && downmixNormalizationEnabled;
  double centerMixLevel =
      getAdjustedCenterMixLevel(frame, userCenterMixLevelDb, isDownmixActive);
  // swresample only uses this when the output layout has no LFE channel.
  double lfeLevel = isDownmixActive && lfeMixLevel > 0.0f ? lfeMixLevel : 0.0;

  bool needsReconfigure =
      !decoderContext->resample_context ||
      !decoderContext->has_input_layout ||
      !decoderContext->has_output_layout ||
      !channelLayoutsEqual(&decoderContext->input_layout, &inputLayout) ||
      !channelLayoutsEqual(&decoderContext->output_layout, &outputLayout) ||
      codecContext->sample_fmt != inputSampleFormat ||
      codecContext->sample_rate != inputSampleRate ||
      decoderContext->downmix_normalization_enabled != applyNormalization ||
      (!decoderContext->has_center_mix_level && isDownmixActive) ||
      (decoderContext->has_center_mix_level != isDownmixActive) ||
      (isDownmixActive &&
       fabs(decoderContext->center_mix_level - centerMixLevel) > 0.000001) ||
      fabs(decoderContext->lfe_mix_level - lfeLevel) > 0.000001;

  if (!needsReconfigure) {
    av_channel_layout_uninit(&inputLayout);
    av_channel_layout_uninit(&outputLayout);
    return 0;
  }

  if (decoderContext->transcoding_mode == 2 && decoderContext->encoder_initialized
      && decoderContext->resample_context) {
    int drained;
    do {
      drained = appendResamplerTailToFifo(decoderContext);
    } while (drained > 0);
    if (drained < 0) {
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return drained;
    }
  }
  clearResampler(decoderContext);

  SwrContext* resampleContext = NULL;
  int result = swr_alloc_set_opts2(&resampleContext, &outputLayout,
                                   decoderContext->output_sample_format,
                                   outputSampleRate, &inputLayout,
                                   inputSampleFormat, inputSampleRate, 0, NULL);
  if (result < 0) {
    logError("swr_alloc_set_opts2", result);
    av_channel_layout_uninit(&inputLayout);
    av_channel_layout_uninit(&outputLayout);
    return result;
  }

  if (isDownmixActive) {
    av_opt_set_double(resampleContext, "center_mix_level", centerMixLevel, 0);
    if (lfeLevel > 0.0) {
      av_opt_set_double(resampleContext, "lfe_mix_level", lfeLevel, 0);
    }
    if ((decoderContext->output_sample_format == AV_SAMPLE_FMT_FLT ||
         decoderContext->output_sample_format == AV_SAMPLE_FMT_FLTP) &&
        (inputSampleFormat == AV_SAMPLE_FMT_FLT ||
         inputSampleFormat == AV_SAMPLE_FMT_FLTP) &&
        applyNormalization) {
      av_opt_set_double(resampleContext, "rematrix_maxval", 1.0, 0);
    }
  }

  result = swr_init(resampleContext);
  if (result < 0) {
    logError("swr_init", result);
    swr_free(&resampleContext);
    av_channel_layout_uninit(&inputLayout);
    av_channel_layout_uninit(&outputLayout);
    return result;
  }

  decoderContext->resample_context = resampleContext;
  if (decoderContext->has_input_layout) {
    av_channel_layout_uninit(&decoderContext->input_layout);
  }
  if (decoderContext->has_output_layout) {
    av_channel_layout_uninit(&decoderContext->output_layout);
  }
  copyChannelLayout(&inputLayout, &decoderContext->input_layout);
  copyChannelLayout(&outputLayout, &decoderContext->output_layout);
  decoderContext->has_input_layout = true;
  decoderContext->has_output_layout = true;
  decoderContext->center_mix_level = centerMixLevel;
  decoderContext->lfe_mix_level = lfeLevel;
  decoderContext->downmix_normalization_enabled = applyNormalization;
  decoderContext->has_center_mix_level = isDownmixActive;
  decoderContext->downmix_active = isDownmixActive;
  decoderContext->limiter.sample_rate = static_cast<float>(outputSampleRate);

  if (decoderContext->transcoding_mode != 0 && !decoderContext->encoder_initialized) {
    const AVCodecID encoderId = decoderContext->transcoding_mode == 2
        ? AV_CODEC_ID_EAC3 : AV_CODEC_ID_AC3;
    const AVCodec* encoder = avcodec_find_encoder(encoderId);
    if (!encoder) {
      LOGE("Requested AC3/EAC3 encoder not found. Check FFmpeg build options.");
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    AVCodecContext* enc_ctx = avcodec_alloc_context3(encoder);
    if (!enc_ctx) {
      LOGE("Failed to allocate encoder context.");
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    enc_ctx->sample_fmt = AV_SAMPLE_FMT_FLTP;
    enc_ctx->sample_rate = outputSampleRate;
    enc_ctx->time_base = AVRational{1, outputSampleRate};
    int layoutResult = av_channel_layout_copy(&enc_ctx->ch_layout, &outputLayout);
    if (layoutResult < 0) {
      logError("av_channel_layout_copy (encoder)", layoutResult);
      avcodec_free_context(&enc_ctx);
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    enc_ctx->bit_rate = outputLayout.nb_channels == 8 ? 1024000 : 640000;

    int ret = avcodec_open2(enc_ctx, encoder, NULL);
    if (ret < 0) {
      logError("avcodec_open2 (encoder)", ret);
      avcodec_free_context(&enc_ctx);
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    decoderContext->encoder_context = enc_ctx;
    if (enc_ctx->frame_size <= 0) {
      LOGE("Invalid AC3/EAC3 encoder frame size: %d", enc_ctx->frame_size);
      avcodec_free_context(&decoderContext->encoder_context);
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }

    decoderContext->fifo = av_audio_fifo_alloc(enc_ctx->sample_fmt, enc_ctx->ch_layout.nb_channels, 1);
    if (!decoderContext->fifo) {
      LOGE("Failed to allocate AVAudioFifo.");
      avcodec_free_context(&decoderContext->encoder_context);
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }

    decoderContext->encoder_frame = av_frame_alloc();
    if (!decoderContext->encoder_frame) {
      LOGE("Failed to allocate encoder frame.");
      av_audio_fifo_free(decoderContext->fifo);
      decoderContext->fifo = nullptr;
      avcodec_free_context(&decoderContext->encoder_context);
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    decoderContext->encoder_frame->nb_samples = enc_ctx->frame_size;
    decoderContext->encoder_frame->format = enc_ctx->sample_fmt;
    ret = av_channel_layout_copy(&decoderContext->encoder_frame->ch_layout, &enc_ctx->ch_layout);
    if (ret < 0) {
      logError("av_channel_layout_copy (encoder frame)", ret);
      av_frame_free(&decoderContext->encoder_frame);
      av_audio_fifo_free(decoderContext->fifo);
      decoderContext->fifo = nullptr;
      avcodec_free_context(&decoderContext->encoder_context);
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    ret = av_frame_get_buffer(decoderContext->encoder_frame, 0);
    if (ret < 0) {
      logError("av_frame_get_buffer (encoder)", ret);
      av_frame_free(&decoderContext->encoder_frame);
      av_audio_fifo_free(decoderContext->fifo);
      decoderContext->fifo = nullptr;
      avcodec_free_context(&decoderContext->encoder_context);
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }

    decoderContext->encoder_packet = av_packet_alloc();
    if (!decoderContext->encoder_packet) {
      LOGE("Failed to allocate encoder packet.");
      av_frame_free(&decoderContext->encoder_frame);
      av_audio_fifo_free(decoderContext->fifo);
      decoderContext->fifo = nullptr;
      avcodec_free_context(&decoderContext->encoder_context);
      av_channel_layout_uninit(&inputLayout);
      av_channel_layout_uninit(&outputLayout);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    decoderContext->encoder_initialized = true;
  }

  av_channel_layout_uninit(&inputLayout);
  av_channel_layout_uninit(&outputLayout);
  return 0;
}

int transformError(int errorNumber) {
  return errorNumber == AVERROR_INVALIDDATA ? AUDIO_DECODER_ERROR_INVALID_DATA
                                            : AUDIO_DECODER_ERROR_OTHER;
}

void logError(const char* functionName, int errorNumber) {
  char* buffer = (char*)malloc(ERROR_STRING_BUFFER_LENGTH * sizeof(char));
  av_strerror(errorNumber, buffer, ERROR_STRING_BUFFER_LENGTH);
  LOGE("Error in %s: %s", functionName, buffer);
  free(buffer);
}

void clearResampler(DecoderContext* decoderContext) {
  if (!decoderContext) {
    return;
  }
  if (decoderContext->resample_context) {
    swr_free(&decoderContext->resample_context);
  }
  if (decoderContext->has_input_layout) {
    av_channel_layout_uninit(&decoderContext->input_layout);
    decoderContext->has_input_layout = false;
  }
  if (decoderContext->has_output_layout) {
    av_channel_layout_uninit(&decoderContext->output_layout);
    decoderContext->has_output_layout = false;
  }
  decoderContext->downmix_normalization_enabled = false;
  decoderContext->lfe_mix_level = 0.0;
  decoderContext->has_center_mix_level = false;
  decoderContext->downmix_active = false;

  // Free encoder resources on reset to allow reconfiguration with correct parameters
  if (decoderContext->encoder_context) {
    avcodec_free_context(&decoderContext->encoder_context);
    decoderContext->encoder_context = nullptr;
  }
  if (decoderContext->fifo) {
    av_audio_fifo_free(decoderContext->fifo);
    decoderContext->fifo = nullptr;
  }
  if (decoderContext->encoder_frame) {
    av_frame_free(&decoderContext->encoder_frame);
    decoderContext->encoder_frame = nullptr;
  }
  if (decoderContext->encoder_packet) {
    av_packet_free(&decoderContext->encoder_packet);
    decoderContext->encoder_packet = nullptr;
  }
  while (decoderContext->pending_packet_head) {
    PendingEncodedPacket* node = decoderContext->pending_packet_head;
    decoderContext->pending_packet_head = node->next;
    av_packet_free(&node->packet);
    free(node);
  }
  decoderContext->pending_packet_tail = nullptr;
  decoderContext->pending_packet_count = 0;
  decoderContext->encoder_initialized = false;
  decoderContext->encoder_flush_started = false;
  decoderContext->encoder_flush_completed = false;
  decoderContext->encoder_final_frame_submitted = false;
  decoderContext->decoder_drain_started = false;
  decoderContext->decoder_drain_completed = false;
  decoderContext->resampler_drain_completed = false;
  decoderContext->next_encoder_pts = 0;
  decoderContext->last_output_pts = AV_NOPTS_VALUE;
  decoderContext->last_output_packet_count = 0;
}

void resetLimiter(AudioLimiter* limiter) {
  if (!limiter) {
    return;
  }
  limiter->attenuation = 1.0f;
  limiter->sample_rate = LIMITER_DEFAULT_SAMPLE_RATE;
  limiter->hold_counter = 0;
  limiter->increase = 0.0f;
}

float getLimiterGain(AudioLimiter* limiter, float highestSample) {
  if (!limiter) {
    return 1.0f;
  }

  if (highestSample * limiter->attenuation > 1.0f) {
    limiter->attenuation = 1.0f / highestSample;
    limiter->hold_counter =
        static_cast<int>(lroundf(limiter->sample_rate * LIMITER_HOLD_SECONDS));
    limiter->increase = powf(
        fminf(highestSample, 10000.0f),
        1.0f / (LIMITER_RELEASE_SECONDS * limiter->sample_rate));
  }

  const float gain = limiter->attenuation;
  if (limiter->hold_counter > 0) {
    --limiter->hold_counter;
  } else if (limiter->increase > 0.0f) {
    limiter->attenuation *= limiter->increase;
    if (limiter->attenuation > 1.0f) {
      limiter->increase = 0.0f;
      limiter->attenuation = 1.0f;
    }
  }
  return gain;
}

void limitInterleavedFloat(AudioLimiter* limiter, float* samples,
                           int frameCount, int channelCount,
                           int sampleRate) {
  if (!limiter || !samples || frameCount <= 0 || channelCount <= 0) {
    return;
  }
  if (sampleRate > 0) {
    limiter->sample_rate = static_cast<float>(sampleRate);
  }

  for (int frame = 0; frame < frameCount; ++frame) {
    float* frameSamples = samples + frame * channelCount;
    float highest = 0.0f;
    for (int channel = 0; channel < channelCount; ++channel) {
      highest = fmaxf(highest, fabsf(frameSamples[channel]));
    }
    const float gain = getLimiterGain(limiter, highest);
    if (gain < 1.0f) {
      for (int channel = 0; channel < channelCount; ++channel) {
        frameSamples[channel] *= gain;
      }
    }
  }
}

void limitPlanarFloat(AudioLimiter* limiter, uint8_t** samples,
                      int frameCount, int channelCount, int sampleRate) {
  if (!limiter || !samples || frameCount <= 0 || channelCount <= 0) {
    return;
  }
  if (sampleRate > 0) {
    limiter->sample_rate = static_cast<float>(sampleRate);
  }

  for (int frame = 0; frame < frameCount; ++frame) {
    float highest = 0.0f;
    for (int channel = 0; channel < channelCount; ++channel) {
      float* channelSamples = reinterpret_cast<float*>(samples[channel]);
      highest = fmaxf(highest, fabsf(channelSamples[frame]));
    }
    const float gain = getLimiterGain(limiter, highest);
    if (gain < 1.0f) {
      for (int channel = 0; channel < channelCount; ++channel) {
        float* channelSamples = reinterpret_cast<float*>(samples[channel]);
        channelSamples[frame] *= gain;
      }
    }
  }
}

void releaseContext(DecoderContext* decoderContext) {
  if (!decoderContext) {
    return;
  }
  clearResampler(decoderContext);
  if (decoderContext->requested_output_layout_name) {
    free(decoderContext->requested_output_layout_name);
    decoderContext->requested_output_layout_name = NULL;
  }
  if (decoderContext->encoder_context) {
    avcodec_free_context(&decoderContext->encoder_context);
  }
  if (decoderContext->fifo) {
    av_audio_fifo_free(decoderContext->fifo);
  }
  if (decoderContext->encoder_frame) {
    av_frame_free(&decoderContext->encoder_frame);
  }
  if (decoderContext->encoder_packet) {
    av_packet_free(&decoderContext->encoder_packet);
  }
  if (decoderContext->codec_context) {
    avcodec_free_context(&decoderContext->codec_context);
  }
  free(decoderContext);
}

bool copyChannelLayout(const AVChannelLayout* source, AVChannelLayout* destination) {
  if (!source || !destination) {
    return false;
  }
  return av_channel_layout_copy(destination, source) >= 0;
}

bool channelLayoutsEqual(const AVChannelLayout* left, const AVChannelLayout* right) {
  if (!left || !right) {
    return false;
  }
  return av_channel_layout_compare(left, right) == 0;
}

bool getInputChannelLayout(AVCodecContext* codecContext, AVFrame* frame,
                           AVChannelLayout* inputLayout) {
  if (frame->ch_layout.nb_channels > 0) {
    return copyChannelLayout(&frame->ch_layout, inputLayout);
  }
  if (codecContext->ch_layout.nb_channels > 0) {
    return copyChannelLayout(&codecContext->ch_layout, inputLayout);
  }
  int channelCount = codecContext->ch_layout.nb_channels;
  if (channelCount <= 0) {
    return false;
  }
  av_channel_layout_default(inputLayout, channelCount);
  return inputLayout->nb_channels == channelCount;
}

bool getOutputChannelLayout(DecoderContext* decoderContext,
                            const AVChannelLayout* inputLayout,
                            AVChannelLayout* outputLayout) {
  if (decoderContext->requested_output_channel_count > 0 &&
      inputLayout->nb_channels >
          decoderContext->requested_output_channel_count) {
    return applyRequestedOutputLayout(decoderContext, outputLayout);
  }
  return copyChannelLayout(inputLayout, outputLayout);
}

bool applyRequestedOutputLayout(DecoderContext* decoderContext,
                                AVChannelLayout* outputLayout) {
  if (!decoderContext || !outputLayout) {
    return false;
  }

  if (decoderContext->requested_output_layout_name &&
      av_channel_layout_from_string(
          outputLayout, decoderContext->requested_output_layout_name) >= 0) {
    return outputLayout->nb_channels ==
        decoderContext->requested_output_channel_count;
  }

  av_channel_layout_default(outputLayout,
                            decoderContext->requested_output_channel_count);
  return outputLayout->nb_channels ==
      decoderContext->requested_output_channel_count;
}

double getAdjustedCenterMixLevel(AVFrame* frame, jint userCenterMixLevelDb,
                                 bool isDownmixActive) {
  if (!isDownmixActive) {
    return DEFAULT_CENTER_MIX_LEVEL;
  }

  double centerMixLevel = DEFAULT_CENTER_MIX_LEVEL;
  AVFrameSideData* sideData =
      av_frame_get_side_data(frame, AV_FRAME_DATA_DOWNMIX_INFO);
  if (sideData && sideData->size >= sizeof(AVDownmixInfo)) {
    AVDownmixInfo* downmixInfo = (AVDownmixInfo*)sideData->data;
    centerMixLevel = downmixInfo->center_mix_level;
  }

  if (centerMixLevel <= 0.0) {
    return centerMixLevel;
  }

  double currentDb = 20.0 * log10(centerMixLevel);
  return pow(10.0, (currentDb + userCenterMixLevelDb) / 20.0);
}
