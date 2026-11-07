# 播放反馈修复：0.19.0-haoke.9

本次正式版本为 84，沿用 dev.anilbeesetti.nextplayer.haoke、既有正式证书和 stable 更新信任身份。没有权限、存储格式或用户数据迁移。

## 行为变化

- RotationState 根据最后请求的方向轴切换，覆盖传感器、正反向横竖屏，以及资源配置未追上点击的情况。
- PlayerActivity 提供可释放且跟随最新状态的按键入口；MediaPlayerControls 在原生键盘模式下恢复 Compose 和目标按钮焦点，隐藏后保留稳定根焦点。PlayerKeyInputState 消费完整的已处理按键序列，避免松开/重复事件落到新显示按钮；锁定及投屏分别沿各自状态处理。PlayerButton 使用框架 combinedClickable，使触屏、原生确认键、无障碍动作、长按、禁用与最新回调一致。
- VideoFrameCapture 使用 Media3 1.11.1 inspector-frame，固定点击时的视频与播放位置；解码单帧时清空可选择字幕配置。网络提取使用独立会话，不关闭或定位正在播放的连接。
- FrameImageStore 在 Android 10+ 用本次创建的待发布 MediaStore 行保存 PNG，写完后发布；失败只回收自己的待发布行。旧系统使用文档选择器，不新增存储权限。重复点击、退出、超时及资源释放有界处理。文件名不接受媒体标题或网络地址。

最大长边 3840，超出等比例缩小；HDR 导出转换为 BT.709 SDR。播放器控制栏及可选择字幕不进入帧图，已烧录字幕不能去除。DRM、不可定位/直播和不支持的解码来源不保证可提取，且不绕过保护。

## 验证

- 自动回归 194 项通过，Release Lint 0 错误、38 条警告。
- 物理 OnePlus PKG110 / Android 16，最终正式签名 R8 包：八次暂停/播放横竖切换通过；网络播放中导出 1920×1080 干净 PNG 并保持播放；自动隐藏后快进、后退、确认唤出、暂停、续播通过。
- 同一正式包还通过本地暂停截图、HLG HDR 图片导出、语言资源上下文及真实 stable feed 检查。
- 83→84 原签名覆盖升级，设置及播放记录指纹一致，首次安装时间和数据目录保持不变。
- 正式包 SHA-256：bc2e5bd75ed81f75957a63d38f8a8647d15f22f722e113eedca77e83a2793766；33135947 字节。手机实际安装包与交付包一致。
- 签名及 16 KB ZIP 对齐通过；不是 debuggable 或 testOnly 安装包。正式包权限声明与 .8 一致，五个 FFmpeg 库逐字节一致。本机音频指纹库本轮重新编译，二进制哈希不同，不声明所有 native 二进制相同。

遥控采用真机原生按键事件注入；蓝牙配对、具体品牌遥控器和电视未测。网络使用专用 HTTP 合成素材及 USB 转发，未把这些结果当作真实局域网全协议兼容证明。系统语言未修改，采用隔离资源上下文验证首选语言映射。既有 HDR 多格式验证见 [HDR-FIX.md](HDR-FIX.md)；本版未重复宣称 .8 的全部组合测试。

安全检查为 FOCUSED，仅覆盖本次 URI/导出行归属、异步资源、正式身份与更新交接、公开源码敏感信息；不作全仓安全审计声明。

## 对应源码

构建输入随本版本提供，新增播放器和设备回归测试一并保留。Next Player 的 GPL-3.0、Anil Beesetti 及第三方归属保持；Nextlib 固定版本、JNI 源码及 recipe/许可入口见 [core/decoder](../core/decoder/README.md)。沿用 Noto Sans SC（SIL OFL 1.1，可商用）。

[正式版本说明](https://haoke.org/software/10cdc544f64e9b91/releases/nextplayer-haoke-0190-9) · [源码标签](https://github.com/zh1414319052-lgtm/next-player-haoke/tree/haoke-0.19.0-haoke.9)。网站既有安装包下载入口由站长维护，源码发布不表示代传或已核验网盘文件。
