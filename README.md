# 下一代播放器 / Next Player · 浩科优化版

**0.19.0-haoke.12（87）**，基于 [Anil Beesetti / Next Player](https://github.com/anilbeesetti/nextplayer) 0.19.0 的独立维护版本，保留 GPL-3.0、原作者和第三方归属；未经上游作者背书。上游说明见 [README.upstream.md](README.upstream.md)。

**[正式版下载：浩科工具站](https://haoke.org/software/10cdc544f64e9b91)** · [本版更新说明](https://haoke.org/software/10cdc544f64e9b91/releases/nextplayer-haoke-0190-12)

GitHub 提供对应源码，不托管 APK。网站的固定网盘入口由站长维护。

## 本版修复

修复局域网投屏设备重复广播导致设备列表闪退的问题。自动发现与手动添加统一按设备身份合并，刷新时保留手动添加的设备；限制并发描述请求，避免旧结果覆盖新结果。修复关闭投屏列表时取消扫描的套接字异常，并完善扫描失败和手动探测失败后的重试。实现和测试边界见 [投屏修复记录](docs/CAST-FIX.md)。

## 保留的功能

- 保留 .11 的中日双语字幕分行、外挂 ASS/SSA 自动和手动加载、拖动后恢复当前字幕。
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
| 版本 | 0.19.0-haoke.12 / 87 |
| Application ID | dev.anilbeesetti.nextplayer.haoke |
| 系统 / 架构 | Android 7.0+，minSdk 24，arm64-v8a |
| APK 字节数 | 33152331 |
| APK SHA-256 | 4d50bc0b1b22597d86489051f6062e3f88ad456b97c1e3f197807af9d8c31ab5 |
| 正式证书 SHA-256 | d27867cecea4c6e041195cc87501c4663cefa6a6c6240a0fca835998a87c09f9 |

沿用原正式签名，可覆盖已有浩科正式版；上游版和 QA 版使用独立身份及数据。正式包非 debuggable / 非 testOnly，签名与 16 KB ZIP 对齐通过。13 项权限与 .11 一致；原生库对比见下方验证结果。

## 构建与验证

Kotlin / Jetpack Compose 多模块工程。实际验证使用 JDK 21、JVM target 17、Android SDK 37 和 Gradle wrapper。Windows 推荐 ASCII 构建路径；SDK 路径放在不提交的 local.properties。

~~~powershell
.\gradlew.bat --max-workers=1 --no-parallel -PhaokeTestBuildType=release :app:assembleRelease :app:lintRelease :app:testReleaseUnitTest --console=plain
~~~

Linux/macOS 使用 ./gradlew。ARM64 未签名包位于 app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk；自行测试可构建 :app:assembleQaFeedback，使用独立 QA 包名。正式签名脚本 [scripts/sign_release.py](scripts/sign_release.py) 只读取维护者在源码外的受保护身份；仓库不含正式私钥或凭据。

- 本次投屏修复通过 155 项相关单元测试（投屏 18、播放器 135、系统首选语言 2），随后正式 release 变体 63 项应用测试通过，包括更新验签及 UI 生命周期测试。
- Release Lint 0 错误、38 条警告。R8 正式构建、正式证书和 16 KB ZIP 对齐通过。权限与 .11 相同；10 个原生库逐字节一致。
- 一加 PKG110 / Android 16 上最终隔离 QA 包的 6 项设备测试通过，覆盖重复设备、手动添加/刷新、失败重试、兼容性确认、实际 SSDP 扫描取消与重新打开、模拟 DLNA 接收端的 HTTP/SOAP/媒体 Range 请求及语言资源。测试包与辅助组件已卸载。
- 用户的 Redmi 23054RA19C / Android 14 和实际电视未实测；模拟接收端不代表电视解码兼容性。网络发现和播放能力仍依赖路由器、电视及片源。
- 正式 APK 已核对覆盖升级所需的包名、签名证书及版本递增。按项目既有正式交付要求，未在真机安装最终 APK，未声称完成本版实体覆盖升级。
- 系统首选语言 zh → 简体中文、en/其他 → 英文。测试与构建命令见 [CAST-FIX.md](docs/CAST-FIX.md)。

单帧导出使用 Media3 1.11.1 inspector-frame。解码扩展来源和构建材料见 [core/decoder/README.md](core/decoder/README.md)，固定 Nextlib 源码为 [2dc77cd2d9352bc308aa26782f9c77a320df510b](https://github.com/anilbeesetti/nextlib/tree/2dc77cd2d9352bc308aa26782f9c77a320df510b)。本版未新增依赖或改变原生库。

## 更新与许可

沿用 Android/stable 的签名版本通知：验证身份、签名、时效、版本及兼容性；只在安全前台页面自动检查，并保留关于页手动检查。没有新增遥测、后台轮询、视频上传或旧远程弹窗。网站会接收常规请求/IP 元数据。

[GNU GPL v3.0](LICENSE) 及原作者、贡献者、第三方归属保留；分发修改安装包时需提供对应源码和许可。[Noto Sans SC](core/ui/src/main/assets/licenses/Noto-Sans-SC-OFL.txt) 使用 SIL OFL 1.1，可商用和再分发。

## English

Next Player Haoke **0.19.0-haoke.12 (87)** is an independently maintained GPL-3.0 edition based on Anil Beesetti's Next Player 0.19.0. This release fixes cast-device sheet crashes caused by repeated renderer identities, shares deduplication between automatic discovery and manual additions, preserves manual devices during refresh, and handles scan cancellation and retry. Description requests are limited to four concurrent loads.

It retains the .11 bilingual/external subtitle fixes, native HDR playback, optional SDR interpolation, clean decoded PNG frame export, remote input, FTP resume and configurable intro/outro skipping. Burned-in subtitles cannot be removed. Interpolation performance and hardware compatibility vary.

The ARM64 release requires Android 7.0+. Its package and signing certificate match the established formal edition; versionCode increases to 87. APK size is 33152331 bytes and SHA-256 is `4d50bc0b1b22597d86489051f6062e3f88ad456b97c1e3f197807af9d8c31ab5`. The repair passed 155 targeted unit tests and six isolated device tests; the formal release variant passed 63 application tests. Release Lint: 0 errors, 38 warnings.

Device tests used a OnePlus PKG110 / Android 16 and a local synthetic renderer with actual HTTP/SOAP and byte-range requests. The reporter's Redmi / Android 14 and real TV were not tested. The final signed APK was verified for upgrade prerequisites but was not installed on the phone, following the project's earlier instruction. See [casting repair details](docs/CAST-FIX.md).

[Download from Haoke Tools](https://haoke.org/software/10cdc544f64e9b91). This repository and its version tag provide corresponding source. The existing cloud-drive entry is maintained by the site owner; source publication does not claim that the cloud-drive binary was uploaded or verified.
