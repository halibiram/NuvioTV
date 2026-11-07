# E-AC-3 integration with the PR3107 passthrough branch

Base: `halibiram/NuvioTV:fix/passthrough-content-time-clock` at `28626bb1f83993b7028b115973ab5fdb97f2fc6b`.
Source of the transcoding experiment: `HeliumMarcos/NuvioTV:heliummarcos/eac3-transcoding-5.1-7.1` at `772aac3e3`.
Requested integration: https://github.com/NuvioMedia/NuvioTV/issues/2398#issuecomment-6689671248

## User behavior

Automatic surround resolution, learned rejections and the IEC/MAT paths remain available. Auto surround mode ignores the stored manual output format.

In Playback > Audio > Surround Sound > Manual, enable passthrough for the formats your audio system accepts. The **Blocked formats output** selector offers PCM, Dolby Digital (AC-3), and Dolby Digital Plus (E-AC-3). The last choice requires the rebuilt decoder AAR.

- The passthrough switches select the blocked formats. AC-3 can receive converted TrueHD, DTS, DTS-HD and E-AC-3/JOC. E-AC-3 can receive converted TrueHD, DTS and DTS-HD. AAC is outside this conversion policy.
- The app uses PCM for blocked formats that cannot use the selected Dolby output. AC-3 output still requires AC-3 support on the active route, including optical routes.
- **Decode Channel Target** is the surround channel control for processed audio. Auto preserves eligible 5.1 and 7.1 sources when converting to E-AC-3. Selecting 5.1 caps E-AC-3 at 5.1, and selecting 7.1 preserves 5.1 or 7.1 without upmixing. Selecting 2.0 uses PCM for blocked formats. AC-3 is limited to 5.1. Passthrough keeps the source layout.
- The retired source masks, E-AC-3-only 5.1 switch and optical force switch no longer affect playback. The manual format selection and channel target remain stored independently.
- AC-3 uses the PR3107 sample-rate rules at 32, 44.1 and 48 kHz. Other source rates map to 48 kHz. E-AC-3 uses 48 kHz.

The playback stats show the source codec and layout on one audio row. The Output row appears during AC-3 or E-AC-3 transcoding and uses the live AudioTrack configuration. Route and the existing IEC, clock, bitrate and underrun diagnostics remain available. The native IEC path bypasses the stock AudioTrack provider, so its route information remains in the IEC diagnostics.

## Bitstream metadata

The Matroska extractor refines E-AC-3 JOC and dependent-substream 7.1 channel information and detects TrueHD Atmos from checksum-validated major sync headers. TrueHD retains its MIME/channel count and receives a metadata entry, not an invented codec string.

The early scan is bounded and leaves a per-sample fallback. DTS retains PR3107's sync search. Lace parsing handles fixed, Xiph and all EBML size fields; truncated lace headers retain the late fallback. The player track label/profile and input statistics consume the bitstream evidence.

JOC source statistics show the compressed base channel count. A source Atmos marker is not copied to ordinary transcoded E-AC-3. TrueHD Atmos object preservation during transcoding is not implemented.

## Encoded decoder pipeline

The local Java/JNI decoder carries the experiment's one-access-unit output queue, sample-based packet timestamps, normal-playback queue servicing, ordered decoder/resampler/FIFO/encoder draining and seek/flush invalidation. It replaces the target branch's earlier concatenated AC-3 drain implementation while preserving its renderer audio-path reporting and AC-3 sample-rate helpers.

Java/JNI must be rebuilt together. Do not combine the new Java decoder with old native libraries. E-AC-3 7.1 also requires the pinned FFmpeg candidate and three mapping/bit-allocation corrections described in [the encoder README](../../experiments/eac3-7.1/README.md). No prebuilt AAR is replaced by this source contribution.

## Validation status

- 94 focused isolated JVM tests passed: passthrough policy, denied-codec planner, surround resolver, AC-3 rate/flush helpers, E-AC-3 layout eligibility, audio descriptions, E-AC-3/JOC probing and Matroska lacing.
- Four TrueHD Atmos parser tests passed, including real Atmos/plain headers, checksum/truncation failures and metadata preservation.
- Java sources for the FFmpeg module and vendored Matroska extractor typechecked against the target AARs, with only decoder/container dependencies supplemented from the pinned Maven version.
- The exact AudioTrack-provider wrapper was separately typechecked against the target AAR API; it uses `DefaultAudioSink.AudioTrackProvider.DEFAULT`.
- JNI native names/parameter arities were statically checked for all 16 Java decoder declarations.
- Independent review found four issues (provider API, Auto/manual preference isolation, stereo eligibility, EBML lace offsets). All four were corrected and re-reviewed with no remaining P1/P2 finding in that review.
- Tests are JVM checks, not a complete Android/native build. They use a small local implementation of Android TextUtils for the isolated JVM environment; it is not part of the app.
- The authorized full local ARMv7 Android/native build passed (`:app:assembleFullDebug`, 10m36s). Native JNI, application Kotlin/Java, DEX and APK packaging completed. The local signing preflight was corrected only through environment variables and a task-local validation key, with no project signing changes.
- The final Gradle audio suite passed: **63 classes, 746 tests, zero failures/errors, one skipped optional live-network AFR test**. This includes the existing IEC/passthrough/tunnel suites and the new codec/metadata/layout/lacing regressions. The final test pass after making the optional-layout tests compatible with old AARs took 22s.
- APK signature, ARMv7 ABI and inclusion of the rebuilt `libffmpegJNI.so` were verified. Validation APK SHA-256: `9117ba43f54ec88fe77381e71faa62bb578794c1e1858e09cc430a62d62e4b99`. It was built from the working tree before the final source commit and was not installed on the TV. No GitHub compilation was dispatched.
- The optional layout test also runs safely against the unchanged stock AAR: its availability guard skips E-AC-3-specific assertions when those APIs are absent.
- Before the menu and stats simplification, the integration APK played a TrueHD 7.1 channel-identification clip with E-AC-3 output. Android reported eight channels at 48 kHz over HDMI eARC, and the listener confirmed that every channel played in the corresponding speaker, including both rears. The revised APK was then installed as an in-place update to the test package and exercised on the same HDMI eARC chain.
- Full builds/CI dispatch are intentionally not automatic in this work session and require the user's request.

## Revised menu and stats validation

The local ARMv7 APK built successfully. The updated audio suite ran 740 tests across 63 classes, with 739 passing, one optional live network test skipped and zero failures.

| Playback case | TV result |
| --- | --- |
| TrueHD 7.1, channel target Auto | [DD+ 7.1 output](images/pr3107/truehd-to-ddp-71.png). Android reported eight E-AC-3 channels over HDMI eARC. |
| TrueHD 7.1, channel target 5.1 | [DD+ 5.1 output](images/pr3107/truehd-to-ddp-51.png). Android reported six E-AC-3 channels over HDMI eARC. |
| Native DD+ 7.1 | [Direct passthrough](images/pr3107/ddp-71-passthrough.png). The source label shows 7.1 and the Output row is hidden. |

The [output selector](images/pr3107/blocked-formats-output.png) and [channel target](images/pr3107/channel-target.png) were verified in Brazilian Portuguese. Optical/SPDIF, playback stress tests and additional ABIs remain pending.
