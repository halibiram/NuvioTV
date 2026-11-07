package com.nuvio.tv.core.player.dvmkv;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.extractor.Ac3Util;
import java.util.Arrays;

/** Refines Matroska E-AC-3 formats from JOC headers and discrete 7.1 extensions. */
final class Eac3Surround {
  private Eac3Surround() {}

  static Format refineFormat(Format format, byte[] data, int offset, int limit) {
    int baseSize = completeSyncframeSize(data, offset, limit);
    if (baseSize == 0) {
      return format;
    }
    Ac3Util.SyncFrameInfo base = parseFrame(data, offset, baseSize);
    if (base == null || base.streamType == Ac3Util.SyncFrameInfo.STREAM_TYPE_TYPE1) {
      return format;
    }
    if (MimeTypes.AUDIO_E_AC3_JOC.equals(base.mimeType)) {
      return format.buildUpon()
          .setSampleMimeType(MimeTypes.AUDIO_E_AC3_JOC)
          .setChannelCount(base.channelCount)
          .build();
    }

    int extension = offset + baseSize;
    int extensionSize = completeSyncframeSize(data, extension, limit);
    if (extensionSize < 10) {
      return format;
    }
    int bitstreamId = (data[extension + 5] & 0xff) >> 3;
    if (((data[extension + 2] & 0xff) >> 6) != 1 || bitstreamId < 11 || bitstreamId > 16) {
      return format;
    }

    boolean has71Extension = base.channelCount == 6 && has71ChannelMap(data, extension, extensionSize);
    Ac3Util.SyncFrameInfo dependent = parseFrame(data, extension, extensionSize);
    if (dependent != null
        && dependent.streamType == Ac3Util.SyncFrameInfo.STREAM_TYPE_TYPE1
        && dependent.sampleRate == base.sampleRate
        && dependent.sampleCount == base.sampleCount
        && MimeTypes.AUDIO_E_AC3_JOC.equals(dependent.mimeType)) {
      return format.buildUpon()
          .setSampleMimeType(MimeTypes.AUDIO_E_AC3_JOC)
          .setChannelCount(has71Extension ? 8 : base.channelCount)
          .build();
    }
    return has71Extension ? format.buildUpon().setChannelCount(8).build() : format;
  }

  private static Ac3Util.SyncFrameInfo parseFrame(byte[] data, int offset, int size) {
    try {
      return Ac3Util.parseAc3SyncframeInfo(
          new ParsableBitArray(Arrays.copyOfRange(data, offset, offset + size)));
    } catch (IllegalStateException | IllegalArgumentException | IndexOutOfBoundsException e) {
      return null;
    }
  }

  private static boolean has71ChannelMap(byte[] data, int extension, int extensionSize) {
    ParsableBitArray header = new ParsableBitArray(data, extension + extensionSize);
    header.setPosition(extension * 8 + 50); // After bsid and dialnorm.
    if (header.readBit()) {
      header.skipBits(8); // Compression gain.
    }
    if (!header.readBit()) {
      return false;
    }
    int channelMap = header.readBits(16);
    int channelMode = (data[extension + 4] >> 1) & 7;
    return ((channelMode == 5 || channelMode == 6) && channelMap == 0x1a00)
        || (channelMode == 2 && channelMap == 0x0200);
  }

  private static int completeSyncframeSize(byte[] data, int offset, int limit) {
    if (offset < 0 || offset > limit || limit > data.length || limit - offset < 6
        || data[offset] != 0x0b || data[offset + 1] != 0x77) {
      return 0;
    }
    int size = Ac3Util.parseAc3SyncframeSize(Arrays.copyOfRange(data, offset, offset + 6));
    return size >= 6 && size <= limit - offset ? size : 0;
  }
}
