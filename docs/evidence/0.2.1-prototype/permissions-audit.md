# 自动识别小样 APK 审计

此报告检查指定 APK 的打包内容；调试构建与未签名构建不能作为正式交付。

| 项目 | 结果 |
| --- | --- |
| 文件 | `auto-ledger-prototype.apk` |
| 应用 ID | `io.github.xiaochuan45.autoledger` |
| 版本 | `0.2.1-prototype` / `3` |
| Android 范围 | minSdk 26 / targetSdk 35 |
| 请求的权限 | io.github.xiaochuan45.autoledger.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION |
| 网络与其余系统敏感 uses-permission | 无；包括 INTERNET、ACCESS_NETWORK_STATE |
| 内部权限例外 | 如存在，仅允许应用自身定义的 signature 级接收器权限 |
| 备份 | allowBackup=false；旧版关闭或排除全部域；云备份和设备迁移九个域全部排除 |
| 对外 Activity | 仅启动 Activity：`io.github.xiaochuan45.autoledger.MainActivity` |
| 对外服务 | 仅通知监听和无障碍；分别受系统 BIND_* 权限保护 |
| 广播接收器 | 无 |
| 无障碍范围 | 仅微信、支付宝；仅窗口变化和内容变化；默认 flags；无截图、手势或全部窗口访问 |
| AndroidX 初始化 Provider | 1 个；如存在，必须不可导出 |
| 签名证书 SHA-256 | `0e2a128391be8ae3d39140ed5d2cd7de8256d62b19233b3593d9ef97180b0073` |
| 构建类型 | `release`；debuggable=false |
| APK SHA-256 | `c50a327439792f8669a1be670f9f067794483cb86ab3399ab54f414e86d26862` |

本报告不代表支付识别、后台运行或用户手机真机验证通过。识别规则仍需逐场景真机适配。
