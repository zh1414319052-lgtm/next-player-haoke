# DLNA casting repair — 0.19.0-haoke.12

The reported failure was an Android 14 Compose duplicate-key crash while the cast
device sheet displayed repeated renderer UUIDs. A single renderer can advertise
multiple SSDP services and description locations. The old discovery path used
service USNs and concurrently published mutable snapshots, which could duplicate
one renderer or overwrite a newer result.

## Behavior and invariants

- One row per canonical device UDN/UUID; empty UDN falls back to the description
  URL without lowercasing case-sensitive URL paths. UUID matching is locale-neutral.
- One description request per exact location in each scan, with at most four
  requests in flight. One flow collector merges completed descriptions.
- Automatic discovery and manual additions use the same identity and merge rule.
  Later descriptions update a device; distinct devices with the same name remain.
  Discovery updates preserve manually added devices.
- Scan/probe errors end progress state and allow retry. Cancellation closes the
  socket, releases the multicast lock and prevents old results reaching a reopened
  sheet. A socket closed during cancellation is not treated as a scan failure.
- Selection, compatibility confirmation and cast transport retain their behavior.

## Verification

The final isolated QA build passed 18 cast, 135 player and 2 primary-language unit
tests (155 total). Six device tests passed on a OnePlus PKG110 / Android 16:
duplicate identities and concurrent completion, manual addition and refresh,
failure/retry, selection and compatibility confirmation, cancellation/reopen,
language resources, and a local synthetic DLNA renderer using actual HTTP/SOAP
requests and media byte-range reads with pause/seek/resume/stop.

The initial device run exposed an EBADF error when canceling a blocked UDP receive.
That failure was fixed before the successful six-test rerun. Both isolated QA
packages were removed after testing; existing formal installations were retained.

The synthetic renderer is not a real TV decoder test. The reporter's Redmi
23054RA19C / Android 14 and their television were unavailable. No claim is made
that every TV, codec or router configuration is compatible.

The final formal APK is checked separately for release build/lint, update tests,
package identity, increasing version code, established signing certificate and
16 KiB ZIP alignment. The project's existing instruction forbids installing the
formal APK for device testing. See the current README for final artifact facts.

## Reproduction commands

Use JDK 21 and the Android SDK specified by the project. On Windows use an ASCII
path and serialize packaging to avoid the local APK packager concurrency issue.

```powershell
.\gradlew.bat --max-workers=1 --no-parallel -PhaokeTestBuildType=release :app:assembleRelease :app:lintRelease :app:testReleaseUnitTest
.\gradlew.bat --max-workers=1 --no-parallel :core:cast:testDebugUnitTest :feature:player:testDebugUnitTest :core:common:testDebugUnitTest --tests "*PrimaryLanguageTest"
```

For device tests build `:app:assembleQaFeedback` and
`:app:assembleQaFeedbackAndroidTest` with `-PhaokeTestBuildType=qaFeedback`.
Run `CastDevicePickerDeviceTest`, `CastLoopbackDeviceTest` and
`PrimaryLanguageDeviceTest` using `AndroidJUnitRunner` on an authorized device.

## 中文说明

本版修复投屏设备重复广播造成列表闪退的问题，将自动发现和手动添加统一按设备身份合并，保留手动设备，并处理扫描失败、重试、关闭后重新打开及取消扫描时的套接字异常。真实手机测试通过；用户的 Redmi 与电视未实测，局域网发现和电视解码兼容性仍依赖实际设备。
