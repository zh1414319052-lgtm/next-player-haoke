# Nextlib HDR capability patch

This module replaces the default `nextlib-media3ext` Java/Kotlin classes with the
same upstream sources plus a small HDR capability fix. It retains the renderer
identities expected by `DecoderManager` and leaves audio decoding unchanged.

Upstream: https://github.com/anilbeesetti/nextlib/tree/2dc77cd2d9352bc308aa26782f9c77a320df510b

License: GPL-3.0; the upstream license is included in `LICENSE`. Original source
blob IDs are recorded in `upstream-sources.json`. Native implementation source:
https://github.com/anilbeesetti/nextlib/tree/2dc77cd2d9352bc308aa26782f9c77a320df510b/media3ext/src/main/jni

The original `io.github.anilbeesetti:nextlib-media3ext:1.11.1-0.16.0` AAR is used
only to extract its unchanged `jni/**` files during the build. Its SHA-256 is
checked by the build task. Its `classes.jar` is never packaged alongside these
classes. All original native ABIs and consumer ProGuard rules are preserved.

## Behavior

`FfmpegVideoRenderer.supportsFormat` rejects PQ/ST2084, HLG and Dolby Vision.
The native renderer currently converts pixels to RGBA8888 without HDR tone
mapping or HDR surface signaling; advertising full support can make Media3
prefer this incorrect output over a working system codec. System MediaCodec
renderers now retain responsibility for HDR. This does not add HDR support to
devices that lack a capable system codec. Unsupported manual selections use the
player's existing confirmation/recovery flow rather than displaying false colors.

SDR remains available through FFmpeg, including 10-bit SDR/BT.2020 metadata.
The audio renderer, decoder-switching state machine and native binaries are
unchanged. The player reports FFmpeg interpolation as unavailable, consistently
with software decoding, because that renderer bypasses the video effects graph.

At the application layer, `HdrRenderersFactory` uses separate native HDR and SDR
renderers. HDR never receives video-effect messages: this preserves native HDR
metadata/output and avoids Media3's persistent graph reusing its first stream's
output color space for an incompatible later stream. HDR interpolation is
explicitly unavailable in the UI; SDR interpolation and decoder choices remain.

`-PnextlibPath=...` remains an explicit opt-in to an external Nextlib checkout.
That development override bypasses this module and must carry the equivalent
HDR capability patch before it can be treated as an HDR-fixed build.

## Validation

Run `:core:decoder:testDebugUnitTest` and the player's decoder recovery tests.
The isolated `qaHdr` APK and `HdrPlaybackDeviceTest` exercise real JNI SDR support,
HDR decoder selection, manual fallback, HDR/SDR transitions, and playback with
interpolation on/off. See `docs/HDR-FIX.md` for the physical-device results.
