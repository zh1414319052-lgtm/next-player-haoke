# Next Player 字幕修复 QA

2026-11-11 · 0.19.0-haoke.10-QA-subtitles.2（85）· ARM64 · dev.anilbeesetti.nextplayer.qafeedback

- 同一位置同时显示的文字字幕按原顺序分行，保留每段颜色和字号。ASS 定位和字号使用可见视频范围；独立定位的标牌保持在原位，未指定位置的普通字幕保持原有排版。
- 手动外挂字幕优先用文件显示名判断格式，兼容大写扩展名、无扩展名的文档 URI 和 Android 把 ASS 标为 audio/aac 的情况。加载后按实际轨道身份选中，兼容 Media3 合并音频和字幕时添加的轨道编号。保留播放状态、进度和已有字幕设置。
- 同名字幕按真实视频路径查找，播放历史里的 URI 不再当作文件路径。兼容紧凑 ASS 文件头，避免 Media3 1.11.1 漏读段落最后一项。Media3 按最后一个字幕样本估算外挂轨道的时长。ASS/SSA 在最后一条字幕的实际结束时间补发空字幕样本，确保拖进度或续播时仍能读取尚在显示的末句。原字幕文件保持原样。

验证：一加 PKG110 / Android 16（API 36），使用 scripts/subtitle_playback_fixture.py 生成无烧录字幕的视频、标准及紧凑 ASS 和 SRT。核对同名大写 ASS 自动显示、带播放历史重开、系统文件选择器选入 ASS、停用字幕且关闭记住选择时添加后自动选中、暂停位置保留、重复选同一文件不增轨、切换 SRT、记住轨道后重开、画面内三种颜色分区。4 项真机用例通过，包含从前段跨过空档拖到 14 秒、拖回第一句、拖过末句后清空、重开后在末句处续播，以及手动加载紧凑 ASS 后跳转。outputs 中的字幕跳转真机验证图记录了实际拖动进度条后在 00:14 显示的中日两行。验证素材为自制字幕。

本地 9 项字幕、字幕重载、SRT 和外部音轨相关回归通过。QA 构建、签名、16 KB 对齐通过；lint 0 错误、17 条警告。交付 APK SHA-256：a0bdeb780497e31d5a3ffb8c2617ae48ec8fd52c4be981ee08514f7c1df62057。测试包和手机测试文件均已清理并回查，保留正式应用。此轮是独立 QA。

参考：[Next Player 上游的 Android ASS 分类反馈](https://github.com/anilbeesetti/nextplayer/issues/1256)、[竖屏画幅讨论](https://github.com/anilbeesetti/nextplayer/pull/1861)、[Media3 SSA 解析器](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/text/ssa/SsaParser.java)、[Media3 渐进式字幕源的时长估算](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/ProgressiveMediaPeriod.java)。内置字体 Noto Sans SC，SIL OFL 1.1，可商用。
