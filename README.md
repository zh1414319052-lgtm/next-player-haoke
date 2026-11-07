# 下一代播放器 / Next Player · 浩科优化版

本仓库提供 **0.19.0-haoke.9（versionCode 84）** 正式版对应的修改源码。基于 [Anil Beesetti / Next Player](https://github.com/anilbeesetti/nextplayer) 0.19.0，保留 GPL-3.0、原作者与第三方归属；这是独立维护的浩科优化版，未经上游作者背书。上游说明保留于 [README.upstream.md](README.upstream.md)。

**正式版下载：[浩科工具站](https://haoke.org/software/10cdc544f64e9b91)** · [本版更新说明](https://haoke.org/software/10cdc544f64e9b91/releases/nextplayer-haoke-0190-9)

GitHub 用于源码维护，不提供安装包托管。网站现有安装包入口由站长维护。

## 本版更新

- 修复方向按钮只能切换一次：配置尚未追上点击时，也能根据最近请求继续横竖切换。
- 新增“截取单帧”：从视频解码保存 PNG，不含播放控件、可选择的内封字幕或外挂字幕；保留原始像素至长边 3840，超出时等比例缩小。HDR 图片转换为 BT.709 SDR。
- 修复手机控制栏自动隐藏后遥控按键丢失，恢复唤出后的焦点；拦截的按下、松开及重复事件配对处理。统一触屏、确认键、无障碍点击及长按行为，兼顾锁定和投屏状态。

Android 10+ 将图片保存至相册 Pictures/Next Player；旧系统通过文档选择器保存。截图保持播放状态和位置，独立解码与网络会话有超时、取消和释放保护。画面内已烧录的字幕无法去除；DRM、直播、不可定位或设备不能解码的来源不保证可导出。

实现和验证范围见 [PLAYER-FEEDBACK-FIX.md](docs/PLAYER-FEEDBACK-FIX.md)。

## 保留的功能

- 石墨黑与青绿色影院界面，本地及网络视频、文件夹浏览、字幕/音轨、倍速、手势、画中画和后台音频。
- FTP 传输中断时，在文件长度可确认、服务器支持 REST 等条件下，从实际已读位置续传。
- 自定义片头、片尾跳过分别 0–1800 秒，0 为不跳过；保存后开启“自定义跳过片头片尾”。关闭保留时长，自定义优先于相应端自动识别；直播、过短正文、循环与手动回看有保护。
- 可选 SDR GPU 运动补偿插帧，支持固定 60 帧与两倍模式。适用于硬件解码、1 倍速、12–30 fps、最高 3840×2160；效果和性能因设备/片源而异，复杂运动仍可能有伪影，不保证锁定 60 帧。
- HDR 保留系统原生色彩输出，暂不支持实时插帧；SDR 插帧保留。HDR 解码依赖系统与硬件，见 [既有 HDR 修复](docs/HDR-FIX.md)。
- 既有 DLNA 投屏、签名版本更新检查及关于页开源许可。DLNA 兼容性取决于电视与局域网。

新安装默认深色影院主题；插帧和跳过默认关闭。升级保留已有主题、设置及播放记录。

## 正式包身份

| 项目 | 值 |
| --- | --- |
| 版本 | 0.19.0-haoke.9 / 84 |
| Application ID | dev.anilbeesetti.nextplayer.haoke |
| 系统与架构 | Android 7.0+ / minSdk 24 / arm64-v8a |
| APK 字节数 | 33135947 |
| APK SHA-256 | bc2e5bd75ed81f75957a63d38f8a8647d15f22f722e113eedca77e83a2793766 |
| 正式证书 SHA-256 | d27867cecea4c6e041195cc87501c4663cefa6a6c6240a0fca835998a87c09f9 |

沿用正式包名和签名，可覆盖旧浩科正式版；上游和 QA 包使用独立身份及数据。自行构建不含正式私钥，请使用独立 debug/QA 包名测试。

## 源码与构建

Kotlin / Jetpack Compose 多模块项目，源码位于 app、core、feature。使用 JDK 21（JVM target 17）、Android SDK 37 与本仓库 Gradle wrapper。Windows 推荐 ASCII 检出目录；在不提交的 local.properties 配置 sdk.dir。

```powershell
.\gradlew.bat -PhaokeTestBuildType=release :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease :feature:player:testDebugUnitTest :core:common:testDebugUnitTest :core:decoder:testDebugUnitTest --console=plain
```

ARM64 未签名产物为 app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk。Linux/macOS 使用 ./gradlew。依赖需要可用的下载环境。

单帧提取使用与现有播放器一致的 Media3 1.11.1 inspector-frame。默认解码扩展使用 [core/decoder](core/decoder/README.md)，基于 Nextlib 1.11.1-0.16.0 的固定源码修正 HDR 能力声明，并从 SHA-256 固定的上游 AAR 提取未经修改的原生库。其源码、JNI 构建材料与许可来自 [固定提交](https://github.com/anilbeesetti/nextlib/tree/2dc77cd2d9352bc308aa26782f9c77a320df510b)，本版五个 FFmpeg 库与 .8 正式包逐字节一致。

scripts/sign_release.py 只供维护者读取源码外的既有受保护签名身份；仓库不含正式密钥、密码或本机凭据。app/debug.keystore 是上游已公开的调试身份，仅用于 QA/debug。

## 验证范围

- 194 项相关自动测试全部通过：播放器 127、应用 63、公共层 2、解码能力 2。Release Lint：0 错误、38 条警告。
- 最终正式签名 / R8 包在一加 PKG110、Android 16 通过：暂停和播放各四次横竖切换；网络播放中导出干净单帧；隐藏后遥控快进、后退、唤出、确认暂停和确认续播；语言资源与真实签名更新 feed 检查。
- 83→84 原签名覆盖升级后，设置和播放记录指纹一致，首次安装时间、数据目录保持不变。
- 遥控测试使用物理手机原生按键事件注入；没有验证实际蓝牙配对、具体品牌遥控器或电视机。网络素材使用专用 HTTP 合成测试服务器及 USB 转发，实际局域网协议组合仍依赖用户环境。
- 本轮系统语言未修改；复用首选 zh → 简体中文，en 和其他 → 英文，并在真机隔离资源上下文验证变体。旧版本 HDR 多格式验收属于 .8 的记录，不作为本轮重复完成的声明。

## 更新通知、隐私与许可

沿用本产品 stable 签名版本通知，在安全的前台空闲页面或手动检查时验证身份、签名、版本、时效和兼容性；播放及后台状态取消检查。服务会接收常规请求/IP 元数据，不上传视频内容、媒体库列表或设备标识。没有新增遥测、后台轮询或旧远程弹窗。

沿用 [GNU GPL v3.0](LICENSE)，分发修改安装包时须保留对应源码和许可。原作者、贡献者与第三方归属保留于源码、上游说明及应用内许可页。Noto Sans SC 使用 [SIL OFL 1.1](core/ui/src/main/assets/licenses/Noto-Sans-SC-OFL.txt)，允许商用和再分发。

## English

Corresponding modified source for Next Player Haoke **0.19.0-haoke.9 (84)**, based on Anil Beesetti’s Next Player 0.19.0 under GPL-3.0. This independent edition retains the upstream attribution and licenses.

This release fixes repeated screen rotation and hardware remote input after playback controls disappear. It adds clean decoded PNG frame export without controls or selectable subtitles, preserving source pixels up to a 3840-pixel long edge. HDR exports are converted to BT.709 SDR. Burned-in subtitles cannot be removed; DRM, non-seekable streams and unsupported decoders are not guaranteed. Android 10+ uses Pictures/Next Player; older versions use the document picker.

The established package/signing identity supports in-place upgrades preserving settings and history. SDR interpolation, FTP resume, configurable intro/outro skipping, DLNA casting and native HDR playback remain. HDR playback does not support interpolation; performance and hardware compatibility vary.

194 relevant automated tests passed; Release Lint reported 0 errors and 38 warnings. The signed/R8 package passed repeated rotation, network frame export, hidden-control remote pause/resume, localized resources and its real signed feed on OnePlus PKG110 / Android 16. The 83-to-84 upgrade preserved settings/history fingerprints and the data directory. Native key injection is not a paired Bluetooth remote test; physical remote brands, TVs and other devices remain untested.

Downloads: **[Haoke Tools](https://haoke.org/software/10cdc544f64e9b91)**. Release keys, credentials, local SDK configuration and personal media are not included.
