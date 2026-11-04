# 下一代播放器 / Next Player · 浩科优化版

`next-player-haoke` 保存 **0.19.0-haoke.7（versionCode 82）** 正式版对应的修改源码。
基于 [Anil Beesetti / Next Player](https://github.com/anilbeesetti/nextplayer) 0.19.0，保留 GPL-3.0 和原作者归属。
这是独立维护的浩科优化版，未经上游作者背书；上游介绍保留于 [README.upstream.md](README.upstream.md)。

**正式版下载：[浩科工具站 https://haoke.org](https://haoke.org/software/10cdc544f64e9b91)**

GitHub 用于源码维护；正式安装包请到上述网站查找“下一代播放器”。

## 本版改动

- 修复局域网 FTP 视频提前结束。读取中断时校验剩余数据，通过 FTP REST 从实际断点续读。
- 石墨黑与青绿色影院界面，更新媒体库、续播卡片、更多、设置和播放控制栏。
- 改善运动补偿插帧的边缘锯齿和 4K 分析缩放抗混叠，保留固定 60 帧与两倍模式。
- 自定义片头、片尾跳过时长，各为 0–1800 整数秒；0 为不跳过该部分。支持保存、取消、关闭保留时长、片头撤销与手动回看。
- 保留自动识别、循环和播放结束保护、音轨/字幕、网络播放及既有 DLNA 投屏能力。
- 复用签名版本通知，关于页提供手动检查和开源许可入口。

新安装默认深色影院主题；插帧和跳过默认关闭。升级保留已保存的主题与功能开关。
插帧适用于硬件解码、1 倍速、12–30 fps、最高 3840×2160；效果和性能受 GPU 与片源影响，复杂运动仍可能有伪影，不保证全程锁定 60 帧。
DLNA 兼容性取决于电视和局域网。

设置跳过：**更多 → 设置 → 播放器 → 设置跳过时长**，保存后开启“自定义跳过片头片尾”。
设置对所有视频生效；自定义正数时长优先于相应端自动识别。重叠或正文不足、直播及不可拖动内容受保护规则限制，循环模式保留片尾。

## 正式包身份

| 项目 | 值 |
| --- | --- |
| 版本 | `0.19.0-haoke.7` / `82` |
| Application ID | `dev.anilbeesetti.nextplayer.haoke` |
| 已交付架构 | `arm64-v8a` |
| 系统要求 | Android 7.0+ / minSdk 24 |
| 正式 APK 字节数 | `33127642` |
| 正式 APK SHA-256 | `053a37e7def5357a4717d84a270f7a8317431c12caea7478d42a69f5bc4bce1b` |
| 正式证书 SHA-256 | `d27867cecea4c6e041195cc87501c4663cefa6a6c6240a0fca835998a87c09f9` |

正式版可同签名覆盖旧浩科正式版；上游版、QA 版使用不同包名和数据。
自行构建不包含浩科正式私钥，不能用其他签名覆盖已安装的浩科正式版；请使用独立 debug/QA 包名测试。

## 源码与构建

项目采用 Kotlin / Jetpack Compose，多模块源码位于 `app/`、`core/` 与 `feature/`。
正式编译使用 JDK 21（JVM target 17）、Android SDK 37 及仓库 Gradle wrapper。
Windows 请使用 ASCII 检出路径，避免构建工具对中文路径的兼容问题。
在本机创建不提交的 `local.properties`，设置 `sdk.dir` 指向 Android SDK。

```powershell
.\gradlew.bat -PhaokeTestBuildType=release :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease ktlintCheck --continue --console=plain
```

ARM64 未签名产物为 `app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk`。
构建固定依赖 NextLib 1.11.1-0.16.0，公开源码与构建脚本见 [对应提交](https://github.com/anilbeesetti/nextlib/tree/2dc77cd2d9352bc308aa26782f9c77a320df510b)。

Linux/macOS 使用 `./gradlew`；依赖需要正常下载环境。
`scripts/sign_release.py` 供维护者读取既有源码外受保护签名身份；仓库不提供私钥、密码或本机凭据，普通贡献者无需正式签名身份。

`app/debug.keystore` 为上游仓库原已公开的 Android 调试用身份，原样保留，仅用于 debug/QA，不是浩科正式签名。

## 验证范围

- 本次版本的媒体模块和正式包单元测试共 156 项通过，Release Lint 0 错误、18 条警告。
- 实际同签覆盖升级验证了设置和测试播放记录保留。FTP 断流复测使用合成视频和测试服务器。
- 设计基准见 [docs/design/CINEMA-UI.md](docs/design/CINEMA-UI.md)，版本身份见 [docs/HAOKE_HANDOFF.json](docs/HAOKE_HANDOFF.json)。

## 更新通知与隐私

沿用 `dev.anilbeesetti.nextplayer.haoke / stable` 签名版本通知。
客户端在前台空闲媒体库或用户手动检查时验证签名、身份、有效期、版本和兼容性；播放及后台状态会取消检查。
服务会收到常规请求/IP 元数据，不上传视频内容、媒体库列表或设备标识。本版没有新增遥测、后台轮询或远程弹窗。

## 许可与归属

沿用 [GNU GPL v3.0](LICENSE)。分发修改安装包时须满足对应源码及许可保留要求。
原作者、贡献者和第三方库归属保留于源码、上游说明和应用内许可页。
Noto Sans SC 字体使用 [SIL OFL 1.1](core/ui/src/main/assets/licenses/Noto-Sans-SC-OFL.txt)，允许商用和再分发。

## English

Corresponding modified source for Next Player Haoke edition **0.19.0-haoke.7 (82)**, based on Anil Beesetti's Next Player 0.19.0, under GPL-3.0.
Repairs premature end of LAN FTP playback by validating the transfer and resuming at the interrupted byte offset.
Downloads: **[https://haoke.org](https://haoke.org/software/10cdc544f64e9b91)**.
The upstream project and this independent edition use separate application/signing identities. Release keys, credentials, local SDK paths and user media are not included.
