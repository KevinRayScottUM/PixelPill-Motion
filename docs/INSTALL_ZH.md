# 安装与排错

## 安装

1. 安装 APK。
2. 在 Vector 或 LSPosed 的模块页启用 **PixelPill Motion**。
3. 勾选 **系统界面 / System UI (`com.android.systemui`)**。Pixel Fold 还必须勾选 **Pixel Launcher (`com.google.android.apps.nexuslauncher`)**，因为展开状态/收起任务栏的白条由 Launcher 绘制。
4. 首次启用模块后完整重启手机；以后修改设置时，可以点击模块内的 **Restart UI services · Apply now**，授权 Root 后同时刷新 SystemUI 与 Pixel Launcher。
5. 打开模块设置。默认 AOSP-like 参数为：按下宽度 76%、按下 120 ms、回弹 190 ms、overshoot 8%、轻触觉。
6. 先测试普通点按，再长按确认 Circle to Search 正常出现。

## 安全恢复

如果 SystemUI 异常，进入 Vector/LSPosed 禁用本模块并重启。模块不修改系统分区，所以禁用后即可完全退出 Hook。

## 收集兼容日志

在框架日志中搜索 `PixelPillMotion`。提交 issue 时请附上：Pixel 型号、系统 build 号、Android 版本、Vector/LSPosed 版本、折叠/展开状态，以及相关日志。不要提交包含账号、通知内容或其他隐私信息的完整日志。

## 状态说明

桌面构建环境只能验证编译、Lint、Manifest、APK 签名与资源结构，无法代替真实 Pixel 上的 SystemUI 私有 API 验证。Android 17 OTA 后如果类名发生变化，模块会安全跳过失效 Hook，并通过日志说明探测结果。
