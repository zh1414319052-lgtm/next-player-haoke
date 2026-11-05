# HDR playback fix in 0.19.0-haoke.8 (83)

The previous release could select FFmpeg for AV1 HDR. Its RGBA8888 video output
does not perform HDR tone mapping or signal an HDR surface, causing washed-out
colors. The locally maintained Nextlib renderer now rejects PQ, HLG and Dolby
Vision for that output path. SDR video and FFmpeg audio remain available.

The application uses separate native HDR and SDR renderers. HDR goes directly to
the system surface; the SDR renderer retains motion interpolation. This also
avoids a persistent Media3 effect graph carrying its first stream's color space
into an incompatible HDR/SDR transition. HDR interpolation is currently unavailable
and the UI reports this explicitly. A missing system HDR decoder is reported as
unsupported instead of producing incorrect colors.

## Evidence and reproducible checks

On a OnePlus PKG110 running Android 16, QA tests covered HDR10, HLG, HDR10+, AV1
HDR and Dolby Vision 8.4, each with interpolation switched off and on, plus SDR.
They covered pause, seeking, resume, manual FFmpeg fallback and HDR/SDR transitions.
HDR output matched a system-renderer reference captured on the same device.
Screenshots are not HDR display calibration; other devices and Dolby Vision
profiles 5/7 have not been verified.

Public diagnostic samples: [AndroidX Media3 test assets](https://github.com/androidx/media/tree/release/libraries/test_data/src/test/assets/media/mp4)
and [haasn/hdr-tests](https://github.com/haasn/hdr-tests). Short samples were looped
with stream copy, preserving HDR metadata. Samples are not bundled in the APK or
this repository. Their download availability is not a blanket commercial license.

`HdrCapabilityTest` checks the HDR capability boundary. `ColorModeVideoRendererTest`
checks format isolation and native HDR effects-message handling.
`HdrPlaybackDeviceTest` compares the QA service with native output.
`HdrFormalDeviceTest` exercises the signed/R8 artifact and checks existing settings
and history fingerprints across the same-signature upgrade without printing data.
Device tests require their explicit task flags and task-owned synthetic fixtures.

The formal release uses the established package/certificate, versionCode 83 and
ARM64 packaging. Source and build instructions are in the root README; fixed
Nextlib source, license and unchanged native-library provenance are in
`core/decoder`. No new permissions, telemetry, background polling or updater
identity are introduced by this fix.
