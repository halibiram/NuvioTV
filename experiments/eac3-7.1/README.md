# Experimental E-AC-3 5.1/7.1 encoder integration

The Android local decoder enables AC-3 and E-AC-3 encoding. Eight-channel E-AC-3 requires the pinned candidate and all three corrections below. Stock prebuilt AARs are unchanged by this source contribution and do not expose the new E-AC-3 setting.

## Attribution and pinned inputs

- FFmpeg base: `c352a9ab022b945307f1ad5d35575f695a13c2ca`.
- Candidate: FFmpeg PR 21668, Nomis101/ShortKatz, commit `f32ba6711cb3929d07ea7df3382281965352cb1b`.
- Original patch: https://code.ffmpeg.org/FFmpeg/FFmpeg/pulls/21668.patch
- Mailing-list source: https://www.mail-archive.com/ffmpeg-devel@ffmpeg.org/msg187572.html
- `upstream-21668.patch` retains the candidate attribution. Transport wrapping from the mailing-list copy was removed and all nine resulting Git blob hashes were checked against the patch index hashes (LF normalization).

## Prepare the FFmpeg source (does not compile)

Use a fresh checkout; the correction scripts intentionally fail when their expected source does not match. From the NuvioTV root, with `<ffmpeg-source>` replaced by an absolute checkout path:

```sh
git clone --filter=blob:none --no-checkout https://github.com/FFmpeg/FFmpeg.git <ffmpeg-source>
git -C <ffmpeg-source> checkout c352a9ab022b945307f1ad5d35575f695a13c2ca
git -C <ffmpeg-source> apply /absolute/path/to/NuvioTV/experiments/eac3-7.1/upstream-21668.patch
python3 experiments/eac3-7.1/fix_base_mapping.py <ffmpeg-source>
python3 experiments/eac3-7.1/fix_surround_substream.py <ffmpeg-source>
python3 experiments/eac3-7.1/fix_decoder_mapping.py <ffmpeg-source>
```

When using `build_ffmpeg.sh`, place the checkout at `<module-main>/jni/ffmpeg` (normally `ffmpeg-decoder-downmix/src/main/jni/ffmpeg`); that script resolves its source from its first module-path argument. The CMake/JNI build also supports an external source tree. Point `FFMPEG_SOURCE_DIR` and `FFMPEG_BUILD_DIR` at that patched source and its Android library outputs and select `USE_LOCAL_FFMPEG_DECODER=true` (or `-PuseLocalFfmpegDecoder=true`). The existing `build_ffmpeg.sh` enables both encoders. Rebuild FFmpeg and JNI/Java together; the encoded scheduler adds JNI entry points and changes the output mode contract. Do not combine a newly compiled Java decoder with an older native library. Native build instructions and toolchain requirements remain those of `ffmpeg-decoder-downmix`.

## What the local corrections do

- `fix_base_mapping.py`: preserve channel identity when the independent six-channel base reads eight-channel input planes.
- `fix_surround_substream.py`: represent side and rear surrounds in the dependent substream, preserve source planes, fix bit allocation and write the final dependent substream's compression word as specified in ATSC A/52 Annex E.3.8.5.
- `fix_decoder_mapping.py`: combine independent/dependent streams by channel identity rather than assuming indices are unchanged when expanding the layout.
- `verify_channels.py`: verify substream order, the dependent channel map, frame CRCs and total bitrate, then round-trip distinct-frequency 5.1 and 7.1 tones to measure amplitude and crosstalk.

The original candidate advertised 7.1 but failed channel-isolation testing; advertising eight channels alone was insufficient. After the corrections, the earlier experimental Android APK played all individually announced TrueHD 7.1 channels on the user's HDMI eARC setup. This is device evidence for that earlier APK, not validation of the PR3107 integration, other ABIs or all receivers.

No Atmos encoding/preservation is claimed. Native E-AC-3 JOC passthrough and source TrueHD Atmos identification remain separate from ordinary E-AC-3 transcoding. The PR3107 integration has passed a local full ARMv7 Android/native build and its Gradle audio suite. Integration-specific device regression testing, visual evidence and other ABIs remain pending before merge readiness.
