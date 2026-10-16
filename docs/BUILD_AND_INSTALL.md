# 云端编译与手机安装

本阶段先验证“GitHub 云端编译 → 下载 APK → 手机上安装启动”。手机应用名为 **本地账本·预览**，应用 ID 为 `io.github.xiaochuan45.autoledger.preview`，版本为 `0.1.0-preview`（版本号 1）。这是独立的安装预览，尚未实现账单保存、自动识别、导入或备份功能。

## 当前访问状态（2026-10-16）

仓库写入授权已恢复：用户安装官方 [ChatGPT Codex Connector](https://github.com/apps/chatgpt-codex-connector) 并选择 All repositories，API 确认内容及工作流写入权限；实际 `git push` 已成功将 `main` 推进到 [`f31ff887521ee718d52b1f463874b1414c8c665a`](https://github.com/xiaochuan45/auto-ledger-android/commit/f31ff887521ee718d52b1f463874b1414c8c665a)。此前实际写入返回 403 的阻断已解决，排障历史保留在 [第一阶段记录](PHASE1_STATUS.md)。

当前云端开发环境的编译、Lint、实际 APK 权限和签名审计已通过。[首轮 GitHub Actions](https://github.com/xiaochuan45/auto-ledger-android/actions/runs/38843736161) 已触发且正在运行；模拟器结果尚未确认，APK 尚未发布，下载及手机安装启动仍待验证。

## 云端构建方式

GitHub Actions 的 [Android preview 工作流](../.github/workflows/android.yml) 在推送 `main`、创建 PR 和手动触发时运行。它执行：

1. 准备 Temurin JDK 17、Android SDK 35、Build Tools 35.0.0 与仓库 Gradle Wrapper。
2. 编译 Debug APK，并执行 Android lint。
3. 对实际 APK 检查 manifest、网络权限、导出组件、备份规则和签名，生成 SHA-256。
4. 在 Android 15 / API 35 的 x86_64 模拟器上安装并执行启动和导航测试，再核对交付 APK 与测试构建一致。
5. 检查全部通过后上传 Actions artifact；仅 `main` 的非 PR 构建会发布独立的 GitHub prerelease。

本轮不依赖个人电脑。仓库依赖下载和编译发生在 GitHub 托管 runner；安装到手机后的应用不联网。第三方 Actions 均固定到提交 SHA。PR 只有读取仓库权限；仅发布 job 有 `contents: write`。

当前工作流正在执行，不能把源码已推送或云端开发环境编译通过等同于 Actions 全部检查、模拟器测试或发布通过。实际状态应查看本次 Actions run 和发布记录。

## APK 下载

优先打开仓库的 [Releases 页面](https://github.com/xiaochuan45/auto-ledger-android/releases)，选择最新的“本地账本 · 安装预览”预发布版本，在 Assets 中下载 **`auto-ledger-preview.apk`**。同一版本提供 `auto-ledger-preview.apk.sha256` 和 `permissions-audit.md`。不要下载 GitHub 自动生成的 Source code 压缩包来安装。

每次发布使用独立标签 `preview-<run id>-<run attempt>`，不会覆盖旧 APK。Release 页面包含源码提交及云端验证记录。预发布版本可能不会显示在 GitHub 的 `/releases/latest`，请使用完整 Releases 页面。

若 Release 尚未出现，先查看 [Actions](https://github.com/xiaochuan45/auto-ledger-android/actions) 中对应运行。验证成功的运行会提供 `preview-apk-...` artifact（需登录 GitHub，保存 30 天）；下载 ZIP 后解压得到 APK。没有 artifact 或 Release 表示尚无可交付文件，不能把源码当作安装包。

## 一加手机安装与检查

1. 用手机浏览器下载 APK。系统询问时，只为本次使用的浏览器或文件管理器开启“允许安装未知应用”。ColorOS 的具体入口和名称以实机为准，通常可从系统安装提示进入。
2. 点击 APK 安装，核对名称“本地账本·预览”，启动后查看预览说明。
3. 切换“账单”和“统计”，打开设置说明，返回主界面；关闭后再次启动。
4. 本阶段不需要授予通知读取、无障碍或其他运行时权限。系统可能显示未知来源或测试应用提示；如出现无法安装、包冲突或安全校验失败，请记录提示原文，不要跳过系统安全检查。
5. 测试结束后可关闭浏览器或文件管理器的“允许安装未知应用”。

请反馈：手机型号、Android 版本、ColorOS 版本、下载和安装是否成功、启动与切页是否正常、错误提示原文。不要提供真实账单、验证码或聊天截图。Android/ColorOS 版本可在“设置 → 关于本机”中查看。

## 签名、更新与数据边界

本阶段使用 GitHub runner 生成的临时 Debug 签名，APK 可安装但不作为日常记账版本。不同构建的签名可能变化，因此可能无法直接覆盖安装，需要卸载旧预览后重装。**仅在确认安装的是这个无账单功能的预览应用时使用卸载重装。后续保存真实数据的版本不得用这种办法处理升级失败。**

正式版开始保存账目前，应确定稳定的应用 ID、私密签名及安全保管方式，再验证覆盖升级保留数据。官方应用的授权范围不包括 Secrets 管理或仓库 administration 权限；此前相应管理接口返回 403，与已经恢复的内容及工作流写入权限分开处理。这个限制不影响本轮临时签名预览。需要配置正式签名时，再提供具体的仓库设置步骤；不要把 keystore 或密码提交到公开仓库。

应用声明 `allowBackup=false`，关闭旧版全量备份，并对 Android 12+ 的云备份与设备迁移规则明确排除所有数据域。实际 APK 审计还检查不存在 `INTERNET`、`ACCESS_NETWORK_STATE` 或其他系统敏感权限。AndroidX 如需要自身 signature 级内部接收器权限，会在审计中单独列出；它不提供网络能力。正式版的本地加密备份与恢复功能尚未实现。

## 开发者复现

云端环境：JDK 17、Android SDK 35、Build Tools 35.0.0、Python 3，Gradle 由仓库 Wrapper 固定。常用命令：

```sh
./gradlew --no-daemon assembleDebug lintDebug
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/auto-ledger-preview.apk
python3 scripts/verify_apk.py --apk dist/auto-ledger-preview.apk --output-dir dist
```

连接 API 35 测试模拟器后执行：

```sh
./gradlew --no-daemon connectedDebugAndroidTest
cmp app/build/outputs/apk/debug/app-debug.apk dist/auto-ledger-preview.apk
cd dist
sha256sum --check auto-ledger-preview.apk.sha256
```

审计输出包含真实 APK、SHA-256、`permissions-audit.md`、已解码 manifest、打包的备份规则及签名报告。Actions 额外保存 lint 与 instrumentation 报告。模拟器通过只能证明对应自动化测试通过，不能代替一加手机安装、支付识别和后台稳定性实测。
