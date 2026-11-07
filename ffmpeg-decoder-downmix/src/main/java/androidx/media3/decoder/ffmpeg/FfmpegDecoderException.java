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

import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;

/** Thrown when an FFmpeg decoder error occurs. */
@UnstableApi
public final class FfmpegDecoderException extends DecoderException {
  private final boolean eac3LayoutMismatch;

  /* package */ FfmpegDecoderException(String message) {
    super(message);
    eac3LayoutMismatch = false;
  }

  /* package */ FfmpegDecoderException(String message, Throwable cause) {
    super(message, cause);
    eac3LayoutMismatch = false;
  }

  private FfmpegDecoderException(String message, boolean eac3LayoutMismatch) {
    super(message);
    this.eac3LayoutMismatch = eac3LayoutMismatch;
  }

  static FfmpegDecoderException eac3LayoutMismatch() {
    return new FfmpegDecoderException("Decoded layout differs from negotiated E-AC-3 output.", true);
  }

  public boolean isEac3LayoutMismatch() {
    return eac3LayoutMismatch;
  }
}
