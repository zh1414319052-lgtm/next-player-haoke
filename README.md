# 下一代播放器 / Next Player · 浩科优化版

**0.19.0-haoke.11（86）**，基于 [Anil Beesetti / Next Player](https://github.com/anilbeesetti/nextplayer) 0.19.0 的独立维护版本，保留 GPL-3.0、原作者和第三方归属；未经上游作者背书。上游说明见 [README.upstream.md](README.upstream.md)。

**[正式版下载：浩科工具站](https://haoke.org/software/10cdc544f64e9b91)** · [本版更新说明](https://haoke.org/software/10cdc544f64e9b91/releases/nextplayer-haoke-0190-11)

GitHub 提供对应源码，不托管 APK。网站的固定网盘入口由站长维护。

## 本版修复

修复中日双语字幕在同一位置叠字，两种语言现在分行显示。修复外挂 ASS/SSA 字幕无法显示的问题，支持同名自动加载和手动选择。跨过字幕空档拖动进度条、拖回上一句或重新续播时，会恢复当前应显示的字幕。ARM64，Android 7.0 及以上。 实现与验收边界见 [字幕修复记录](docs/SUBTITLE-FIX.md)。

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
| 版本 | 0.19.0-haoke.11 / 86 |
| Application ID | dev.anilbeesetti.nextplayer.haoke |
| 系统 / 架构 | Android 7.0+，minSdk 24，arm64-v8a |
| APK 字节数 | 33152331 |
| APK SHA-256 | eb2862e06efc0e822d904ac831e8605a306af3222a0c1c5d8dd5a886d4f31327 |
| 正式证书 SHA-256 | d27867cecea4c6e041195cc87501c4663cefa6a6c6240a0fca835998a87c09f9 |

沿用原正式签名，可覆盖已有浩科正式版；上游版和 QA 版使用独立身份及数据。正式包非 debuggable / 非 testOnly，签名与 16 KB ZIP 对齐通过。13 项权限及 10 个原生库与 .10 正式版功能一致，指纹库仅 ELF 构建标识不同。

## 构建与验证

Kotlin / Jetpack Compose 多模块工程。实际验证使用 JDK 21、JVM target 17、Android SDK 37 和 Gradle wrapper。Windows 推荐 ASCII 构建路径；SDK 路径放在不提交的 local.properties。

~~~powershell
.\gradlew.bat -PhaokeTestBuildType=release :app:assembleRelease :app:assembleReleaseAndroidTest :app:lintRelease :app:testReleaseUnitTest :feature:player:testDebugUnitTest :core:common:testDebugUnitTest :core:decoder:testDebugUnitTest --console=plain
~~~

Linux/macOS 使用 ./gradlew。ARM64 未签名包位于 app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk；自行测试可构建 :app:assembleQaFeedback，使用独立 QA 包名。正式签名脚本 [scripts/sign_release.py](scripts/sign_release.py) 只读取维护者在源码外的受保护身份；仓库不含正式私钥或凭据。

- 本次 202 项相关自动测试通过。Release Lint 0 错误、17 条警告。正式包签名与 16 KB 对齐已验证。
- 在一加 PKG110 / Android 16 上使用独立 QA 包和自制字幕验证中日双语分行、外挂 ASS 自动与手动加载、跨空档跳转、拖回首句、末尾清空及重新续播。
- 正式 APK 已核对覆盖升级所需的包名、签名证书及版本递增。按本项目此前的正式包交付要求，未在真机安装最终 APK。
- 语言沿用系统首选语言 zh → 简体中文、en/其他 → 英文，QA 语言用例通过。

单帧导出使用 Media3 1.11.1 inspector-frame。解码扩展来源和构建材料见 [core/decoder/README.md](core/decoder/README.md)，固定 Nextlib 源码为 [2dc77cd2d9352bc308aa26782f9c77a320df510b](https://github.com/anilbeesetti/nextlib/tree/2dc77cd2d9352bc308aa26782f9c77a320df510b)。本版未新增依赖或改变原生库。

## 更新与许可

沿用 Android/stable 的签名版本通知：验证身份、签名、时效、版本及兼容性；只在安全前台页面自动检查，并保留关于页手动检查。没有新增遥测、后台轮询、视频上传或旧远程弹窗。网站会接收常规请求/IP 元数据。

[GNU GPL v3.0](LICENSE) 及原作者、贡献者、第三方归属保留；分发修改安装包时需提供对应源码和许可。[Noto Sans SC](core/ui/src/main/assets/licenses/Noto-Sans-SC-OFL.txt) 使用 SIL OFL 1.1，可商用和再分发。

## English

Next Player Haoke **0.19.0-haoke.11 (86)** is an independently maintained GPL-3.0 edition based on Anil Beesetti's Next Player 0.19.0. Fixes overlapping Chinese and Japanese subtitle lines so both remain readable. Restores automatic and manual loading of external ASS/SSA subtitles. Active subtitles now reappear when seeking across a gap, seeking backward, or resuming playback. ARM64, Android 7.0 or later.

It retains native HDR playback, optional SDR interpolation, clean decoded PNG frame export, hidden-control remote input, FTP resume, configurable intro/outro skipping and DLNA casting. Burned-in subtitles cannot be removed. Interpolation performance and hardware compatibility vary.

The ARM64 release requires Android 7.0+. The signing certificate and application ID match the previous formal release, and the build number increases to 86. APK size is 33152331 bytes and its SHA-256 is `eb2862e06efc0e822d904ac831e8605a306af3222a0c1c5d8dd5a886d4f31327`. All 202 relevant automated tests passed. Release Lint reported 0 errors and 17 warnings.

Synthetic ASS and SRT playback tests passed on a OnePlus PKG110 running Android 16 with the isolated QA build. The final signed APK was checked for upgrade compatibility and was not installed on the phone, following the project's earlier formal delivery instruction.

[Download from Haoke Tools](https://haoke.org/software/10cdc544f64e9b91). This repository and its version tag provide corresponding source. The fixed cloud-drive link is maintained by the site owner.
