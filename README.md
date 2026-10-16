# auto-ledger-android

面向个人使用的 Android 本地自动记账工具，追求简洁、实用、易用。主要测试设备为一加 Ace 5，具体 Android 与系统版本待实机确认。

## 当前进度

第一阶段的云端构建、Lint、实际 APK 审计，以及 Android 15 模拟器启动和三个页面导航测试已通过（2 项测试，0 失败）。[安装预览版已发布](https://github.com/xiaochuan45/auto-ledger-android/releases/tag/preview-38843855896-1)，当前云端已通过公开 Release 链接下载并核对 SHA-256；用户手机网络下载及真机安装启动仍待验证。

**[下载 APK：本地账本·预览 0.1.0-preview](https://github.com/xiaochuan45/auto-ledger-android/releases/download/preview-38843855896-1/auto-ledger-preview.apk)**，约 9.3 MB，最低 Android 8.0。安装方法、签名限制和校验值见 [云端编译与手机安装](docs/BUILD_AND_INSTALL.md)。

这份 APK 是**安装验证版**，不读取通知或页面，不保存真实账单。自动付款识别、账单存储、分类、退款和统计本轮未实现，真实支付场景未验证。具体证据和真机结果见 [第一阶段记录](docs/PHASE1_STATUS.md)。

## 项目文档

- [产品需求与验收约束](docs/REQUIREMENTS.md)：支付范围、准确性、退款、分类、统计及隐私要求。
- [实施规划](docs/PLAN.md)：五个阶段、验收条件、初步时间和需要用户参与的节点。
- [测试矩阵](docs/TEST_MATRIX.md)：自动化与真机测试范围、结果记录要求。
- [云端编译与手机安装](docs/BUILD_AND_INSTALL.md)：编译流程、下载方式、签名限制和安装步骤。
- [第一阶段记录](docs/PHASE1_STATUS.md)：当前证据、限制及下一步。
- [仓库协作约定](AGENTS.md)：后续开发应保持的账务、隐私和交付约束。

## 产品边界

- 识别、分类和存储在手机本地完成，不需要后端，不使用云端大模型。
- 应用不申请联网权限，不接广告、统计或云端日志；交付前检查最终 APK。
- 消费平台、支付渠道和消费分类分别记录；未知信息不编造。
- 付款成功且实付金额明确才自动入账；金额或状态不明进入待核对。
- 交易通知、无障碍页面等来源的覆盖能力以实机验证为准；没有可读来源时提供导入或补记办法。
- 云端开发和测试只使用虚构、脱敏数据，真实账单不提交到仓库。

## 开发方向

原生 Kotlin、Jetpack Compose；账单存储阶段引入 Room。使用 Gradle Wrapper 和 GitHub Actions 在云端编译，不依赖个人电脑。

先走通「云端构建 → APK 下载 → 手机安装启动」，再交付自动识别小样，随后依据真机结果完善功能。每轮明确区分「代码已实现」「模拟或自动化测试通过」「用户手机真机实测通过」。
