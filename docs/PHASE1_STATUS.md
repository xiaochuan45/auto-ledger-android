# 第一阶段执行记录

记录日期：2026-10-16。此文件记录实际结果，不将规划项视为已完成。

## 本轮交付

安装预览附件已发布。当前云端通过公开 Release URL 成功下载 APK、校验值及审计报告，并确认 APK SHA-256 匹配。用户手机网络下载、真机安装和启动仍待用户验证。

| 项目 | 本次发布 |
|---|---|
| Release | [preview-38843855896-1](https://github.com/xiaochuan45/auto-ledger-android/releases/tag/preview-38843855896-1) |
| APK | [auto-ledger-preview.apk](https://github.com/xiaochuan45/auto-ledger-android/releases/download/preview-38843855896-1/auto-ledger-preview.apk) |
| 版本及包名 | `0.1.0-preview` / `1`；`io.github.xiaochuan45.autoledger.preview` |
| 文件大小 | 9,286,218 bytes，约 8.86 MiB / 9.3 MB |
| SHA-256 | `b9e84e3d036e5e59014aa2be7e76b3d186b19b33cac50f5c2c445ad80d1c3776` |
| 对应源码 | [`56083c7aafb77e43fc3fe3841cbf7e22e2dc9022`](https://github.com/xiaochuan45/auto-ledger-android/commit/56083c7aafb77e43fc3fe3841cbf7e22e2dc9022) |
| CI 记录 | [GitHub Actions run 38843855896](https://github.com/xiaochuan45/auto-ledger-android/actions/runs/38843855896)，全部成功 |
| 签名用途 | 本次构建的临时 Debug 签名，仅供安装验证；稳定正式签名与升级保留数据待验证 |

## 已检查

- 仓库读写已恢复。用户安装官方 [ChatGPT Codex Connector](https://github.com/apps/chatgpt-codex-connector)，选择 All repositories；应用权限 API 确认 `contents: write` 和 `workflows: write`。首次恢复写入时，实际 `git push` 将 `main` 从 `c10bd0e` 推进到 [`f31ff887521ee718d52b1f463874b1414c8c665a`](https://github.com/xiaochuan45/auto-ledger-android/commit/f31ff887521ee718d52b1f463874b1414c8c665a)；本次发布源码见上表。
- 云端环境已运行，HTTP 网络策略为 unrestricted/enforced。
- 初始环境只有 Git、GitHub CLI 与 Java 21 运行时。现已在当前云端安装 Temurin JDK 17.0.20.1、Android SDK 35、Build Tools 35.0.0、Platform Tools，并验证 Gradle 8.11.1 可运行。
- GitHub CLI 已登录，可读取仓库及 Actions 工作流列表。
- 官方应用的授权范围不包括 Secrets 管理或仓库 administration 权限；此前相应管理 API 返回 403。此限制不影响本轮临时签名预览，已验证构建和发布成功。当前不需要重新安装或配置 GitHub 应用。
- [GitHub Actions run 38843855896](https://github.com/xiaochuan45/auto-ledger-android/actions/runs/38843855896) 的构建、Lint、实际 APK 审计、模拟器测试、产物一致性检查及发布 job 全部成功。

## 本轮准备

- 需求、分阶段规划、测试矩阵和协作约定已提交并推送到 GitHub。
- 最小 Kotlin / Compose 工程及构建配置已提交并推送。提供账单、统计及设置基础页面，不采集或保存账单。
- 使用官方 Gradle 8.11.1 Wrapper，已核对 Wrapper JAR 官方 SHA-256，并固定发行包 SHA-256。
- 安装验证版包名 `io.github.xiaochuan45.autoledger.preview`，版本 `0.1.0-preview`，最低 Android 8.0（API 26）。
- 使用开发调试签名，只做安装验证；正式账本启用前必须配置稳定私密签名。此预览包与未来正式包分离，不承诺跨预览构建覆盖更新。

## 验证状态

| 项目 | 结果 |
|---|---|
| 规划文档提交到仓库 | 已推送；本次发布对应源码 `56083c7aafb77e43fc3fe3841cbf7e22e2dc9022` |
| 最小工程代码 | 已实现安装预览界面，云端开发环境及 GitHub Actions 均编译通过 |
| 云端编译、Lint、APK 权限及签名检查 | 云端开发环境及 GitHub Actions 均通过 |
| 模拟器安装启动与页面测试 | Android 15 / API 35 模拟器上 2 项通过，0 跳过、0 失败；含启动和账单/统计/设置导航 |
| 交付 APK 与测试构建一致性 | 测试后 `cmp` 和 SHA-256 检查通过 |
| APK 发布及云端公开下载校验 | Release 附件发布成功；公开 URL 下载 APK、校验值与审计报告成功，APK SHA-256 匹配 |
| 一加 Ace 5 下载、安装、启动 | 待用户真机验证 |
| 自动识别及真实支付场景 | 本轮未实现，全部待验证 |
| 稳定正式签名及升级保留数据 | 待实现并验证 |

## 下一步

用户按 [手机安装步骤](BUILD_AND_INSTALL.md#一加手机安装与检查) 下载 APK，确认一加 Ace 5 的 Android/ColorOS 版本、安装、启动和三个页面切换结果。根据真机反馈修复兼容问题，再按规划推进自动识别小样。

## GitHub Actions 与发布证据

- 对应源码提交 `56083c7aafb77e43fc3fe3841cbf7e22e2dc9022`，run `38843855896` 状态为 success，发布 job 成功创建 `preview-38843855896-1`。
- Android 15 / API 35 模拟器日志包含 `Starting 2 tests` 与 `Tests 2/2 completed. (0 skipped) (0 failed)`；测试涵盖明确的空账单安装预览，以及账单、统计、设置导航。此证据不代表用户手机或真实支付场景通过。
- `scripts/verify_apk.py` 对实际安装包检查通过：无 INTERNET / ACCESS_NETWORK_STATE，无系统敏感请求权限；仅允许应用自身 signature 级内部权限；唯一导出的启动 Activity；关闭备份并排除云备份/设备迁移全部数据域；apksigner 验签通过。
- 模拟器测试后，工作流对比测试构建与审计 APK 的 `cmp` 以及 SHA-256 检查成功，随后发布 APK、校验值与审计报告。
- 当前云端通过公开 Release URL 无额外认证下载上述三个附件，APK 大小及 SHA-256 与本轮交付表一致。用户手机网络、下载及真机安装启动仍待验证。

## 初次云端本地构建（历史）

- 命令：`./gradlew --no-daemon --console=plain --stacktrace assembleDebug lintDebug assembleDebugAndroidTest`。
- 最终结果：`BUILD SUCCESSFUL in 35s`，75 个任务；Lint 为 0 errors、3 条依赖可升级提醒。固定依赖版本不影响本轮编译通过。
- 该次本地构建编译了 instrumentation 测试 APK，未在本地设备执行；后续设备测试结果见上方 GitHub Actions 证据。
- 本地实际 APK 审计和签名检查通过，确认该本地包包含 `arm64-v8a` 原生库；系统兼容和安装启动仍需真机验证。
- 历史本地构建 SHA-256 为 `62086cf6fe62ac52ef5a69b1250e37a98cb8ecb30d64116db451b43a0de74bb4`。该包使用另一调试签名，**不能用来校验已发布附件**；下载时使用上方发布 APK 的 `b9e84e…` 完整校验值。

## 首轮 CI 排障历史（已解决）

初始 [run 38843736161](https://github.com/xiaochuan45/auto-ledger-android/actions/runs/38843736161) 在 SDK 准备阶段失败：`setup-android` 默认请求已下架的 `tools` 包。工作流改为明确安装 `platform-tools`，固定 `cmdline-tools` 版本 `11076708`，并使用 `ubuntu-24.04` 后，后续 run `38843855896` 全部检查及发布成功。

## 写入授权排障历史（已解决）

1. 初始账户级仓库元数据虽返回 `push/admin`，实际 `git push` 和 GitHub `create_tree` 写入 API 均返回 403，不能据元数据声称写入可用。
2. 当时 API 和用户截图确认未安装相应 GitHub App；通过官方应用页核实 [ChatGPT Codex Connector](https://github.com/apps/chatgpt-codex-connector)，发布者为 `openai`。
3. 用户完成官方应用安装并选择 All repositories 后，API 确认内容与工作流写入权限，实际推送成功，写入阻断已解决。
4. Secrets 管理和仓库 administration 不在该应用的授权范围内，与已恢复的内容/工作流写入权限分开处理；本轮预览无需这些管理权限。正式签名配置时再说明必需的具体设置操作。
