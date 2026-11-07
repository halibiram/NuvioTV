package com.nuvio.tv.core.player.dvmkv;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;

/** Reads the TrueHD major sync for spatial metadata and the multichannel presentation. */
@UnstableApi
public final class TrueHdAtmos implements Metadata.Entry {
  public static final TrueHdAtmos INSTANCE = new TrueHdAtmos();

  // Bits representing pairs rather than single speakers in a TrueHD channel map.
  private static final int CHANNEL_PAIR_MASK = 0x679;

  private TrueHdAtmos() {}

  public static boolean isPresent(Format format) {
    if (format == null || !MimeTypes.AUDIO_TRUEHD.equals(format.sampleMimeType)
        || format.metadata == null) {
      return false;
    }
    for (int i = 0; i < format.metadata.length(); i++) {
      if (format.metadata.get(i) instanceof TrueHdAtmos) {
        return true;
      }
    }
    return false;
  }

  private static int findMajorSync(byte[] data, int offset, int limit) {
    if (offset < 0 || limit > data.length || offset > limit) {
      return -1;
    }
    for (int start = offset; start <= limit - 28; start++) {
      if ((data[start] & 0xff) != 0xf8 || data[start + 1] != 0x72
          || data[start + 2] != 0x6f || (data[start + 3] & 0xff) != 0xba) {
        continue;
      }
      int size = 28;
      if ((data[start + 25] & 1) != 0) {
        size += 2 + ((data[start + 26] & 0xf0) >> 4) * 2;
      }
      if (size > limit - start || !validChecksum(data, start, size)) {
        continue;
      }
      return start;
    }
    return -1;
  }

  private static boolean validChecksum(byte[] data, int start, int size) {
    // MLP major-sync CRC-16, polynomial 0x002d, followed by two XOR bytes.
    int crc = 0;
    for (int i = start; i < start + size - 4; i++) {
      crc ^= (data[i] & 0xff) << 8;
      for (int bit = 0; bit < 8; bit++) {
        crc = ((crc << 1) ^ ((crc & 0x8000) != 0 ? 0x002d : 0)) & 0xffff;
      }
    }
    crc = ((crc >>> 8) | (crc << 8)) & 0xffff;
    crc ^= littleEndian16(data, start + size - 4);
    return crc == littleEndian16(data, start + size - 2);
  }

  private static int littleEndian16(byte[] data, int offset) {
    return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
  }

  /** Returns null when the sample does not contain a complete, checksum-valid TrueHD major sync. */
  @Nullable
  static Format refineFormat(Format format, byte[] data, int offset, int limit) {
    if (!MimeTypes.AUDIO_TRUEHD.equals(format.sampleMimeType)) {
      return null;
    }
    int sync = findMajorSync(data, offset, limit);
    if (sync < 0) {
      return null;
    }
    int substreams = (data[sync + 16] & 0xf0) >> 4;
    int channelCount = format.channelCount;
    if (substreams >= 2 && substreams <= 4) {
      // Prefer the 13-bit multichannel map, falling back to the 5-bit map when absent.
      int channelMap = ((data[sync + 6] & 0x1f) << 8) | (data[sync + 7] & 0xff);
      if (channelMap == 0) {
        channelMap = ((data[sync + 5] & 7) << 2) | ((data[sync + 6] & 0xc0) >> 6);
      }
      int mappedChannels = Integer.bitCount(channelMap) + Integer.bitCount(channelMap & CHANNEL_PAIR_MASK);
      if (mappedChannels == 6 || mappedChannels == 8) {
        channelCount = mappedChannels;
      }
    }
    // The spatial presentation is independent of the 5.1/7.1 channel map.
    boolean addAtmos = substreams == 4 && (data[sync + 17] & 0x80) != 0 && !isPresent(format);
    if (channelCount == format.channelCount && !addAtmos) {
      return format;
    }
    Format.Builder refined = format.buildUpon().setChannelCount(channelCount);
    if (addAtmos) {
      Metadata metadata = format.metadata == null ? new Metadata(INSTANCE)
          : format.metadata.copyWithAppendedEntries(INSTANCE);
      refined.setMetadata(metadata);
    }
    return refined.build();
  }

  @Override public boolean equals(Object other) { return other instanceof TrueHdAtmos; }
  @Override public int hashCode() { return 0x54484441; }
  @Override public String toString() { return "TrueHD Atmos"; }
}
