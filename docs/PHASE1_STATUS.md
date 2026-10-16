# 第一阶段执行记录

记录日期：2026-10-16。此文件记录实际结果，不将规划项视为已完成。

## 已检查

- 仓库读写已恢复。用户安装官方 [ChatGPT Codex Connector](https://github.com/apps/chatgpt-codex-connector)，选择 All repositories；应用权限 API 确认 `contents: write` 和 `workflows: write`。实际 `git push` 已将 `main` 从 `c10bd0e` 推进到 [`f31ff887521ee718d52b1f463874b1414c8c665a`](https://github.com/xiaochuan45/auto-ledger-android/commit/f31ff887521ee718d52b1f463874b1414c8c665a)。
- 云端环境已运行，HTTP 网络策略为 unrestricted/enforced。
- 初始环境只有 Git、GitHub CLI 与 Java 21 运行时。现已在当前云端安装 Temurin JDK 17.0.20.1、Android SDK 35、Build Tools 35.0.0、Platform Tools，并验证 Gradle 8.11.1 可运行。
- GitHub CLI 已登录，可读取仓库及 Actions 工作流列表。
- 官方应用的授权范围不包括 Secrets 管理或仓库 administration 权限；此前相应管理 API 返回 403。此限制不影响本轮临时签名预览，实际构建和发布结果分别验证。
- 推送已触发 [GitHub Actions run 38843736161](https://github.com/xiaochuan45/auto-ledger-android/actions/runs/38843736161)，当前运行中，尚未确认模拟器测试或发布成功。

## 本轮准备

- 需求、分阶段规划、测试矩阵和协作约定已提交并推送到 GitHub。
- 最小 Kotlin / Compose 工程及构建配置已提交并推送。提供账单、统计及设置基础页面，不采集或保存账单。
- 使用官方 Gradle 8.11.1 Wrapper，已核对 Wrapper JAR 官方 SHA-256，并固定发行包 SHA-256。
- 安装验证版包名 `io.github.xiaochuan45.autoledger.preview`，版本 `0.1.0-preview`，最低 Android 8.0（API 26）。
- 使用开发调试签名，只做安装验证；正式账本启用前必须配置稳定私密签名。此预览包与未来正式包分离，不承诺跨预览构建覆盖更新。

## 验证状态

| 项目 | 结果 |
|---|---|
| 规划文档提交到仓库 | 已推送，源码基线 `f31ff887521ee718d52b1f463874b1414c8c665a` |
| 最小工程代码 | 已实现安装预览界面，当前云端编译通过 |
| 云端编译、Lint、APK 权限及签名检查 | 当前云端开发环境全部通过；GitHub Actions 运行中，最终结果待确认 |
| 模拟器安装启动与页面测试 | GitHub Actions 已安排执行，结果待验证 |
| APK 上传和下载链路 | 尚未发布，待 GitHub Actions 全部检查通过及上传 |
| 一加 Ace 5 下载、安装、启动 | 待用户真机验证 |
| 自动识别及真实支付场景 | 本轮未实现，全部待验证 |

## 下一步

跟进已触发的 GitHub Actions，依据实际结果修复问题，验证模拟器和 Release 下载链路；发布成功后补充 APK 下载链接及对应校验值。手机真机结果需用户按安装步骤反馈。

## 当前云端构建证据

- 命令：`./gradlew --no-daemon --console=plain --stacktrace assembleDebug lintDebug assembleDebugAndroidTest`。
- 最终结果：`BUILD SUCCESSFUL in 35s`，75 个任务；Lint 为 0 errors、3 条依赖可升级提醒。固定依赖版本不影响本轮编译通过。
- instrumentation 测试 APK 已编译，但尚未在设备执行；不能写为测试通过。
- `scripts/verify_apk.py` 已对实际 APK 执行并通过：无 INTERNET / ACCESS_NETWORK_STATE，无系统敏感请求权限，仅存在应用自身 signature 级内部权限；唯一导出的启动 Activity，关闭备份及所有云备份/设备迁移数据域，apksigner 验签通过。
- 当前工作区 APK：`dist/auto-ledger-preview.apk`，约 9.7 MiB。尚未上传，不是已可手机下载的链接。
- 此工作区构建 SHA-256：`62086cf6fe62ac52ef5a69b1250e37a98cb8ecb30d64116db451b43a0de74bb4`。GitHub Actions 重新构建会生成自己的临时签名和校验值，不能混用。
- 已确认 APK 包含 `arm64-v8a` 原生库，可覆盖一加设备的 ARM64 架构；系统兼容和安装启动仍需真机验证。

## 写入授权排障历史（已解决）

1. 初始账户级仓库元数据虽返回 `push/admin`，实际 `git push` 和 GitHub `create_tree` 写入 API 均返回 403，不能据元数据声称写入可用。
2. 当时 API 和用户截图确认未安装相应 GitHub App；通过官方应用页核实 [ChatGPT Codex Connector](https://github.com/apps/chatgpt-codex-connector)，发布者为 `openai`。
3. 用户完成官方应用安装并选择 All repositories 后，API 确认内容与工作流写入权限，实际推送成功，写入阻断已解决。
4. Secrets 管理和仓库 administration 不在该应用的授权范围内，与已恢复的内容/工作流写入权限分开处理；本轮预览无需这些管理权限。正式签名配置时再说明必需的具体设置操作。
