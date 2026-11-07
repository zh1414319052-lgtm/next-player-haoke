# 下一代播放器 / Next Player · 浩科优化版

**0.19.0-haoke.10（85）**，基于 [Anil Beesetti / Next Player](https://github.com/anilbeesetti/nextplayer) 0.19.0 的独立维护版本，保留 GPL-3.0、原作者和第三方归属；未经上游作者背书。上游说明见 [README.upstream.md](README.upstream.md)。

**[正式版下载：浩科工具站](https://haoke.org/software/10cdc544f64e9b91)** · [本版更新说明](https://haoke.org/software/10cdc544f64e9b91/releases/nextplayer-haoke-0190-10)

GitHub 提供对应源码，不托管 APK。网站的固定网盘入口由站长维护。

## 本版修复

修复视频暂停后旋转屏幕画面变黑，以及保留暂停帧时横屏画面缩小的问题。暂停位置保持不动，无需短暂播放或跳帧来刷新。正确处理视频尺寸回调缺失、旋转和非方形像素；恢复播放后回到正常 Surface 布局。实现及验收边界见 [PAUSED-ROTATION-FIX.md](docs/PAUSED-ROTATION-FIX.md)。

## 保留的功能

- 本地及网络视频、文件夹浏览、字幕和音轨选择、倍速、手势、画中画及后台音频，石墨黑与青绿色影院界面。
- 干净 PNG 单帧导出，不含控件或可选择字幕；保留原始像素至长边 3840，超出等比例缩小。HDR 图片转 BT.709 SDR。画面内烧录字幕无法去除；DRM、直播、不可定位或不受支持的解码器不保证能导出。
- Android 10+ 保存到 Pictures/Next Player；旧系统使用文档选择器。截图保持播放位置和状态。
- 连续横竖屏切换、隐藏控件后的遥控唤出/快进/后退/暂停/续播、锁定和投屏状态下的按键处理。
- SDR GPU 运动补偿插帧，支持固定 60 帧及两倍模式。适用于硬件解码、1 倍速、12–30 fps、最高 3840×2160；效果因设备/片源而异，不保证全程锁定 60 帧，复杂运动可能有伪影。
- HDR 保持系统原生输出，暂不支持实时插帧；解码依赖系统与硬件。
- 自定义片头/片尾跳过分别 0–1800 秒，0 为不跳过；可选自动识别，默认关闭。保留直播、短视频、循环及手动回看保护。
- FTP 在长度可确认且服务器支持 REST 等条件下，从实际读取位置续传；保留 DLNA 投屏及既有签名更新检查。

首次安装默认深色影院主题，插帧与跳过默认关闭；覆盖升级沿用已有偏好。蓝牙遥控器品牌、电视和局域网协议兼容性依赖实际设备。

## 正式包身份

| 项目 | 值 |
| --- | --- |
| 版本 | 0.19.0-haoke.10 / 85 |
| Application ID | dev.anilbeesetti.nextplayer.haoke |
| 系统 / 架构 | Android 7.0+，minSdk 24，arm64-v8a |
| APK 字节数 | 33135947 |
| APK SHA-256 | ea2a2fc16ca6d398d87cf0d60ab7c70078ef59d9d8984eab0d3a93868e8ea3fd |
| 正式证书 SHA-256 | d27867cecea4c6e041195cc87501c4663cefa6a6c6240a0fca835998a87c09f9 |

沿用原正式签名，可覆盖已有浩科正式版；上游版和 QA 版使用独立身份及数据。正式包非 debuggable / 非 testOnly，签名与 16 KB ZIP 对齐通过。13 项权限及 10 个原生库与 .9 正式版一致。

## 构建与验证

Kotlin / Jetpack Compose 多模块工程。实际验证使用 JDK 21、JVM target 17、Android SDK 37 和 Gradle wrapper。Windows 推荐 ASCII 构建路径；SDK 路径放在不提交的 local.properties。

~~~powershell
.\gradlew.bat -PhaokeTestBuildType=release :app:assembleRelease :app:assembleReleaseAndroidTest :app:lintRelease :app:testReleaseUnitTest :feature:player:testDebugUnitTest :core:common:testDebugUnitTest :core:decoder:testDebugUnitTest --console=plain
~~~

Linux/macOS 使用 ./gradlew。ARM64 未签名包位于 app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk；自行测试可构建 :app:assembleQaFeedback，使用独立 QA 包名。正式签名脚本 [scripts/sign_release.py](scripts/sign_release.py) 只读取维护者在源码外的受保护身份；仓库不含正式私钥或凭据。

- 本次 198 项相关自动测试通过：应用 63、播放器 131、公共层 2、解码层 2。Release Lint：0 错误、38 警告。正式签名 R8 包构建与归档检查通过。
- 修复的 QA 包已在一加 PKG110 / Android 16 完成普通 SDR、配置 60 fps 插帧和 HLG HDR 共 27 次方向切换，以及暂停 seek、前后台返回和续播；隐藏控件后的遥控原生按键检查连续两轮通过。
- **按用户本轮要求，最终 .10 正式包没有安装到真机，未执行 84→85 覆盖升级及正式包运行验收。QA 的真机证据不等同于最终正式包真机测试。** 临时验收辅助程序已卸载，手机仍保留 .9 正式版。
- 语言复用系统首选 zh → 简体中文、en/其他 → 英文；本次无新增 UI 文案，QA 连续旋转资源检查通过，未更改系统语言。未验证全部品牌手机、Android 14、电视或实际蓝牙遥控器配对。

单帧导出使用 Media3 1.11.1 inspector-frame。解码扩展来源和构建材料见 [core/decoder/README.md](core/decoder/README.md)，固定 Nextlib 源码为 [2dc77cd2d9352bc308aa26782f9c77a320df510b](https://github.com/anilbeesetti/nextlib/tree/2dc77cd2d9352bc308aa26782f9c77a320df510b)。本版未新增依赖或改变原生库。

## 更新与许可

沿用 Android/stable 的签名版本通知：验证身份、签名、时效、版本及兼容性；只在安全前台页面自动检查，并保留关于页手动检查。没有新增遥测、后台轮询、视频上传或旧远程弹窗。网站会接收常规请求/IP 元数据。

[GNU GPL v3.0](LICENSE) 及原作者、贡献者、第三方归属保留；分发修改安装包时需提供对应源码和许可。[Noto Sans SC](core/ui/src/main/assets/licenses/Noto-Sans-SC-OFL.txt) 使用 SIL OFL 1.1，可商用和再分发。

## English

Next Player Haoke **0.19.0-haoke.10 (85)** is an independently maintained GPL-3.0 edition based on Anil Beesetti's Next Player 0.19.0. This release fixes blank paused video after rotation and an undersized landscape picture while retaining the paused frame, without briefly playing or seeking to redraw.

It retains native HDR playback, optional SDR interpolation, clean decoded PNG frame export, hidden-control remote input, FTP resume, configurable intro/outro skipping and DLNA casting. Burned-in subtitles cannot be removed; exported HDR frames become SDR. Interpolation performance and hardware compatibility vary.

The ARM64 release requires Android 7.0+, keeps the existing application/signing identity and uses versionCode 85. APK: 33135947 bytes; SHA-256: ea2a2fc16ca6d398d87cf0d60ab7c70078ef59d9d8984eab0d3a93868e8ea3fd. The certificate fingerprint is listed above. 198 relevant automated tests passed; Release Lint has 0 errors and 38 warnings. The QA fix passed 27 rotations plus paused seeking, foreground return and resume on OnePlus PKG110 / Android 16; native remote-key injection passed twice.

**At the user's request, this final signed .10 APK was not installed or runtime-tested on a physical device, and the 84-to-85 upgrade was not exercised.** QA device results are reported separately. Paired Bluetooth remotes, TVs and other devices remain untested. Existing Chinese/English primary-language mapping is retained.

[Download from Haoke Tools](https://haoke.org/software/10cdc544f64e9b91). This repository and its version tag provide corresponding source; no APK binaries or release credentials are hosted here.
